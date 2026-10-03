package moe.ouom.neriplayer.data.local.playlist

import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.mapping.fromSongItemOrNull
import moe.ouom.neriplayer.data.sync.identity.stableKey
import moe.ouom.neriplayer.data.model.sync.nextLyricSyncRevision

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.local.playlist/LocalPlaylistRepository
 * Updated: 2026/3/23
 */

import moe.ouom.neriplayer.data.model.playlist.LocalPlaylistSongAddResult
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylistSongDeleteResult
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylistDeleteResult
import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomStore
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistPreviewAuthority
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.localMediaUri
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseLikeSyncPlan
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseLikeSyncResult
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseRemotePlaylist
import moe.ouom.neriplayer.platform.netease.playlist.NeteasePlaylistSync
import moe.ouom.neriplayer.platform.netease.playlist.NeteasePlaylistSyncMessage
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.lyrics.offset.rebaseLyricUserOffsetMs
import moe.ouom.neriplayer.lyrics.offset.shouldRebaseLyricOffsetForSource
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.logging.NPLogger
import java.io.File
import java.io.IOException

internal fun resolvePlaylistSongAddedAt(
    song: SongItem,
    membershipAddedAt: Long,
    index: Int,
    preserveScannedSourceAddedAt: Boolean
): Long {
    val fallback = (membershipAddedAt - index).coerceAtLeast(1L)
    if (!preserveScannedSourceAddedAt) return fallback
    return (song.logicalCreatedAtMs ?: song.addedAt)
        .takeIf { it > 0L }
        ?: fallback
}

internal fun shouldRewriteLegacyPlaylistsAfterInitialLoad(
    migrationRequired: Boolean,
    allowMigrationWrite: Boolean,
    roomPromotedDuringLoad: Boolean
): Boolean {
    return migrationRequired && allowMigrationWrite && !roomPromotedDuringLoad
}

class LocalPlaylistRepository private constructor(
    internal val context: Context,
    file: File = File(context.filesDir, "local_playlists.json"),
    internal val normalizePlaylists: (List<LocalPlaylist>) -> List<LocalPlaylist> = { playlists ->
        SystemLocalPlaylists.normalize(playlists, context)
    },
    internal val autoSyncEnabled: Boolean = true,
    internal val loadSynchronously: Boolean = false,
    internal val storage: LocalPlaylistStorage = LocalPlaylistFileStorage(file, context.filesDir),
    internal val providedSyncMutationStore: LocalPlaylistSyncMutationStore? = null,
    internal val providedAutoSyncTrigger: (() -> Unit)? = null,
    internal val roomStore: LocalPlaylistRoomStore? = null
) {
    private val neteasePlaylistSync by lazy {
        NeteasePlaylistSync { message ->
            context.getString(
                when (message) {
                    NeteasePlaylistSyncMessage.EMPTY_SONGS -> CoreCommonR.string.local_playlist_sync_netease_empty
                    NeteasePlaylistSyncMessage.NO_SUPPORTED_SONGS -> CoreCommonR.string.local_playlist_sync_netease_no_supported
                    NeteasePlaylistSyncMessage.LOGIN_REQUIRED -> CoreCommonR.string.playback_login_required
                    NeteasePlaylistSyncMessage.ALL_SYNCED -> CoreCommonR.string.local_playlist_sync_netease_all_synced
                }
            )
        }
    }

    internal val gson = Gson()
    internal val playlistCommitMutex = Mutex()
    internal val syncStorage by lazy { SecureTokenStorage(context) }
    internal val syncMutationStore by lazy {
        providedSyncMutationStore ?: SecureLocalPlaylistSyncMutationStore(syncStorage)
    }
    internal data class SongMetadataUpdate(
        val originalSong: SongItem,
        val newSongInfo: SongItem,
        val clearCoverUrl: Boolean = false,
        val clearOriginalCoverUrl: Boolean = false
    )

    internal val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists
    internal val _playlistCount = MutableStateFlow(0)
    val playlistCount: StateFlow<Int> = _playlistCount
    internal val _syncMutationPending = MutableStateFlow(false)
    val syncMutationPending: StateFlow<Boolean> = _syncMutationPending
    internal val _initializationReadyFlow = MutableStateFlow(false)
    val initializationReadyFlow: StateFlow<Boolean> = _initializationReadyFlow
    internal var preserveBackupOnNextWrite = false
    internal var corruptPrimaryNeedsQuarantine = false
    internal var replaceBackupOnNextWrite = false
    internal val initialLoad = CompletableDeferred<Unit>()
    private val initializationRetryMutex = Mutex()
    @Volatile
    internal var initialLoadFailure: Exception? = null
    internal val initializationScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    @Volatile
    internal var roomStorageEnabled = roomStore != null

    internal data class PlaylistLoadResult(
        val playlists: List<LocalPlaylist>,
        val migrationRequired: Boolean,
        val allowMigrationWrite: Boolean
    )

    internal data class ParsedPlaylistCandidate(
        val decoded: List<LocalPlaylist>,
        val normalized: List<LocalPlaylist>
    )

    init {
        if (loadSynchronously) {
            completeInitialLoad()
        } else {
            initializationScope.launch {
                completeInitialLoad()
            }
        }
    }

    suspend fun awaitInitialized(): Boolean {
        initialLoad.await()
        if (initialLoadFailure == null) return true
        return withContext(Dispatchers.IO) {
            initializationRetryMutex.withLock {
                if (initialLoadFailure != null) {
                    completeInitialLoad()
                }
                currentCoroutineContext().ensureActive()
                initialLoadFailure == null
            }
        }
    }

    /** 首帧只读取当前歌单，权威状态仍由正常初始化流程提供 */
    suspend fun readFastPlaylist(playlistId: Long): LocalPlaylist? = withContext(Dispatchers.IO) {
        val authority = try {
            roomStore?.readFastPlaylistAuthority(playlistId)
                ?: LocalPlaylistPreviewAuthority.Unmigrated
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            NPLogger.w(
                "LocalPlaylistRepo",
                "Fast Room playlist preview unavailable: ${error.message}"
            )
            return@withContext null
        }
        if (authority is LocalPlaylistPreviewAuthority.RoomPrimary) {
            return@withContext authority.playlist
        }

        val primaryText = runCatching { storage.readPrimary() }
            .onFailure { error ->
                NPLogger.w(
                    "LocalPlaylistRepo",
                    "Fast legacy playlist preview unavailable: ${error.message}"
                )
            }
            .getOrNull()
            ?: return@withContext null
        parseFastPlaylistPreview(primaryText, playlistId)
    }

    internal suspend fun requireInitialized() {
        if (!awaitInitialized()) {
            throw IOException("Local playlist initialization failed", initialLoadFailure)
        }
    }

    internal fun songSet(songs: List<SongItem>): Set<SongIdentity> = songs.map { it.identity() }.toSet()

    internal class SongDuplicateIndex(
        private val includeLocalMetadataFallback: Boolean
    ) {
        private val identities = HashSet<SongIdentity>()
        private val localKeys = HashSet<String>()

        fun add(song: SongItem) {
            identities += song.identity()
            localKeys += LocalSongSupport.localDuplicateKeys(song, includeLocalMetadataFallback)
        }

        fun contains(song: SongItem): Boolean {
            if (song.identity() in identities) return true
            val keys = LocalSongSupport.localDuplicateKeys(song, includeLocalMetadataFallback)
            return keys.any(localKeys::contains)
        }
    }

    /** 按来源列表顺序匹配歌曲，避免每个候选都重新扫描整张列表 */
    internal class SongMatchIndex(
        private val songs: List<SongItem>,
        private val includeLocalMetadataFallback: Boolean
    ) {
        private val identityToIndex = HashMap<SongIdentity, Int>()
        private val localKeyToIndex = HashMap<String, Int>()

        init {
            songs.forEachIndexed { index, song ->
                identityToIndex.putIfAbsent(song.identity(), index)
                LocalSongSupport.localDuplicateKeys(
                    song = song,
                    includeMetadataFallback = includeLocalMetadataFallback
                ).forEach { key ->
                    localKeyToIndex.putIfAbsent(key, index)
                }
            }
        }

        fun firstMatch(candidate: SongItem): SongItem? {
            var matchIndex = identityToIndex[candidate.identity()] ?: Int.MAX_VALUE
            LocalSongSupport.localDuplicateKeys(
                song = candidate,
                includeMetadataFallback = includeLocalMetadataFallback
            ).forEach { key ->
                val index = localKeyToIndex[key] ?: return@forEach
                if (index < matchIndex) {
                    matchIndex = index
                }
            }
            return songs.getOrNull(matchIndex)
        }
    }

    suspend fun createPlaylist(name: String) {
        withContext(Dispatchers.IO) {
            commitPlaylistMutation {
                val list = _playlists.value.toMutableList()
                list.add(
                    LocalPlaylist(
                        id = nextPlaylistId(list),
                        name = sanitizePlaylistName(name),
                        modifiedAt = System.currentTimeMillis(),
                        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                    )
                )
                publishLocked(list)
            }
        }
    }

    suspend fun createPlaylistWithSongs(name: String, songs: List<SongItem>): LocalPlaylist {
        return createPlaylistWithSongs(
            name = name,
            songs = songs,
            hydrateLocalMetadata = true
        )
    }

    suspend fun createPlaylistWithScannedSongs(name: String, songs: List<SongItem>): LocalPlaylist {
        return createPlaylistWithSongs(
            name = name,
            songs = songs,
            hydrateLocalMetadata = false,
            preserveScannedSourceAddedAt = true
        )
    }

    suspend fun createPlaylistWithPreparedSongs(name: String, songs: List<SongItem>): LocalPlaylist {
        return createPlaylistWithSongs(
            name = name,
            songs = songs,
            hydrateLocalMetadata = false
        )
    }

    suspend fun addToFavorites(song: SongItem) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val hydratedSong = hydrateLocalSongsForPersistence(listOf(song)).first()
            commitPlaylistMutation {
                val list = _playlists.value.toMutableList()
                val index = list.indexOfFirst { FavoritesPlaylist.isSystemPlaylist(it, context) }
                if (index == -1) return@commitPlaylistMutation

                val favorites = list[index]
                if (
                    hasExistingSong(
                        existingSongs = favorites.songs,
                        candidate = hydratedSong,
                        includeLocalMetadataFallback = true
                    )
                ) {
                    return@commitPlaylistMutation
                }

                val stampedSong = stampSongsForPlaylistInsert(
                    songs = listOf(hydratedSong),
                    addedAt = nextPlaylistSongAddedAt(favorites, now)
                ).first()
                val syncMutation = buildPlaylistSongDeletionRemoval(
                    favorites.id,
                    listOf(hydratedSong)
                )
                list[index] = favorites.copy(
                    songs = mergeNewSongsFirst(favorites.songs, listOf(stampedSong)),
                    modifiedAt = now,
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
                publishLocked(list, syncMutation = syncMutation)
            }
        }
    }

    suspend fun removeFromFavorites(song: SongItem) {
        withContext(Dispatchers.IO) {
            commitPlaylistMutation {
                val list = _playlists.value.toMutableList()
                val index = list.indexOfFirst { FavoritesPlaylist.isSystemPlaylist(it, context) }
                if (index == -1) return@commitPlaylistMutation

                val favorites = list[index]
                val removedSongs = favorites.songs.filter { it.sameIdentityAs(song) }
                val updatedSongs = favorites.songs.filterNot { it.sameIdentityAs(song) }.toMutableList()
                if (updatedSongs.size == favorites.songs.size) return@commitPlaylistMutation

                val deletedAt = System.currentTimeMillis()
                val syncMutation = buildPlaylistSongDeletionMutation(
                    favorites.id,
                    removedSongs,
                    deletedAt
                )
                list[index] = favorites.copy(
                    songs = updatedSongs,
                    modifiedAt = deletedAt,
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
                publishLocked(list, syncMutation = syncMutation)
            }
        }
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String) {
        withContext(Dispatchers.IO) {
            commitPlaylistMutation {
                val updated = _playlists.value.map { playlist ->
                    if (playlist.id != playlistId || SystemLocalPlaylists.isSystemPlaylist(playlist, context)) {
                        playlist
                    } else {
                        playlist.copy(
                            name = sanitizePlaylistName(newName, excludedPlaylistId = playlistId),
                            modifiedAt = System.currentTimeMillis()
                        )
                    }
                }
                publishLocked(updated)
            }
        }
    }

    suspend fun removeSongsFromPlaylistByIdentity(playlistId: Long, songs: List<SongItem>) {
        removeSongsFromPlaylistByIdentityWithResult(playlistId, songs)
    }

    suspend fun removeSongsFromPlaylistByIdentityWithResult(
        playlistId: Long,
        songs: List<SongItem>
    ): List<LocalPlaylistSongDeleteResult> {
        return withContext(Dispatchers.IO) {
            if (songs.isEmpty()) return@withContext emptyList()
            val toRemove = songSet(songs)
            commitPlaylistMutation {
                var syncMutation = LocalPlaylistSyncMutation()
                var deletedSongs = emptyList<LocalPlaylistSongDeleteResult>()
                val updated = _playlists.value.map { playlist ->
                    if (playlist.id != playlistId) return@map playlist
                    val removedSongs = playlist.songs.withIndex()
                        .filter { it.value.identity() in toRemove }
                    val filtered = playlist.songs.filterNot { it.identity() in toRemove }.toMutableList()
                    if (filtered.size == playlist.songs.size) {
                        playlist
                    } else {
                        val deletedAt = System.currentTimeMillis()
                        deletedSongs = removedSongs.map { indexedSong ->
                            LocalPlaylistSongDeleteResult(
                                playlistId = playlist.id,
                                song = indexedSong.value,
                                index = indexedSong.index
                            )
                        }
                        syncMutation += buildPlaylistSongDeletionMutation(
                            playlist.id,
                            deletedSongs.map { it.song },
                            deletedAt
                        )
                        playlist.copy(
                            songs = filtered,
                            modifiedAt = deletedAt,
                            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                        )
                    }
                }
                publishLocked(updated, syncMutation = syncMutation)
                deletedSongs
            }
        }
    }

    suspend fun clearPlaylistSongsWithResult(
        playlistId: Long
    ): List<LocalPlaylistSongDeleteResult> {
        return withContext(Dispatchers.IO) {
            commitPlaylistMutation {
                var changed = false
                var syncMutation = LocalPlaylistSyncMutation()
                var deletedSongs = emptyList<LocalPlaylistSongDeleteResult>()
                val updated = _playlists.value.map { playlist ->
                    if (playlist.id != playlistId || playlist.songs.isEmpty()) {
                        return@map playlist
                    }
                    changed = true
                    val deletedAt = System.currentTimeMillis()
                    deletedSongs = playlist.songs.mapIndexed { index, song ->
                        LocalPlaylistSongDeleteResult(
                            playlistId = playlist.id,
                            song = song,
                            index = index
                        )
                    }
                    syncMutation += buildPlaylistSongDeletionMutation(
                        playlist.id,
                        playlist.songs,
                        deletedAt
                    )
                    playlist.copy(
                        songs = mutableListOf(),
                        modifiedAt = deletedAt,
                        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                    )
                }
                if (!changed) return@commitPlaylistMutation emptyList()
                publishLocked(updated, syncMutation = syncMutation)
                deletedSongs
            }
        }
    }

    suspend fun restoreDeletedSongs(deleteResults: List<LocalPlaylistSongDeleteResult>): Boolean {
        return withContext(Dispatchers.IO) {
            if (deleteResults.isEmpty()) return@withContext false
            commitPlaylistMutation {
                val current = _playlists.value
                val restoreResults = deleteResults
                    .distinctBy { it.playlistId to it.index to it.song.identity() }
                    .sortedBy { it.index }
                val playlistId = restoreResults.firstOrNull()?.playlistId
                    ?: return@commitPlaylistMutation false
                if (restoreResults.any { it.playlistId != playlistId }) {
                    return@commitPlaylistMutation false
                }

                val currentPlaylist = current.firstOrNull { it.id == playlistId }
                    ?: return@commitPlaylistMutation false
                if (
                    !isLocalFilesPlaylist(currentPlaylist.id, currentPlaylist.name) &&
                    SystemLocalPlaylists.isSystemPlaylist(currentPlaylist, context)
                ) {
                    return@commitPlaylistMutation false
                }
                if (restoreResults.any { result ->
                        currentPlaylist.songs.any { it.sameIdentityAs(result.song) }
                    }
                ) {
                    return@commitPlaylistMutation false
                }

                val readdedSongs = renewSongsForPlaylistRestore(
                    restoreResults.map(LocalPlaylistSongDeleteResult::song)
                )
                val restoredSongs = currentPlaylist.songs.toMutableList()
                restoreResults.zip(readdedSongs).forEach { (result, song) ->
                    val insertIndex = result.index.coerceIn(0, restoredSongs.size)
                    restoredSongs.add(insertIndex, song)
                }
                val modifiedAt = System.currentTimeMillis()
                val updated = current.map { playlist ->
                    if (playlist.id != playlistId) {
                        playlist
                    } else {
                        playlist.copy(
                            songs = restoredSongs,
                            modifiedAt = modifiedAt,
                            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                        )
                    }
                }
                publishLocked(
                    playlists = updated,
                    syncMutation = buildPlaylistSongDeletionRemoval(
                        playlistId = playlistId,
                        songs = readdedSongs
                    )
                )
                true
            }
        }
    }

    suspend fun removeSongsFromPlaylistById(playlistId: Long, songIds: List<Long>) {
        withContext(Dispatchers.IO) {
            if (songIds.isEmpty()) return@withContext
            commitPlaylistMutation {
                var syncMutation = LocalPlaylistSyncMutation()
                val updated = _playlists.value.map { playlist ->
                    if (playlist.id != playlistId) return@map playlist
                    val removedSongs = playlist.songs.filter { it.id in songIds }
                    val filtered = playlist.songs.filterNot { it.id in songIds }.toMutableList()
                    if (filtered.size == playlist.songs.size) {
                        return@map playlist
                    }
                    val deletedAt = System.currentTimeMillis()
                    syncMutation += buildPlaylistSongDeletionMutation(
                        playlist.id,
                        removedSongs,
                        deletedAt
                    )
                    playlist.copy(
                        songs = filtered,
                        modifiedAt = deletedAt,
                        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                    )
                }
                publishLocked(updated, syncMutation = syncMutation)
            }
        }
    }

    suspend fun deletePlaylist(playlistId: Long): Boolean {
        return deletePlaylistWithResult(playlistId) != null
    }

    suspend fun deletePlaylistWithResult(playlistId: Long): LocalPlaylistDeleteResult? {
        return deletePlaylistsWithResult(listOf(playlistId)).firstOrNull()
    }

    suspend fun deletePlaylistsWithResult(
        playlistIds: List<Long>
    ): List<LocalPlaylistDeleteResult> {
        return withContext(Dispatchers.IO) {
            if (playlistIds.isEmpty()) return@withContext emptyList()
            val requestedIds = playlistIds.toSet()
            commitPlaylistMutation {
                val current = _playlists.value
                val deleted = current.mapIndexedNotNull { index, playlist ->
                    if (
                        playlist.id in requestedIds &&
                        !SystemLocalPlaylists.isSystemPlaylist(playlist, context)
                    ) {
                        LocalPlaylistDeleteResult(playlist = playlist, index = index)
                    } else {
                        null
                    }
                }
                if (deleted.isEmpty()) {
                    return@commitPlaylistMutation emptyList()
                }

                val deletedIds = deleted.map { it.playlist.id }
                val updated = current.filterNot { it.id in deletedIds }
                publishLocked(
                    playlists = updated,
                    syncMutation = LocalPlaylistSyncMutation(
                        deletedPlaylistIds = deletedIds,
                        clearedPlaylistDeletionIds = deletedIds
                    )
                )
                deleted
            }
        }
    }

    suspend fun restoreDeletedPlaylists(
        deleteResults: List<LocalPlaylistDeleteResult>
    ): Boolean {
        return withContext(Dispatchers.IO) {
            if (deleteResults.isEmpty()) return@withContext false
            commitPlaylistMutation {
                val current = _playlists.value
                val restoreResults = deleteResults
                    .distinctBy { it.playlist.id }
                    .sortedBy { it.index }
                if (restoreResults.any { result ->
                        current.any { it.id == result.playlist.id } ||
                            SystemLocalPlaylists.isSystemPlaylist(result.playlist, context)
                    }
                ) {
                    return@commitPlaylistMutation false
                }

                val restoredModifiedAt = System.currentTimeMillis()
                    .coerceAtMost(Long.MAX_VALUE - 1L) + 1L
                val restored = current.toMutableList()
                restoreResults.forEach { result ->
                    val insertIndex = result.index.coerceIn(0, restored.size)
                    restored.add(
                        insertIndex,
                        result.playlist.copy(
                            modifiedAt = maxOf(
                                restoredModifiedAt,
                                result.playlist.modifiedAt
                                    .coerceAtMost(Long.MAX_VALUE - 1L) + 1L
                            )
                        )
                    )
                }
                publishLocked(
                    playlists = restored,
                    syncMutation = LocalPlaylistSyncMutation(
                        restoredPlaylistIds = restoreResults.map { it.playlist.id }
                    )
                )
                true
            }
        }
    }

    suspend fun moveSong(playlistId: Long, fromIndex: Int, toIndex: Int) {
        withContext(Dispatchers.IO) {
            commitPlaylistMutation {
                val updated = _playlists.value.map { playlist ->
                    if (playlist.id != playlistId) return@map playlist
                    if (fromIndex !in playlist.songs.indices || toIndex !in playlist.songs.indices) return@map playlist

                    val songs = playlist.songs.toMutableList().apply {
                        val song = removeAt(fromIndex)
                        add(toIndex, song)
                    }
                    val modifiedAt = System.currentTimeMillis()
                    playlist.copy(
                        songs = stampSongsForDisplayOrder(songs, modifiedAt),
                        modifiedAt = modifiedAt,
                        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                    )
                }
                publishLocked(updated)
            }
        }
    }

    suspend fun reorderSongs(
        playlistId: Long,
        newOrder: List<SongIdentity>,
        expectedOrder: List<SongIdentity>? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            commitPlaylistMutation {
                if (expectedOrder != null) {
                    val currentOrder = _playlists.value.firstOrNull { it.id == playlistId }
                        ?.songs?.map { it.identity() }
                    // 预览快照与顺序写入共用提交锁，避免确认期间覆盖其他修改
                    if (currentOrder != expectedOrder) return@commitPlaylistMutation false
                    val orderedIdentities = newOrder.toSet()
                    if (newOrder.size != expectedOrder.size ||
                        orderedIdentities.size != newOrder.size ||
                        orderedIdentities != expectedOrder.toSet()
                    ) {
                        return@commitPlaylistMutation false
                    }
                }
                val updated = _playlists.value.map { playlist ->
                    if (playlist.id != playlistId) return@map playlist
                    val byIdentity = playlist.songs.associateBy { it.identity() }
                    val ordered = newOrder.mapNotNull { byIdentity[it] }.toMutableList()
                    val orderedIndex = SongDuplicateIndex(
                        includeLocalMetadataFallback = false
                    ).apply {
                        ordered.forEach(::add)
                    }
                    playlist.songs.forEach { song ->
                        if (!orderedIndex.contains(song)) {
                            ordered += song
                            orderedIndex.add(song)
                        }
                    }
                    val modifiedAt = System.currentTimeMillis()
                    playlist.copy(
                        songs = stampSongsForDisplayOrder(ordered, modifiedAt),
                        modifiedAt = modifiedAt,
                        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                    )
                }
                publishLocked(updated)
                true
            }
        }
    }

    suspend fun addSongsToPlaylist(playlistId: Long, songs: List<SongItem>) {
        addSongsToPlaylistAndCount(playlistId, songs)
    }

    suspend fun addSongsToPlaylistAndCount(playlistId: Long, songs: List<SongItem>): Int {
        return addSongsToPlaylistWithResult(
            playlistId = playlistId,
            songs = songs,
            hydrateLocalMetadata = true
        ).addedCount
    }

    suspend fun addSongsToPlaylistWithResult(
        playlistId: Long,
        songs: List<SongItem>
    ): LocalPlaylistSongAddResult {
        return addSongsToPlaylistWithResult(
            playlistId = playlistId,
            songs = songs,
            hydrateLocalMetadata = true
        )
    }

    suspend fun addScannedSongsToPlaylistAndCount(playlistId: Long, songs: List<SongItem>): Int {
        return addScannedSongsToPlaylistWithResult(playlistId, songs).addedCount
    }

    suspend fun addScannedSongsToPlaylistWithResult(
        playlistId: Long,
        songs: List<SongItem>
    ): LocalPlaylistSongAddResult {
        return addSongsToPlaylistWithResult(
            playlistId = playlistId,
            songs = songs,
            hydrateLocalMetadata = false,
            includeLocalMetadataFallback = true,
            preserveScannedSourceAddedAt = true
        )
    }

    suspend fun addPreparedSongsToPlaylist(playlistId: Long, songs: List<SongItem>) {
        addPreparedSongsToPlaylistAndCount(playlistId, songs)
    }

    suspend fun addPreparedSongsToPlaylistAndCount(playlistId: Long, songs: List<SongItem>): Int {
        return addPreparedSongsToPlaylistWithResult(playlistId, songs).addedCount
    }

    suspend fun addPreparedSongsToPlaylistWithResult(
        playlistId: Long,
        songs: List<SongItem>
    ): LocalPlaylistSongAddResult {
        return addSongsToPlaylistWithResult(
            playlistId = playlistId,
            songs = songs,
            hydrateLocalMetadata = false
        )
    }

    suspend fun addSongsToLocalFilesPlaylist(songs: List<SongItem>) {
        addSongsToLocalFilesPlaylistAndCount(songs)
    }

    suspend fun addSongsToLocalFilesPlaylistAndCount(songs: List<SongItem>): Int {
        return addSongsToLocalFilesPlaylistWithResult(
            songs = songs,
            hydrateLocalMetadata = true
        ).addedCount
    }

    suspend fun addScannedSongsToLocalFilesPlaylistAndCount(songs: List<SongItem>): Int {
        return addScannedSongsToLocalFilesPlaylistWithResult(songs).addedCount
    }

    suspend fun addScannedSongsToLocalFilesPlaylistWithResult(
        songs: List<SongItem>
    ): LocalPlaylistSongAddResult {
        return addSongsToLocalFilesPlaylistWithResult(
            songs = songs,
            hydrateLocalMetadata = false,
            preserveScannedSourceAddedAt = true
        )
    }

    suspend fun refreshScannedLocalSongMetadata(
        songs: List<SongItem>,
        includeEmbeddedAssets: Boolean = false,
        includeLyricContents: Boolean = false,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        withContext(Dispatchers.IO) {
            val candidates = distinctPlaylistSongs(
                songs = songs.filter { LocalSongSupport.isLocalSong(it, context) },
                includeLocalMetadataFallback = true
            )
            onProgress(0, candidates.size)
            if (candidates.isEmpty()) {
                return@withContext
            }

            val refreshDispatcher = Dispatchers.IO.limitedParallelism(LOCAL_METADATA_REFRESH_PARALLELISM)
            var processedCount = 0
            val allUpdates = ArrayList<SongMetadataUpdate>()
            val refreshStartedAt = SystemClock.elapsedRealtime()
            candidates.chunked(LOCAL_METADATA_REFRESH_BATCH_SIZE).forEach { batch ->
                val batchStartedAt = SystemClock.elapsedRealtime()
                val updates = coroutineScope {
                    batch.map { originalSong ->
                        async(refreshDispatcher) {
                            when {
                                includeEmbeddedAssets -> SongMetadataUpdate(
                                    originalSong = originalSong,
                                    newSongInfo = LocalAudioImportManager.hydrateLocalSongMetadata(
                                        context,
                                        originalSong
                                    )
                                )
                                includeLyricContents -> SongMetadataUpdate(
                                    originalSong = originalSong,
                                    newSongInfo = LocalAudioImportManager.hydrateLocalSongTextMetadata(
                                        context,
                                        originalSong
                                    )
                                )
                                else -> LocalAudioImportManager
                                    .hydrateLocalSongCoverMetadataResult(context, originalSong)
                                    .let { result ->
                                        SongMetadataUpdate(
                                            originalSong = originalSong,
                                            newSongInfo = result.song,
                                            clearCoverUrl = result.clearCoverUrl,
                                            clearOriginalCoverUrl = result.clearOriginalCoverUrl
                                        )
                                    }
                            }
                        }
                    }.awaitAll()
                }.filter { update ->
                    update.newSongInfo != update.originalSong
                }
                allUpdates += updates
                processedCount += batch.size
                onProgress(processedCount, candidates.size)
                NPLogger.d(
                    "LocalPlaylistRepo",
                    "本地扫描后台批次完成: processed=$processedCount/${candidates.size}, " +
                        "updates=${updates.size}, elapsed=" +
                        "${SystemClock.elapsedRealtime() - batchStartedAt}ms"
                )
            }
            applySongMetadataUpdates(allUpdates)
            NPLogger.d(
                "LocalPlaylistRepo",
                "本地扫描后台刷新完成: candidates=${candidates.size}, " +
                    "updates=${allUpdates.size}, elapsed=" +
                    "${SystemClock.elapsedRealtime() - refreshStartedAt}ms"
            )
        }
    }

    suspend fun refreshMissingLocalSongDurations(
        songs: List<SongItem>,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        withContext(Dispatchers.IO) {
            val candidates = distinctPlaylistSongs(
                songs = songs.filter {
                    it.durationMs <= 0L && LocalSongSupport.isLocalSong(it, context)
                },
                includeLocalMetadataFallback = true
            ).filter { song ->
                song.localFilePath?.isNotBlank() == true ||
                    song.mediaUri?.let { reference ->
                        reference.startsWith("content://", ignoreCase = true) ||
                            reference.startsWith("file://", ignoreCase = true) ||
                            reference.startsWith("/")
                    } == true
            }
            onProgress(0, candidates.size)
            if (candidates.isEmpty()) return@withContext

            val dispatcher = Dispatchers.IO.limitedParallelism(
                LOCAL_DURATION_REFRESH_PARALLELISM
            )
            val mediaStoreDurations = LocalMediaSupport.resolveMediaStoreDurationsFast(
                context = context,
                sources = candidates.mapNotNull { it.localMediaUri() }
            )
            val updates = ArrayList<Pair<SongItem, SongItem>>(candidates.size)
            var processed = 0
            candidates.chunked(LOCAL_DURATION_REFRESH_BATCH_SIZE).forEach { batch ->
                updates += coroutineScope {
                    batch.map { originalSong ->
                        async(dispatcher) {
                            val bulkDuration = originalSong.localMediaUri()
                                ?.toString()
                                ?.let(mediaStoreDurations::get)
                            val hydratedSong = bulkDuration?.let { durationMs ->
                                originalSong.copy(durationMs = durationMs)
                            } ?: LocalAudioImportManager.hydrateLocalSongDurationMetadata(
                                context,
                                originalSong
                            )
                            originalSong to hydratedSong
                        }
                    }.awaitAll()
                }.filter { (originalSong, hydratedSong) ->
                    hydratedSong != originalSong
                }
                processed += batch.size
                onProgress(processed, candidates.size)
            }
            applySongDurationUpdates(updates)
        }
    }

    suspend fun addSongToPlaylist(playlistId: Long, song: SongItem) {
        addSongsToPlaylist(playlistId, listOf(song))
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, song: SongItem) {
        removeSongsFromPlaylistByIdentity(playlistId, listOf(song))
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        removeSongsFromPlaylistById(playlistId, listOf(songId))
    }

    suspend fun exportSongsToPlaylistByIdentity(sourcePlaylistId: Long, targetPlaylistId: Long, songs: List<SongItem>) {
        withContext(Dispatchers.IO) {
            val wanted = songSet(songs)
            val now = System.currentTimeMillis()
            commitPlaylistMutation {
                val source = _playlists.value.firstOrNull { it.id == sourcePlaylistId }
                    ?: return@commitPlaylistMutation
                val inSourceOrder = source.songs.filter { it.identity() in wanted }
                addStampedSongsToPlaylistLocked(targetPlaylistId, inSourceOrder, now)
            }
        }
    }

    suspend fun updateSongMetadata(
        originalSong: SongItem,
        newSongInfo: SongItem,
        triggerSync: Boolean = false
    ) {
        withContext(Dispatchers.IO) {
            recordLyricSyncState(newSongInfo)
            commitPlaylistMutation {
                val modifiedAt = if (triggerSync) System.currentTimeMillis() else null
                var changed = false
                val updated = _playlists.value.map { playlist ->
                    val songIndex = playlist.songs.indexOfFirst { it.sameIdentityAs(originalSong) }
                    if (songIndex == -1) {
                        playlist
                    } else {
                        val mergedSongInfo = mergeSongMetadataForPersistence(
                            currentSong = playlist.songs[songIndex],
                            newSongInfo = newSongInfo
                        )
                        if (playlist.songs[songIndex] == mergedSongInfo) {
                            return@map playlist
                        }
                        saveCoverMapping(mergedSongInfo)
                        val songs = playlist.songs.toMutableList()
                        songs[songIndex] = mergedSongInfo
                        changed = true
                        playlist.copy(
                            songs = songs,
                            modifiedAt = modifiedAt ?: playlist.modifiedAt,
                            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                        )
                    }
                }
                if (!changed) {
                    return@commitPlaylistMutation
                }
                // 调用方决定这次元数据变更是不是用户动作，避免播放期自动补全顺手唤醒云同步
                publishLocked(
                    playlists = updated,
                    triggerSync = triggerSync,
                    markLocalMutation = triggerSync
                )
            }
        }
    }

    suspend fun recordLyricSyncState(song: SongItem) {
        if (song.lyricSyncEdited == null || song.lyricSyncRevision <= 0L) return
        withContext(Dispatchers.IO) {
            SyncSong.fromSongItemOrNull(song, context)
                ?.let(syncStorage::recordLyricOverride)
        }
    }

    suspend fun nextUserLyricSyncRevision(song: SongItem): Long = withContext(Dispatchers.IO) {
        val syncSong = SyncSong.fromSongItemOrNull(song, context)
        val knownRevision = if (syncSong == null) 0L else {
            val key = syncSong.stableKey()
            syncStorage.getLyricOverrides().firstOrNull { it.stableKey() == key }?.lyricSyncRevision ?: 0L
        }
        nextLyricSyncRevision(maxOf(song.lyricSyncRevision, knownRevision), System.currentTimeMillis())
    }

    internal class SongMetadataUpdateIndex(updates: List<SongMetadataUpdate>) {
        private val byIdentity = HashMap<SongIdentity, SongMetadataUpdate>(updates.size * 2)
        private val byLocalKey = HashMap<String, SongMetadataUpdate>(updates.size * 3)

        init {
            updates.forEach { update ->
                byIdentity[update.originalSong.identity()] = update
                LocalSongSupport.localDuplicateKeys(
                    song = update.originalSong,
                    includeMetadataFallback = true
                ).forEach { key ->
                    byLocalKey.putIfAbsent(key, update)
                }
            }
        }

        fun find(song: SongItem): SongMetadataUpdate? {
            byIdentity[song.identity()]?.let { return it }
            return LocalSongSupport.localDuplicateKeys(
                song = song,
                includeMetadataFallback = true
            ).firstNotNullOfOrNull(byLocalKey::get)
        }
    }

    suspend fun updateSongMetadata(
        songId: Long,
        albumIdentifier: String,
        newSongInfo: SongItem,
        triggerSync: Boolean = false
    ) {
        updateSongMetadata(
            originalSong = newSongInfo.copy(id = songId, album = albumIdentifier),
            newSongInfo = newSongInfo,
            triggerSync = triggerSync
        )
    }

    suspend fun rebaseLyricOffsetsForSource(
        targetSource: MusicPlatform,
        previousDefaultOffsetMs: Long,
        newDefaultOffsetMs: Long
    ) {
        if (previousDefaultOffsetMs == newDefaultOffsetMs) {
            return
        }
        withContext(Dispatchers.IO) {
            commitPlaylistMutation {
                val modifiedAt = System.currentTimeMillis()
                var changed = false
                val updated = _playlists.value.map { playlist ->
                    var playlistChanged = false
                    val updatedSongs = playlist.songs.map { song ->
                        if (
                            shouldRebaseLyricOffsetForSource(
                                lyricSource = song.matchedLyricSource,
                                targetSource = targetSource,
                                userOffsetMs = song.userLyricOffsetMs
                            )
                        ) {
                            changed = true
                            playlistChanged = true
                            song.copy(
                                userLyricOffsetMs = rebaseLyricUserOffsetMs(
                                    userOffsetMs = song.userLyricOffsetMs,
                                    previousDefaultOffsetMs = previousDefaultOffsetMs,
                                    newDefaultOffsetMs = newDefaultOffsetMs
                                )
                            )
                        } else {
                            song
                        }
                    }
                    if (!playlistChanged) {
                        playlist
                    } else {
                        playlist.copy(
                            songs = updatedSongs.toMutableList(),
                            modifiedAt = modifiedAt,
                            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                        )
                    }
                }
                if (changed) {
                    publishLocked(
                        playlists = updated,
                        triggerSync = true,
                        markLocalMutation = true
                    )
                }
            }
        }
    }

    suspend fun updatePlaylists(
        playlists: List<LocalPlaylist>,
        triggerSync: Boolean = false,
        restoredPlaylistIds: Set<Long> = emptySet()
    ) {
        withContext(Dispatchers.IO) {
            commitPlaylistMutation {
                val merged = mergeExternalPlaylists(playlists)
                publishLocked(
                    playlists = merged,
                    triggerSync = triggerSync,
                    syncMutation = LocalPlaylistSyncMutation(
                        restoredPlaylistIds = restoredPlaylistIds.sorted()
                    )
                )
            }
        }
    }

    internal suspend fun <T> withCommittedSyncSnapshot(read: () -> T): T {
        return commitPlaylistMutation {
            flushPendingSyncMutationBeforeSync()
            currentCoroutineContext().ensureActive()
            // 后续处理与网络请求留在锁外，避免阻塞本地编辑
            val captured = read()
            currentCoroutineContext().ensureActive()
            captured
        }
    }

    private suspend fun flushPendingSyncMutationBeforeSync() {
        if (_syncMutationPending.value) {
            flushPendingSyncMutation(LocalPlaylistRoomStore.domainDigest(_playlists.value))
        }
    }

    internal suspend fun applySyncedPlaylistsIfUnchanged(
        playlists: List<LocalPlaylist>,
        expectedMutationVersion: Long
    ): Boolean {
        return withContext(Dispatchers.IO) {
            commitPlaylistMutation {
                flushPendingSyncMutationBeforeSync()
                if (syncMutationStore.getSyncMutationVersion() != expectedMutationVersion) {
                    return@commitPlaylistMutation false
                }
                publishLocked(
                    playlists = mergeExternalPlaylists(playlists),
                    triggerSync = false,
                    markLocalMutation = false
                )
                true
            }
        }
    }

    suspend fun reorderPlaylists(newOrder: List<Long>) {
        withContext(Dispatchers.IO) {
            commitPlaylistMutation {
                val current = _playlists.value
                val system = current.filter { SystemLocalPlaylists.isSystemPlaylist(it, context) }
                val others = current.filterNot { SystemLocalPlaylists.isSystemPlaylist(it, context) }
                if (others.size <= 1) return@commitPlaylistMutation

                val byId = others.associateBy { it.id }
                val ordered = newOrder.mapNotNull { byId[it] }.toMutableList()
                others.forEach { playlist ->
                    if (ordered.none { it.id == playlist.id }) ordered += playlist
                }
                if (ordered.map(LocalPlaylist::id) == others.map(LocalPlaylist::id)) {
                    return@commitPlaylistMutation
                }

                val modifiedAt = System.currentTimeMillis()
                val reordered = ordered.map { playlist ->
                    // 歌单顺序属于全局状态，重排后统一刷新 modifiedAt，便于同步层感知顺序变化
                    playlist.copy(modifiedAt = modifiedAt)
                }
                publishLocked(reordered + system)
            }
        }
    }

    fun filterNeteaseLikeSyncCandidates(songs: List<SongItem>): List<SongItem> {
        return neteasePlaylistSync.filterNeteaseLikeSyncCandidates(songs)
    }

    fun filterNeteaseLikeSyncCandidatesPreservingDuplicates(songs: List<SongItem>): List<SongItem> {
        return neteasePlaylistSync.filterNeteaseLikeSyncCandidatesPreservingDuplicates(songs)
    }

    suspend fun fetchNeteaseRemotePlaylists(client: NeteaseClient): List<NeteaseRemotePlaylist> {
        return neteasePlaylistSync.fetchNeteaseRemotePlaylists(client)
    }

    suspend fun prepareNeteaseLikeSyncPlan(
        client: NeteaseClient,
        songs: List<SongItem>
    ): NeteaseLikeSyncPlan {
        return neteasePlaylistSync.prepareNeteaseLikeSyncPlan(client, songs)
    }

    suspend fun prepareNeteasePlaylistSyncPlan(
        client: NeteaseClient,
        targetPlaylistId: Long,
        songs: List<SongItem>
    ): NeteaseLikeSyncPlan {
        return neteasePlaylistSync.prepareNeteasePlaylistSyncPlan(client, targetPlaylistId, songs)
    }

    suspend fun syncFavoritesToNeteaseLiked(client: NeteaseClient): NeteaseLikeSyncResult =
        withContext(Dispatchers.IO) {
            requireInitialized()
            val favorites = FavoritesPlaylist.firstOrNull(_playlists.value, context)
            syncSongsToNeteaseLiked(client, favorites?.songs.orEmpty())
        }

    suspend fun syncSongsToNeteaseLiked(
        client: NeteaseClient,
        songs: List<SongItem>
    ): NeteaseLikeSyncResult {
        return neteasePlaylistSync.syncSongsToNeteaseLiked(client, songs)
    }

    suspend fun syncSongsToNeteasePlaylist(
        client: NeteaseClient,
        targetPlaylistId: Long,
        songs: List<SongItem>
    ): NeteaseLikeSyncResult {
        return neteasePlaylistSync.syncSongsToNeteasePlaylist(client, targetPlaylistId, songs)
    }

    companion object {
        const val MAX_PLAYLIST_NAME_LENGTH = 10
        internal const val LOCAL_METADATA_HYDRATE_BATCH_SIZE = 48
        internal const val LOCAL_METADATA_REFRESH_BATCH_SIZE = 48
        internal const val LOCAL_METADATA_REFRESH_PARALLELISM = 4
        internal const val LOCAL_DURATION_REFRESH_BATCH_SIZE = 64
        internal const val LOCAL_DURATION_REFRESH_PARALLELISM = 16

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: LocalPlaylistRepository? = null

        fun getInstance(context: Context): LocalPlaylistRepository {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                INSTANCE ?: LocalPlaylistRepository(
                    context = appContext,
                    roomStore = LocalPlaylistRoomStore(
                        database = NeriUserDataDatabase.getInstance(appContext)
                    )
                ).also { INSTANCE = it }
            }
        }

        internal fun createForTest(
            context: Context,
            file: File,
            normalizePlaylists: (List<LocalPlaylist>) -> List<LocalPlaylist> = { it },
            autoSyncEnabled: Boolean = false,
            loadSynchronously: Boolean = true,
            storage: LocalPlaylistStorage = LocalPlaylistFileStorage(file, context.filesDir),
            syncMutationStore: LocalPlaylistSyncMutationStore? = null,
            autoSyncTrigger: (() -> Unit)? = null,
            roomStore: LocalPlaylistRoomStore? = null
        ): LocalPlaylistRepository {
            return LocalPlaylistRepository(
                context = context,
                file = file,
                normalizePlaylists = normalizePlaylists,
                autoSyncEnabled = autoSyncEnabled,
                loadSynchronously = loadSynchronously,
                storage = storage,
                providedSyncMutationStore =
                    syncMutationStore ?: InMemoryLocalPlaylistSyncMutationStore(),
                providedAutoSyncTrigger = autoSyncTrigger,
                roomStore = roomStore
            )
        }
    }
}
