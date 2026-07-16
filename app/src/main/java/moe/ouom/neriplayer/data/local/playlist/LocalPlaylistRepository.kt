package moe.ouom.neriplayer.data.local.playlist

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

import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.model.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.sync.NeteaseLikeSyncPlan
import moe.ouom.neriplayer.data.local.playlist.sync.NeteaseLikeSyncResult
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.settings.rebaseLyricUserOffsetMs
import moe.ouom.neriplayer.data.settings.shouldRebaseLyricOffsetForSource
import moe.ouom.neriplayer.data.sync.github.CoverUrlMapper
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.github.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.model.normalizedSyncCausalTokens
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

class LocalPlaylistRepository private constructor(
    private val context: Context,
    file: File = File(context.filesDir, "local_playlists.json"),
    private val normalizePlaylists: (List<LocalPlaylist>) -> List<LocalPlaylist> = { playlists ->
        SystemLocalPlaylists.normalize(playlists, context)
    },
    private val autoSyncEnabled: Boolean = true,
    private val storage: LocalPlaylistStorage = LocalPlaylistFileStorage(file, context.filesDir),
    private val providedSyncMutationStore: LocalPlaylistSyncMutationStore? = null,
    private val providedAutoSyncTrigger: (() -> Unit)? = null
) {
    private val gson = Gson()
    private val playlistCommitMutex = Mutex()
    private val syncStorage by lazy { SecureTokenStorage(context) }
    private val syncMutationStore by lazy {
        providedSyncMutationStore ?: SecureLocalPlaylistSyncMutationStore(syncStorage)
    }
    private val recentNeteaseLikedIds = Collections.synchronizedSet(mutableSetOf<Long>())

    private data class NeteaseResolvedCandidate(
        val song: SongItem,
        val neteaseId: Long
    )

    private data class LocalNeteaseCandidateSummary(
        val supportedSongs: Int,
        val skippedUnsupported: Int,
        val skippedExisting: Int,
        val candidates: List<NeteaseResolvedCandidate>
    )

    private data class NeteaseCandidateValidationResult(
        val supportedSongs: Int,
        val skippedUnsupported: Int,
        val skippedExisting: Int,
        val candidates: List<NeteaseResolvedCandidate>
    )

    private data class NeteaseLikedIdsFetchResult(
        val likedIds: Set<Long>,
        val likedFingerprints: Set<String> = emptySet(),
        val compareSucceeded: Boolean,
        val message: String? = null
    )

    private data class ParsedNeteaseIds(
        val ids: Set<Long>,
        val success: Boolean
    )

    private data class ParsedNeteasePlaylistId(
        val playlistId: Long?,
        val success: Boolean
    )

    private data class ParsedNeteasePlaylistTrackIds(
        val trackIds: List<Long>,
        val trackCount: Int,
        val success: Boolean
    )

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists
    private val _playlistCount = MutableStateFlow(0)
    val playlistCount: StateFlow<Int> = _playlistCount
    private val _syncMutationPending = MutableStateFlow(false)
    val syncMutationPending: StateFlow<Boolean> = _syncMutationPending
    private var preserveBackupOnNextWrite = false
    private var corruptPrimaryNeedsQuarantine = false
    private var replaceBackupOnNextWrite = false

    private data class PlaylistLoadResult(
        val playlists: List<LocalPlaylist>,
        val migrationRequired: Boolean,
        val allowMigrationWrite: Boolean,
        val committedPrimaryText: String?
    )

    private data class ParsedPlaylistCandidate(
        val decoded: List<LocalPlaylist>,
        val normalized: List<LocalPlaylist>
    )

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val loadResult = readStoredPlaylists()
        recoverPendingSyncMutation(loadResult.committedPrimaryText)
        if (loadResult.migrationRequired && loadResult.allowMigrationWrite) {
            runCatching {
                persistToDisk(loadResult.playlists)
            }.onFailure { error ->
                NPLogger.e("LocalPlaylistRepo", "Failed to persist normalized playlists", error)
            }
        }
        _playlists.value = loadResult.playlists
        _playlistCount.value = loadResult.playlists.size
    }

    private fun readStoredPlaylists(): PlaylistLoadResult {
        val primaryRead = runCatching(storage::readPrimary)
        val primaryText = primaryRead.getOrNull()
        if (primaryRead.isSuccess && primaryText == null) {
            return recoverFromBackup(primaryWasCorrupt = false)
                ?: emptyPlaylistLoadResult(allowMigrationWrite = true)
        }

        if (primaryRead.isFailure) {
            NPLogger.e(
                "LocalPlaylistRepo",
                "Failed to read primary playlist storage",
                primaryRead.exceptionOrNull()
            )
            preserveBackupOnNextWrite = true
            return recoverFromBackup(primaryWasCorrupt = false)
                ?: emptyPlaylistLoadResult(allowMigrationWrite = false)
        }

        val primaryParsed = parsePlaylists(primaryText.orEmpty(), "primary")
        if (primaryParsed != null) {
            return PlaylistLoadResult(
                playlists = primaryParsed.normalized,
                migrationRequired = primaryParsed.normalized != primaryParsed.decoded,
                allowMigrationWrite = true,
                committedPrimaryText = primaryText
            )
        }

        return recoverFromBackup(primaryWasCorrupt = true)
            ?: emptyPlaylistLoadResult(allowMigrationWrite = false)
    }

    private fun emptyPlaylistLoadResult(allowMigrationWrite: Boolean): PlaylistLoadResult {
        val normalized = normalizePlaylistOrder(emptyList())
        return PlaylistLoadResult(
            playlists = normalized,
            migrationRequired = normalized.isNotEmpty(),
            allowMigrationWrite = allowMigrationWrite,
            committedPrimaryText = null
        )
    }

    private fun recoverFromBackup(primaryWasCorrupt: Boolean): PlaylistLoadResult? {
        val backupRead = runCatching(storage::readBackup)
        val backupText = backupRead.getOrNull()
        if (backupRead.isFailure) {
            NPLogger.e(
                "LocalPlaylistRepo",
                "Failed to read playlist backup",
                backupRead.exceptionOrNull()
            )
        }

        val backupParsed = backupText?.let { parsePlaylists(it, "backup") }
        if (backupText != null && backupParsed == null) {
            replaceBackupOnNextWrite = true
        }
        val primaryReadyForRestore = if (primaryWasCorrupt) {
            corruptPrimaryNeedsQuarantine = true
            quarantineCorruptPrimary()
        } else {
            true
        }
        if (backupParsed == null) {
            return null
        }

        val repairSucceeded = primaryReadyForRestore &&
            runCatching {
                storage.commit(backupText, rotateBackup = false)
            }.onFailure { error ->
                preserveBackupOnNextWrite = true
                NPLogger.e("LocalPlaylistRepo", "Failed to restore playlist backup", error)
            }.isSuccess
        return PlaylistLoadResult(
            playlists = backupParsed.normalized,
            migrationRequired = backupParsed.normalized != backupParsed.decoded,
            allowMigrationWrite = repairSucceeded,
            committedPrimaryText = backupText.takeIf { repairSucceeded }
        )
    }

    private fun quarantineCorruptPrimary(): Boolean {
        return runCatching(storage::quarantinePrimary)
            .onSuccess { quarantine ->
                corruptPrimaryNeedsQuarantine = false
                if (quarantine != null) {
                    NPLogger.w(
                        "LocalPlaylistRepo",
                        "Quarantined corrupt playlist storage: ${quarantine.name}"
                    )
                }
            }
            .onFailure { error ->
                preserveBackupOnNextWrite = true
                NPLogger.e("LocalPlaylistRepo", "Failed to quarantine corrupt playlists", error)
            }
            .isSuccess
    }

    private fun parsePlaylists(text: String, source: String): ParsedPlaylistCandidate? {
        return runCatching {
            validateLocalPlaylistJson(text, source)
            val type = object : TypeToken<List<LocalPlaylist>>() {}.type
            val decoded = requireNotNull(gson.fromJson<List<LocalPlaylist>>(text, type)) {
                "Playlist $source contains JSON null"
            }
            ParsedPlaylistCandidate(
                decoded = decoded,
                normalized = normalizePlaylistOrder(decoded)
            )
        }.onFailure { error ->
            NPLogger.e("LocalPlaylistRepo", "Failed to parse $source playlists", error)
        }.getOrNull()
    }

    private fun migratePlaylistSongOrder(playlists: List<LocalPlaylist>): List<LocalPlaylist> {
        if (playlists.isEmpty()) return playlists

        var changed = false
        val migrated = playlists.map { playlist ->
            if (playlist.songOrderVersion >= DISPLAY_ORDER_SONG_ORDER_VERSION) {
                val displaySongs = sortSongsByAddedAtForDisplay(playlist.songs)
                if (displaySongs == playlist.songs) {
                    playlist
                } else {
                    changed = true
                    playlist.copy(songs = displaySongs)
                }
            } else {
                changed = true
                playlist.copy(
                    songs = migrateLegacySongsToDisplayOrder(playlist.songs, playlist.modifiedAt),
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            }
        }
        return if (changed) migrated else playlists
    }

    private fun normalizePlaylistOrder(playlists: List<LocalPlaylist>): List<LocalPlaylist> {
        val normalizedMemberships = normalizeSongMembershipTokens(playlists)
        val migrated = migratePlaylistSongOrder(normalizedMemberships)
        return migratePlaylistSongOrder(
            normalizeSongMembershipTokens(normalizePlaylists(migrated))
        )
    }

    private fun normalizeSongMembershipTokens(
        playlists: List<LocalPlaylist>
    ): List<LocalPlaylist> {
        var changed = false
        val normalized = playlists.map { playlist ->
            var playlistChanged = false
            val songs = playlist.songs.mapTo(mutableListOf()) { song ->
                val normalizedTokens = song.syncMembershipTokens.normalizedSyncCausalTokens()
                if (normalizedTokens == song.syncMembershipTokens) {
                    song
                } else {
                    changed = true
                    playlistChanged = true
                    song.copy(syncMembershipTokens = normalizedTokens)
                }
            }
            if (playlistChanged) playlist.copy(songs = songs) else playlist
        }
        return if (changed) normalized else playlists
    }

    private fun migrateLegacySongsToDisplayOrder(
        songs: List<SongItem>,
        playlistModifiedAt: Long
    ): MutableList<SongItem> {
        if (songs.isEmpty()) return mutableListOf()

        val newestAddedAt = maxOf(
            System.currentTimeMillis(),
            playlistModifiedAt,
            songs.maxOfOrNull { it.addedAt } ?: 0L
        )
        return songs
            .asReversed()
            .mapIndexed { index, song ->
                val displayAddedAt = (newestAddedAt - index).coerceAtLeast(1L)
                song.copy(addedAt = displayAddedAt)
            }
            .toMutableList()
    }

    private fun sortSongsByAddedAtForDisplay(songs: List<SongItem>): MutableList<SongItem> {
        if (songs.size < 2) return songs.toMutableList()
        return songs
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<SongItem>> { it.value.addedAt }
                    .thenBy { it.index }
            )
            .mapTo(mutableListOf()) { it.value }
    }

    private fun persistToDisk(playlists: List<LocalPlaylist>, serialized: String = gson.toJson(playlists)) {
        if (corruptPrimaryNeedsQuarantine && !quarantineCorruptPrimary()) {
            throw IOException("Corrupt playlist storage could not be quarantined")
        }
        storage.commit(
            text = serialized,
            rotateBackup = !preserveBackupOnNextWrite,
            replaceBackupWithCommittedPrimary = replaceBackupOnNextWrite
        )
        preserveBackupOnNextWrite = false
        replaceBackupOnNextWrite = false
    }

    private suspend fun <T> commitPlaylistMutation(block: () -> T): T {
        return playlistCommitMutex.withLock {
            block()
        }
    }

    private fun publishLocked(
        playlists: List<LocalPlaylist>,
        triggerSync: Boolean = true,
        syncMutation: LocalPlaylistSyncMutation = LocalPlaylistSyncMutation(),
        markLocalMutation: Boolean = triggerSync
    ) {
        val normalized = normalizePlaylistOrder(playlists)
        val stateChanged = normalized != _playlists.value
        if (!stateChanged && syncMutation.isEmpty) {
            if (markLocalMutation) {
                syncMutationStore.markSyncMutation()
            }
            if (triggerSync && autoSyncEnabled) {
                scheduleAutoSync()
            }
            return
        }

        // 先推进本地 epoch，避免同步回写在文件已更新但版本未变化的窗口覆盖本地修改
        if (markLocalMutation) {
            syncMutationStore.markSyncMutation()
        }
        val currentPrimaryText = storage.readPrimary()
        val serialized = if (stateChanged || currentPrimaryText == null) {
            gson.toJson(normalized)
        } else {
            currentPrimaryText
        }
        val pendingOutbox = preparePendingSyncMutationUpdate(
            currentPrimaryText = currentPrimaryText,
            nextPrimaryDigest = primaryDigest(serialized),
            syncMutation = syncMutation
        )
        writePendingSyncMutation(pendingOutbox)
        if (stateChanged || currentPrimaryText == null) {
            persistToDisk(normalized, serialized)
        }
        if (stateChanged) {
            _playlists.value = normalized
            _playlistCount.value = normalized.size
        }
        if (pendingOutbox != null) {
            val settled = runCatching {
                settlePendingSyncMutation(pendingOutbox, triggerSync)
            }.onFailure { error ->
                _syncMutationPending.value = true
                NPLogger.e(
                    "LocalPlaylistRepo",
                    "Playlist saved; sync mutation will be retried",
                    error
                )
            }.isSuccess
            if (!settled) return
        } else if (triggerSync && autoSyncEnabled) {
            scheduleAutoSync()
        }
    }

    private fun recoverPendingSyncMutation(committedPrimaryText: String?) {
        runCatching {
            flushPendingSyncMutation(committedPrimaryText)
        }.onFailure { error ->
            NPLogger.e("LocalPlaylistRepo", "Failed to replay playlist sync mutation", error)
        }
    }

    private fun flushPendingSyncMutation(primaryText: String? = storage.readPrimary()): Boolean {
        val pendingText = storage.readPendingSyncMutation() ?: return false
        val committedOutbox = decodeCommittedSyncMutationOutbox(pendingText, primaryText)
        if (committedOutbox == null) {
            storage.clearPendingSyncMutation()
            _syncMutationPending.value = false
            return false
        }
        if (gson.toJson(committedOutbox) != pendingText) {
            storage.writePendingSyncMutation(gson.toJson(committedOutbox))
        }
        return settlePendingSyncMutation(committedOutbox, triggerSync = false)
    }

    private fun preparePendingSyncMutationUpdate(
        currentPrimaryText: String?,
        nextPrimaryDigest: String,
        syncMutation: LocalPlaylistSyncMutation
    ): LocalPlaylistSyncMutationOutbox? {
        val committedMutations = storage.readPendingSyncMutation()
            ?.let { decodeCommittedSyncMutationOutbox(it, currentPrimaryText) }
            ?.mutations
            .orEmpty()
        if (committedMutations.isEmpty() && syncMutation.isEmpty) {
            return null
        }

        val nextMutation = syncMutation.withExpectedPrimaryDigest(nextPrimaryDigest)
        return LocalPlaylistSyncMutationOutbox(committedMutations + nextMutation)
    }

    private fun decodeCommittedSyncMutationOutbox(
        text: String,
        primaryText: String?
    ): LocalPlaylistSyncMutationOutbox? {
        val outbox = runCatching {
            val root = JSONObject(text)
            if (root.has("mutations")) {
                requireNotNull(gson.fromJson(text, LocalPlaylistSyncMutationOutbox::class.java))
            } else {
                LocalPlaylistSyncMutationOutbox(
                    mutations = listOf(
                        requireNotNull(gson.fromJson(text, LocalPlaylistSyncMutation::class.java))
                    )
                )
            }
        }.getOrElse { error ->
            NPLogger.e("LocalPlaylistRepo", "Discarding corrupt playlist sync mutation", error)
            return null
        }
        if (outbox.mutations.isEmpty() || primaryText == null) return null

        val committedDigest = primaryDigest(primaryText)
        val committedIndex = outbox.mutations.indexOfLast { mutation ->
            mutation.expectedPrimaryDigest == committedDigest
        }
        if (committedIndex < 0) return null
        return LocalPlaylistSyncMutationOutbox(
            mutations = outbox.mutations.take(committedIndex + 1)
        )
    }

    private fun writePendingSyncMutation(outbox: LocalPlaylistSyncMutationOutbox?) {
        if (outbox == null) {
            storage.clearPendingSyncMutation()
        } else {
            storage.writePendingSyncMutation(gson.toJson(outbox))
        }
    }

    private fun settlePendingSyncMutation(
        outbox: LocalPlaylistSyncMutationOutbox,
        triggerSync: Boolean
    ): Boolean {
        val hasSyncMutation = outbox.mutations.any { mutation -> !mutation.isEmpty }
        try {
            outbox.mutations.forEach { mutation ->
                if (!mutation.isEmpty) {
                    syncMutationStore.apply(mutation)
                }
            }
            if ((triggerSync || hasSyncMutation) && autoSyncEnabled && !scheduleAutoSync()) {
                throw IOException("Failed to schedule playlist sync mutation")
            }
            storage.clearPendingSyncMutation()
            _syncMutationPending.value = false
            return hasSyncMutation
        } catch (error: Exception) {
            _syncMutationPending.value = true
            throw IOException("Playlist saved but sync mutation is pending", error)
        }
    }

    private fun primaryDigest(text: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun scheduleAutoSync(): Boolean {
        return try {
            val autoSyncTrigger = providedAutoSyncTrigger
            if (autoSyncTrigger != null) {
                autoSyncTrigger()
            } else {
                if (!syncStorage.isAutoSyncEnabled()) {
                    NPLogger.d("LocalPlaylistRepo", "Auto sync disabled, skip")
                }
                GitHubSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false)
                WebDavSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false)
            }
            true
        } catch (e: Exception) {
            NPLogger.e("LocalPlaylistRepo", "Failed to schedule sync", e)
            false
        }
    }

    private fun sanitizePlaylistName(name: String, excludedPlaylistId: Long? = null): String {
        val defaultName = context.getString(R.string.playlist_create)
        // 限制歌单名长度，保证重名处理时也不会超出最大字数
        val base = name.trim().ifBlank { defaultName }.take(MAX_PLAYLIST_NAME_LENGTH)
        val occupiedNames = _playlists.value
            .asSequence()
            .filter { playlist -> excludedPlaylistId == null || playlist.id != excludedPlaylistId }
            .map { it.name.lowercase() }
            .toSet()

        var candidate = base
        var index = 2
        while (
            SystemLocalPlaylists.matchesReservedName(candidate, context) ||
            candidate.lowercase() in occupiedNames
        ) {
            val suffix = "_$index"
            val allowed = (MAX_PLAYLIST_NAME_LENGTH - suffix.length).coerceAtLeast(0)
            candidate = (base.take(allowed) + suffix).take(MAX_PLAYLIST_NAME_LENGTH)
            index++
        }
        return candidate
    }

    private fun songSet(songs: List<SongItem>): Set<SongIdentity> = songs.map { it.identity() }.toSet()

    private fun stampSongsForPlaylistInsert(songs: List<SongItem>, addedAt: Long): List<SongItem> {
        if (songs.isEmpty()) return emptyList()

        val membershipTokens = syncMutationStore.nextSyncCausalTokens(songs.size)
        check(membershipTokens.size == songs.size) {
            "Expected ${songs.size} sync membership tokens, got ${membershipTokens.size}"
        }
        return songs.mapIndexed { index, song ->
            song.copy(
                addedAt = (addedAt - index).coerceAtLeast(1L),
                syncMembershipTokens = listOf(membershipTokens[index])
            )
        }
    }

    private fun nextPlaylistSongAddedAt(playlist: LocalPlaylist, now: Long): Long {
        val latestExistingAddedAt = playlist.songs.maxOfOrNull { it.addedAt } ?: 0L
        return maxOf(now, latestExistingAddedAt + 1L)
    }

    private fun stampSongsForDisplayOrder(
        songs: List<SongItem>,
        newestAt: Long
    ): MutableList<SongItem> {
        return songs.mapIndexedTo(mutableListOf()) { index, song ->
            song.copy(addedAt = (newestAt - index).coerceAtLeast(1L))
        }
    }

    private fun mergeNewSongsFirst(
        existingSongs: List<SongItem>,
        newSongs: List<SongItem>
    ): MutableList<SongItem> {
        return (newSongs + existingSongs).toMutableList()
    }

    private fun buildPlaylistSongDeletionMutation(
        playlistId: Long,
        songs: List<SongItem>,
        deletedAt: Long
    ): LocalPlaylistSyncMutation {
        val deletions = buildPlaylistSongDeletions(playlistId, songs, deletedAt)
        return LocalPlaylistSyncMutation(addedSongDeletions = deletions)
    }

    private fun buildPlaylistSongDeletionRemoval(
        playlistId: Long,
        songs: List<SongItem>
    ): LocalPlaylistSyncMutation {
        val remoteIdentities = songs
            .asSequence()
            .filterNot { LocalSongSupport.isLocalSong(it, context) }
            .map { it.identity() }
            .toList()
        if (remoteIdentities.isEmpty()) return LocalPlaylistSyncMutation()
        return LocalPlaylistSyncMutation(
            removedSongDeletions = listOf(
                PlaylistSongDeletionRemoval(
                    playlistId = playlistId,
                    identities = remoteIdentities
                )
            )
        )
    }

    private fun buildPlaylistDeletionMutation(playlistId: Long): LocalPlaylistSyncMutation {
        return LocalPlaylistSyncMutation(
            deletedPlaylistIds = listOf(playlistId),
            clearedPlaylistDeletionIds = listOf(playlistId)
        )
    }

    private fun buildPlaylistSongDeletions(
        playlistId: Long,
        songs: List<SongItem>,
        deletedAt: Long
    ): List<SyncPlaylistSongDeletion> {
        if (songs.isEmpty() || isLocalFilesPlaylist(playlistId)) {
            return emptyList()
        }

        val deviceId = syncMutationStore.getOrCreateDeviceId()
        return songs
            .asSequence()
            .filterNot { LocalSongSupport.isLocalSong(it, context) }
            .map { song ->
                val identity = song.identity()
                SyncPlaylistSongDeletion(
                    playlistId = playlistId,
                    songId = identity.id,
                    album = identity.album,
                    mediaUri = LocalSongSupport.sanitizeMediaUriForSync(identity.mediaUri),
                    deletedAt = deletedAt,
                    deviceId = deviceId,
                    removedMembershipTokens = song.syncMembershipTokens.orEmpty()
                )
            }
            .toList()
    }

    private suspend fun hydrateLocalSongsForPersistence(
        songs: List<SongItem>,
        hydrateLocalMetadata: Boolean = true
    ): List<SongItem> {
        if (!hydrateLocalMetadata) {
            return songs
        }
        if (songs.none { LocalSongSupport.isLocalSong(it, context) }) {
            return songs
        }

        return coroutineScope {
            val hydrateDispatcher = Dispatchers.IO.limitedParallelism(4)
            songs.map { song ->
                async(hydrateDispatcher) {
                    LocalAudioImportManager.hydrateLocalSongMetadata(context, song)
                }
            }.awaitAll()
        }
    }

    private fun hasExistingSong(
        existingSongs: List<SongItem>,
        candidate: SongItem,
        includeLocalMetadataFallback: Boolean = false
    ): Boolean {
        return existingSongs.any { existing ->
            existing.sameIdentityAs(candidate) ||
                LocalSongSupport.hasSameLocalSource(
                    first = existing,
                    second = candidate,
                    includeMetadataFallback = includeLocalMetadataFallback
                )
        }
    }

    private fun distinctPlaylistSongs(
        songs: List<SongItem>,
        includeLocalMetadataFallback: Boolean = false
    ): MutableList<SongItem> {
        val duplicateIndex = SongDuplicateIndex(includeLocalMetadataFallback)
        val distinct = mutableListOf<SongItem>()
        songs.forEach { song ->
            if (duplicateIndex.contains(song)) return@forEach
            duplicateIndex.add(song)
            distinct += song
        }
        return distinct
    }

    private fun filterNewSongs(
        existingSongs: List<SongItem>,
        candidates: List<SongItem>,
        includeLocalMetadataFallback: Boolean = false
    ): List<SongItem> {
        val accepted = SongDuplicateIndex(includeLocalMetadataFallback).apply {
            existingSongs.forEach(::add)
        }
        return candidates.filter { candidate ->
            if (accepted.contains(candidate)) {
                false
            } else {
                accepted.add(candidate)
                true
            }
        }
    }

    private class SongDuplicateIndex(
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

    private fun nextPlaylistId(existing: List<LocalPlaylist>): Long {
        val usedIds = existing.mapTo(HashSet(existing.size)) { it.id }
        var candidate = System.currentTimeMillis()
        while (candidate in usedIds) {
            candidate++
        }
        return candidate
    }

    private fun isLocalFilesPlaylist(playlistId: Long, playlistName: String? = null): Boolean {
        return playlistId == LocalFilesPlaylist.SYSTEM_ID ||
            (playlistId < 0 && playlistName != null && LocalFilesPlaylist.matches(playlistName, context))
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
        return createPlaylistWithPreparedSongs(name, songs)
    }

    suspend fun createPlaylistWithPreparedSongs(name: String, songs: List<SongItem>): LocalPlaylist {
        return createPlaylistWithSongs(
            name = name,
            songs = songs,
            hydrateLocalMetadata = false
        )
    }

    private suspend fun createPlaylistWithSongs(
        name: String,
        songs: List<SongItem>,
        hydrateLocalMetadata: Boolean
    ): LocalPlaylist {
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val distinctSongs = distinctPlaylistSongs(
                stampSongsForPlaylistInsert(
                    songs = hydrateLocalSongsForPersistence(songs, hydrateLocalMetadata),
                    addedAt = now
                )
            )
            commitPlaylistMutation {
                val list = _playlists.value.toMutableList()
                val playlist = LocalPlaylist(
                    id = nextPlaylistId(list),
                    name = sanitizePlaylistName(name),
                    songs = distinctSongs,
                    modifiedAt = now,
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
                list.add(playlist)
                publishLocked(list)
                playlist
            }
        }
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
        withContext(Dispatchers.IO) {
            if (songs.isEmpty()) return@withContext
            val toRemove = songSet(songs)
            commitPlaylistMutation {
                var syncMutation = LocalPlaylistSyncMutation()
                val updated = _playlists.value.map { playlist ->
                    if (playlist.id != playlistId) return@map playlist
                    val removedSongs = playlist.songs.filter { it.identity() in toRemove }
                    val filtered = playlist.songs.filterNot { it.identity() in toRemove }.toMutableList()
                    if (filtered.size == playlist.songs.size) {
                        playlist
                    } else {
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
                }
                publishLocked(updated, syncMutation = syncMutation)
            }
        }
    }

    suspend fun clearPlaylistSongs(playlistId: Long) {
        withContext(Dispatchers.IO) {
            commitPlaylistMutation {
                var changed = false
                var syncMutation = LocalPlaylistSyncMutation()
                val updated = _playlists.value.map { playlist ->
                    if (playlist.id != playlistId || playlist.songs.isEmpty()) {
                        return@map playlist
                    }
                    changed = true
                    val deletedAt = System.currentTimeMillis()
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
                if (!changed) return@commitPlaylistMutation
                publishLocked(updated, syncMutation = syncMutation)
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
        return withContext(Dispatchers.IO) {
            commitPlaylistMutation {
                val playlist = _playlists.value.firstOrNull { it.id == playlistId }
                    ?: return@commitPlaylistMutation false
                if (SystemLocalPlaylists.isSystemPlaylist(playlist, context)) {
                    return@commitPlaylistMutation false
                }

                val updated = _playlists.value.filterNot { it.id == playlistId }
                publishLocked(
                    playlists = updated,
                    syncMutation = buildPlaylistDeletionMutation(playlistId)
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

    suspend fun reorderSongs(playlistId: Long, newOrder: List<SongIdentity>) {
        withContext(Dispatchers.IO) {
            commitPlaylistMutation {
                val updated = _playlists.value.map { playlist ->
                    if (playlist.id != playlistId) return@map playlist
                    val byIdentity = playlist.songs.associateBy { it.identity() }
                    val ordered = newOrder.mapNotNull { byIdentity[it] }.toMutableList()
                    playlist.songs.forEach { song ->
                        if (ordered.none { it.sameIdentityAs(song) }) {
                            ordered += song
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
            }
        }
    }

    suspend fun addSongsToPlaylist(playlistId: Long, songs: List<SongItem>) {
        addSongsToPlaylistAndCount(playlistId, songs)
    }

    suspend fun addSongsToPlaylistAndCount(playlistId: Long, songs: List<SongItem>): Int {
        return addSongsToPlaylistAndCount(
            playlistId = playlistId,
            songs = songs,
            hydrateLocalMetadata = true
        )
    }

    suspend fun addScannedSongsToPlaylistAndCount(playlistId: Long, songs: List<SongItem>): Int {
        return addSongsToPlaylistAndCount(
            playlistId = playlistId,
            songs = songs,
            hydrateLocalMetadata = false,
            includeLocalMetadataFallback = true
        )
    }

    suspend fun addPreparedSongsToPlaylist(playlistId: Long, songs: List<SongItem>) {
        addPreparedSongsToPlaylistAndCount(playlistId, songs)
    }

    suspend fun addPreparedSongsToPlaylistAndCount(playlistId: Long, songs: List<SongItem>): Int {
        return addSongsToPlaylistAndCount(
            playlistId = playlistId,
            songs = songs,
            hydrateLocalMetadata = false
        )
    }

    private suspend fun addSongsToPlaylistAndCount(
        playlistId: Long,
        songs: List<SongItem>,
        hydrateLocalMetadata: Boolean,
        includeLocalMetadataFallback: Boolean = false
    ): Int {
        return withContext(Dispatchers.IO) {
            if (songs.isEmpty()) return@withContext 0
            val now = System.currentTimeMillis()
            val hydratedSongs = hydrateLocalSongsForPersistence(songs, hydrateLocalMetadata)
            commitPlaylistMutation {
                addStampedSongsToPlaylistLocked(
                    playlistId = playlistId,
                    songs = hydratedSongs,
                    now = now,
                    includeLocalMetadataFallback = includeLocalMetadataFallback
                )
            }
        }
    }

    private fun addStampedSongsToPlaylistLocked(
        playlistId: Long,
        songs: List<SongItem>,
        now: Long,
        includeLocalMetadataFallback: Boolean = false
    ): Int {
        if (songs.isEmpty()) {
            return 0
        }

        var addedCount = 0
        var syncMutation = LocalPlaylistSyncMutation()
        val updated = _playlists.value.map { playlist ->
            if (playlist.id != playlistId) return@map playlist
            if (isLocalFilesPlaylist(playlist.id, playlist.name)) {
                return@map playlist
            }

            val newSongs = filterNewSongs(
                existingSongs = playlist.songs,
                candidates = songs,
                includeLocalMetadataFallback = includeLocalMetadataFallback
            )
            if (newSongs.isEmpty()) {
                playlist
            } else {
                val toAdd = stampSongsForPlaylistInsert(
                    songs = newSongs,
                    addedAt = nextPlaylistSongAddedAt(playlist, now)
                )
                addedCount += toAdd.size
                syncMutation += buildPlaylistSongDeletionRemoval(playlist.id, toAdd)
                playlist.copy(
                    songs = mergeNewSongsFirst(playlist.songs, toAdd),
                    modifiedAt = now,
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            }
        }
        publishLocked(updated, syncMutation = syncMutation)
        return addedCount
    }

    suspend fun syncLocalFilesPlaylist(
        songs: List<SongItem>,
        allowEmptyReplacement: Boolean = false
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val normalizedSongs = distinctPlaylistSongs(
                songs = hydrateLocalSongsForPersistence(songs),
                includeLocalMetadataFallback = true
            )
            commitPlaylistMutation {
                val currentLocalFiles = LocalFilesPlaylist.firstOrNull(_playlists.value, context)
                if (
                    normalizedSongs.isEmpty() &&
                    !allowEmptyReplacement &&
                    currentLocalFiles?.songs?.isNotEmpty() == true
                ) {
                    NPLogger.w(
                        "LocalPlaylistRepo",
                        "Skip replacing Local Files playlist with empty scan result"
                    )
                    return@commitPlaylistMutation false
                }

                val updated = _playlists.value.map { playlist ->
                    if (!isLocalFilesPlaylist(playlist.id, playlist.name)) {
                        playlist
                    } else {
                        playlist.copy(
                            songs = normalizedSongs,
                            modifiedAt = System.currentTimeMillis(),
                            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                        )
                    }
                }
                publishLocked(updated)
                true
            }
        }
    }

    suspend fun addSongsToLocalFilesPlaylist(songs: List<SongItem>) {
        addSongsToLocalFilesPlaylistAndCount(songs)
    }

    suspend fun addSongsToLocalFilesPlaylistAndCount(songs: List<SongItem>): Int {
        return addSongsToLocalFilesPlaylistAndCount(
            songs = songs,
            hydrateLocalMetadata = true
        )
    }

    suspend fun addScannedSongsToLocalFilesPlaylistAndCount(songs: List<SongItem>): Int {
        return addSongsToLocalFilesPlaylistAndCount(
            songs = songs,
            hydrateLocalMetadata = false
        )
    }

    private suspend fun addSongsToLocalFilesPlaylistAndCount(
        songs: List<SongItem>,
        hydrateLocalMetadata: Boolean
    ): Int {
        return withContext(Dispatchers.IO) {
            if (songs.isEmpty()) return@withContext 0
            val now = System.currentTimeMillis()
            val hydratedSongs = hydrateLocalSongsForPersistence(songs, hydrateLocalMetadata)
            commitPlaylistMutation {
                var addedCount = 0
                val updated = _playlists.value.map { playlist ->
                    if (!isLocalFilesPlaylist(playlist.id, playlist.name)) {
                        return@map playlist
                    }

                    val newSongs = filterNewSongs(
                        existingSongs = playlist.songs,
                        candidates = hydratedSongs,
                        includeLocalMetadataFallback = true
                    )
                    if (newSongs.isEmpty()) {
                        playlist
                    } else {
                        val toAdd = stampSongsForPlaylistInsert(
                            songs = newSongs,
                            addedAt = nextPlaylistSongAddedAt(playlist, now)
                        )
                        addedCount += toAdd.size
                        playlist.copy(
                            songs = mergeNewSongsFirst(playlist.songs, toAdd),
                            modifiedAt = now,
                            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                        )
                    }
                }
                publishLocked(updated)
                addedCount
            }
        }
    }

    suspend fun refreshScannedLocalSongMetadata(
        songs: List<SongItem>,
        includeEmbeddedAssets: Boolean = false,
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
            candidates.chunked(LOCAL_METADATA_REFRESH_BATCH_SIZE).forEach { batch ->
                val updates = coroutineScope {
                    batch.map { originalSong ->
                        async(refreshDispatcher) {
                            val hydratedSong = if (includeEmbeddedAssets) {
                                LocalAudioImportManager.hydrateLocalSongMetadata(
                                    context,
                                    originalSong
                                )
                            } else {
                                LocalAudioImportManager.hydrateLocalSongTextMetadata(context, originalSong)
                            }
                            originalSong to hydratedSong
                        }
                    }.awaitAll()
                }.filter { (originalSong, hydratedSong) ->
                    hydratedSong != originalSong
                }
                applySongMetadataUpdates(updates)
                processedCount += batch.size
                onProgress(processedCount, candidates.size)
            }
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

    suspend fun exportSongsToPlaylistById(sourcePlaylistId: Long, targetPlaylistId: Long, songIds: List<Long>) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            commitPlaylistMutation {
                val source = _playlists.value.firstOrNull { it.id == sourcePlaylistId }
                    ?: return@commitPlaylistMutation
                val inSourceOrder = source.songs.filter { it.id in songIds }
                addStampedSongsToPlaylistLocked(targetPlaylistId, inSourceOrder, now)
            }
        }
    }

    suspend fun updateSongMetadata(originalSong: SongItem, newSongInfo: SongItem) {
        withContext(Dispatchers.IO) {
            commitPlaylistMutation {
                val modifiedAt = System.currentTimeMillis()
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
                            modifiedAt = modifiedAt,
                            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                        )
                    }
                }
                if (!changed) {
                    return@commitPlaylistMutation
                }
                // 播放期自动补 metadata 只需要把本地视图写稳，不该顺手唤醒整条云同步链
                publishLocked(
                    playlists = updated,
                    triggerSync = false,
                    markLocalMutation = true
                )
            }
        }
    }

    private suspend fun applySongMetadataUpdates(updates: List<Pair<SongItem, SongItem>>) {
        if (updates.isEmpty()) {
            return
        }

        commitPlaylistMutation {
            val modifiedAt = System.currentTimeMillis()
            val updateIndex = SongMetadataUpdateIndex(updates)
            var changed = false
            val updated = _playlists.value.map { playlist ->
                var playlistChanged = false
                val refreshedSongs = playlist.songs.map { currentSong ->
                    val newSongInfo = updateIndex.find(currentSong) ?: return@map currentSong
                    val mergedSongInfo = mergeSongMetadataForPersistence(
                        currentSong = currentSong,
                        newSongInfo = newSongInfo
                    )
                    if (currentSong == mergedSongInfo) {
                        currentSong
                    } else {
                        saveCoverMapping(mergedSongInfo)
                        changed = true
                        playlistChanged = true
                        mergedSongInfo
                    }
                }.toMutableList()

                if (playlistChanged) {
                    playlist.copy(
                        songs = refreshedSongs,
                        modifiedAt = modifiedAt,
                        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                    )
                } else {
                    playlist
                }
            }

            if (changed) {
                publishLocked(
                    playlists = updated,
                    triggerSync = false,
                    markLocalMutation = true
                )
            }
        }
    }

    private class SongMetadataUpdateIndex(updates: List<Pair<SongItem, SongItem>>) {
        private val byIdentity = HashMap<SongIdentity, SongItem>(updates.size * 2)
        private val byLocalKey = HashMap<String, SongItem>(updates.size * 3)

        init {
            updates.forEach { (originalSong, hydratedSong) ->
                byIdentity[originalSong.identity()] = hydratedSong
                LocalSongSupport.localDuplicateKeys(
                    song = originalSong,
                    includeMetadataFallback = true
                ).forEach { key ->
                    byLocalKey.putIfAbsent(key, hydratedSong)
                }
            }
        }

        fun find(song: SongItem): SongItem? {
            byIdentity[song.identity()]?.let { return it }
            return LocalSongSupport.localDuplicateKeys(
                song = song,
                includeMetadataFallback = true
            ).firstNotNullOfOrNull(byLocalKey::get)
        }
    }

    suspend fun updateSongMetadata(songId: Long, albumIdentifier: String, newSongInfo: SongItem) {
        updateSongMetadata(
            originalSong = newSongInfo.copy(id = songId, album = albumIdentifier),
            newSongInfo = newSongInfo
        )
    }

    private fun mergeSongMetadataForPersistence(
        currentSong: SongItem,
        newSongInfo: SongItem
    ): SongItem {
        return newSongInfo.copy(
            addedAt = currentSong.addedAt,
            coverUrl = newSongInfo.coverUrl.takeIf { !it.isNullOrBlank() }
                ?: currentSong.coverUrl,
            originalCoverUrl = newSongInfo.originalCoverUrl.takeIf { !it.isNullOrBlank() }
                ?: currentSong.originalCoverUrl
                ?: currentSong.coverUrl,
            syncMembershipTokens = currentSong.syncMembershipTokens.normalizedSyncCausalTokens()
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
                            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                        )
                    }
                }
                if (changed) {
                    publishLocked(
                        playlists = updated,
                        triggerSync = false,
                        markLocalMutation = true
                    )
                }
            }
        }
    }

    private fun saveCoverMapping(newSongInfo: SongItem) {
        runCatching {
            val mapper = CoverUrlMapper.getInstance(context)
            if (newSongInfo.coverUrl != null && newSongInfo.originalCoverUrl != null) {
                mapper.saveCoverMapping(newSongInfo.coverUrl, newSongInfo.originalCoverUrl)
            }
            if (newSongInfo.customCoverUrl != null && newSongInfo.originalCoverUrl != null) {
                mapper.saveCoverMapping(newSongInfo.customCoverUrl, newSongInfo.originalCoverUrl)
            }
        }.onFailure {
            NPLogger.e("LocalPlaylistRepo", "Failed to save cover mapping", it)
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

    internal suspend fun applySyncedPlaylistsIfUnchanged(
        playlists: List<LocalPlaylist>,
        expectedMutationVersion: Long
    ): Boolean {
        return withContext(Dispatchers.IO) {
            commitPlaylistMutation {
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

    private fun mergeExternalPlaylists(playlists: List<LocalPlaylist>): MutableList<LocalPlaylist> {
        val preservedLocalFiles = LocalFilesPlaylist.firstOrNull(_playlists.value, context)
        return playlists
            .filterNot { LocalFilesPlaylist.isSystemPlaylist(it, context) }
            .toMutableList()
            .apply { preservedLocalFiles?.let(::add) }
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

    private fun replaceRecentNeteaseLikedIds(likedIds: Set<Long>) {
        synchronized(recentNeteaseLikedIds) {
            recentNeteaseLikedIds.clear()
            recentNeteaseLikedIds.addAll(likedIds)
        }
    }

    fun filterNeteaseLikeSyncCandidates(songs: List<SongItem>): List<SongItem> {
        return buildLocalNeteaseCandidates(songs).candidates.map { it.song }
    }

    suspend fun filterNeteaseLikeSyncCandidatesExcludingLiked(
        client: NeteaseClient,
        songs: List<SongItem>
    ): List<SongItem> {
        return prepareNeteaseLikeSyncPlan(client, songs).pendingSongs
    }

    suspend fun prepareNeteaseLikeSyncPlan(
        client: NeteaseClient,
        songs: List<SongItem>
    ): NeteaseLikeSyncPlan {
        return withContext(Dispatchers.IO) {
            if (songs.isEmpty()) {
                return@withContext NeteaseLikeSyncPlan(
                    totalSongs = 0,
                    supportedSongs = 0,
                    skippedUnsupported = 0,
                    skippedExisting = 0,
                    pendingSongs = emptyList(),
                    compareSucceeded = false,
                    message = context.getString(R.string.local_playlist_sync_netease_empty)
                )
            }

            val localSummary = buildLocalNeteaseCandidates(songs)
            if (localSummary.candidates.isEmpty()) {
                return@withContext NeteaseLikeSyncPlan(
                    totalSongs = songs.size,
                    supportedSongs = localSummary.supportedSongs,
                    skippedUnsupported = localSummary.skippedUnsupported,
                    skippedExisting = localSummary.skippedExisting,
                    pendingSongs = emptyList(),
                    compareSucceeded = false,
                    message = context.getString(R.string.local_playlist_sync_netease_no_supported)
                )
            }

            if (!client.hasLogin()) {
                return@withContext NeteaseLikeSyncPlan(
                    totalSongs = songs.size,
                    supportedSongs = localSummary.supportedSongs,
                    skippedUnsupported = localSummary.skippedUnsupported,
                    skippedExisting = localSummary.skippedExisting,
                    pendingSongs = emptyList(),
                    compareSucceeded = false,
                    message = context.getString(R.string.playback_login_required)
                )
            }

            runCatching { client.ensureWeapiSession() }.onFailure {
                NPLogger.w("LocalPlaylistRepo", "ensureWeapiSession failed: ${it.message}")
            }

            val validatedSummary = validateNeteaseSyncCandidates(client, localSummary)
            if (validatedSummary.candidates.isEmpty()) {
                return@withContext NeteaseLikeSyncPlan(
                    totalSongs = songs.size,
                    supportedSongs = validatedSummary.supportedSongs,
                    skippedUnsupported = validatedSummary.skippedUnsupported,
                    skippedExisting = validatedSummary.skippedExisting,
                    pendingSongs = emptyList(),
                    compareSucceeded = false,
                    message = context.getString(R.string.local_playlist_sync_netease_no_supported)
                )
            }

            val likedIdsResult = fetchNeteaseLikedIdsMerged(client)
            if (!likedIdsResult.compareSucceeded) {
                return@withContext NeteaseLikeSyncPlan(
                    totalSongs = songs.size,
                    supportedSongs = validatedSummary.supportedSongs,
                    skippedUnsupported = validatedSummary.skippedUnsupported,
                    skippedExisting = validatedSummary.skippedExisting,
                    pendingSongs = emptyList(),
                    compareSucceeded = false,
                    message = likedIdsResult.message ?: NETEASE_COMPARE_FAILED_MESSAGE
                )
            }

            var skippedExisting = validatedSummary.skippedExisting
            val pendingSongs = ArrayList<SongItem>(validatedSummary.candidates.size)
            for (candidate in validatedSummary.candidates) {
                if (candidate.neteaseId in likedIdsResult.likedIds ||
                    candidate.song.toNeteaseFingerprint() in likedIdsResult.likedFingerprints
                ) {
                    skippedExisting += 1
                    continue
                }
                pendingSongs += candidate.song
            }

            val message = if (pendingSongs.isEmpty()) {
                context.getString(R.string.local_playlist_sync_netease_all_synced)
            } else {
                null
            }

            NeteaseLikeSyncPlan(
                totalSongs = songs.size,
                supportedSongs = validatedSummary.supportedSongs,
                skippedUnsupported = validatedSummary.skippedUnsupported,
                skippedExisting = skippedExisting,
                pendingSongs = pendingSongs,
                compareSucceeded = true,
                message = message
            )
        }
    }

    suspend fun syncFavoritesToNeteaseLiked(client: NeteaseClient): NeteaseLikeSyncResult {
        val favorites = FavoritesPlaylist.firstOrNull(_playlists.value, context)
        return syncSongsToNeteaseLiked(client, favorites?.songs.orEmpty())
    }

    suspend fun syncSongsToNeteaseLiked(
        client: NeteaseClient,
        songs: List<SongItem>
    ): NeteaseLikeSyncResult {
        return withContext(Dispatchers.IO) {
            val plan = prepareNeteaseLikeSyncPlan(client, songs)
            if (songs.isEmpty()) {
                return@withContext NeteaseLikeSyncResult(
                    totalSongs = 0,
                    supportedSongs = 0,
                    skippedUnsupported = 0,
                    skippedExisting = 0,
                    added = 0,
                    failed = 0,
                    message = plan.message
                )
            }

            if (!plan.compareSucceeded) {
                return@withContext NeteaseLikeSyncResult(
                    totalSongs = songs.size,
                    supportedSongs = plan.supportedSongs,
                    skippedUnsupported = plan.skippedUnsupported,
                    skippedExisting = plan.skippedExisting,
                    added = 0,
                    failed = 0,
                    message = plan.message
                )
            }

            var skippedUnsupported = plan.skippedUnsupported
            var skippedExisting = plan.skippedExisting
            var added = 0
            var failed = 0
            val candidates = buildLocalNeteaseCandidates(plan.pendingSongs).candidates

            if (candidates.isEmpty()) {
                return@withContext NeteaseLikeSyncResult(
                    totalSongs = songs.size,
                    supportedSongs = plan.supportedSongs,
                    skippedUnsupported = skippedUnsupported,
                    skippedExisting = skippedExisting,
                    added = 0,
                    failed = 0,
                    message = plan.message
                )
            }

            val likedIdsResult = fetchNeteaseLikedIdsMerged(client)
            if (!likedIdsResult.compareSucceeded) {
                return@withContext NeteaseLikeSyncResult(
                    totalSongs = songs.size,
                    supportedSongs = plan.supportedSongs,
                    skippedUnsupported = skippedUnsupported,
                    skippedExisting = skippedExisting,
                    added = 0,
                    failed = 0,
                    message = likedIdsResult.message ?: NETEASE_COMPARE_FAILED_MESSAGE
                )
            }
            val likedIds = likedIdsResult.likedIds.toMutableSet()

            for (candidate in candidates) {
                val neteaseId = candidate.neteaseId
                if (neteaseId in likedIds ||
                    candidate.song.toNeteaseFingerprint() in likedIdsResult.likedFingerprints
                ) {
                    skippedExisting += 1
                    continue
                }

                val liked = runCatching { client.likeSong(neteaseId, like = true) }
                    .getOrElse { error ->
                        NPLogger.e("LocalPlaylistRepo", "likeSong failed: ${error.message}", error)
                        ""
                    }
                val likeCode = parseNeteaseCode(liked)
                if (likeCode == 200) {
                    added += 1
                    likedIds.add(neteaseId)
                    recentNeteaseLikedIds.add(neteaseId)
                } else if (likeCode == 301 && client.hasLogin()) {
                    runCatching { client.ensureWeapiSession() }.onFailure {
                        NPLogger.w("LocalPlaylistRepo", "ensureWeapiSession retry failed: ${it.message}")
                    }
                    val retry = runCatching { client.likeSong(neteaseId, like = true) }
                        .getOrElse { error ->
                            NPLogger.e("LocalPlaylistRepo", "likeSong retry failed: ${error.message}", error)
                            ""
                        }
                    val retryCode = parseNeteaseCode(retry)
                    if (retryCode == 200) {
                        added += 1
                        likedIds.add(neteaseId)
                        recentNeteaseLikedIds.add(neteaseId)
                    } else if (retryCode == 400 && !isNeteaseSongIdStillResolvable(client, neteaseId)) {
                        NPLogger.w(
                            "LocalPlaylistRepo",
                            "Filtered invalid songId after retry code=400: songId=$neteaseId name=${candidate.song.name}"
                        )
                        skippedUnsupported += 1
                    } else {
                        NPLogger.w(
                            "LocalPlaylistRepo",
                            "likeSong retry returned code=$retryCode for songId=$neteaseId name=${candidate.song.name}"
                        )
                        if (isSongAlreadyLikedByCloud(client, candidate)) {
                            skippedExisting += 1
                        } else {
                            failed += 1
                        }
                    }
                } else {
                    NPLogger.w(
                        "LocalPlaylistRepo",
                        "likeSong returned code=$likeCode for songId=$neteaseId name=${candidate.song.name}"
                    )
                    if (likeCode == 400 && !isNeteaseSongIdStillResolvable(client, neteaseId)) {
                        NPLogger.w(
                            "LocalPlaylistRepo",
                            "Filtered invalid songId after code=400: songId=$neteaseId name=${candidate.song.name}"
                        )
                        skippedUnsupported += 1
                    } else if (isSongAlreadyLikedByCloud(client, candidate)) {
                        skippedExisting += 1
                    } else {
                        failed += 1
                    }
                }
            }

            NeteaseLikeSyncResult(
                totalSongs = songs.size,
                supportedSongs = plan.supportedSongs,
                skippedUnsupported = skippedUnsupported,
                skippedExisting = skippedExisting,
                added = added,
                failed = failed,
                message = plan.message
            )
        }
    }

    private fun resolveNeteaseSongId(song: SongItem): Long? {
        val songId = song.id.takeIf { it > 0 } ?: return null
        if (song.album.startsWith(NETEASE_ALBUM_PREFIX)) {
            return songId
        }
        if (song.matchedLyricSource == MusicPlatform.CLOUD_MUSIC) {
            val matched = song.matchedSongId?.toLongOrNull()
            if (matched != null && matched > 0) return matched
        }
        if (song.coverUrl.isNeteaseCoverUrl() || song.originalCoverUrl.isNeteaseCoverUrl()) {
            return songId
        }
        return null
    }

    private fun buildLocalNeteaseCandidates(songs: List<SongItem>): LocalNeteaseCandidateSummary {
        if (songs.isEmpty()) {
            return LocalNeteaseCandidateSummary(
                supportedSongs = 0,
                skippedUnsupported = 0,
                skippedExisting = 0,
                candidates = emptyList()
            )
        }

        var supportedSongs = 0
        var skippedUnsupported = 0
        var skippedExisting = 0
        val seenNeteaseIds = mutableSetOf<Long>()
        val candidates = ArrayList<NeteaseResolvedCandidate>(songs.size)
        for (song in songs) {
            val neteaseId = resolveNeteaseSongId(song)
            if (neteaseId == null) {
                skippedUnsupported += 1
                continue
            }
            if (!seenNeteaseIds.add(neteaseId)) {
                // 同一首网易云歌曲只保留最早出现的那条，保证顺序稳定
                skippedExisting += 1
                continue
            }
            supportedSongs += 1
            candidates += NeteaseResolvedCandidate(song = song, neteaseId = neteaseId)
        }
        return LocalNeteaseCandidateSummary(
            supportedSongs = supportedSongs,
            skippedUnsupported = skippedUnsupported,
            skippedExisting = skippedExisting,
            candidates = candidates
        )
    }

    private fun validateNeteaseSyncCandidates(
        client: NeteaseClient,
        summary: LocalNeteaseCandidateSummary
    ): NeteaseCandidateValidationResult {
        if (summary.candidates.isEmpty()) {
            return NeteaseCandidateValidationResult(
                supportedSongs = 0,
                skippedUnsupported = summary.skippedUnsupported,
                skippedExisting = summary.skippedExisting,
                candidates = emptyList()
            )
        }

        val validatedCandidates = ArrayList<NeteaseResolvedCandidate>(summary.candidates.size)
        var skippedUnsupported = summary.skippedUnsupported
        summary.candidates.chunked(300).forEachIndexed { pageIndex, chunk ->
            val resolvedIds = fetchResolvableNeteaseSongIds(
                client = client,
                ids = chunk.map(NeteaseResolvedCandidate::neteaseId),
                logLabel = "validateNeteaseSyncCandidates page ${pageIndex + 1}"
            )
            if (resolvedIds == null) {
                validatedCandidates.addAll(chunk)
                return@forEachIndexed
            }

            chunk.forEach { candidate ->
                if (candidate.neteaseId in resolvedIds) {
                    validatedCandidates += candidate
                } else {
                    skippedUnsupported += 1
                    NPLogger.w(
                        "LocalPlaylistRepo",
                        "Filtered invalid netease songId before sync: songId=${candidate.neteaseId} name=${candidate.song.name}"
                    )
                }
            }
        }

        return NeteaseCandidateValidationResult(
            supportedSongs = validatedCandidates.size,
            skippedUnsupported = skippedUnsupported,
            skippedExisting = summary.skippedExisting,
            candidates = validatedCandidates
        )
    }

    private suspend fun fetchNeteaseLikedIdsMerged(client: NeteaseClient): NeteaseLikedIdsFetchResult {
        val likedIds = mutableSetOf<Long>()
        val likedFingerprints = mutableSetOf<String>()
        var compareSucceeded = false

        val direct = fetchNeteaseLikedIdsDirect(client)
        if (direct.compareSucceeded) {
            compareSucceeded = true
            likedIds.addAll(direct.likedIds)
        }
        likedFingerprints.addAll(direct.likedFingerprints)

        val fallback = fetchNeteaseLikedIdsFallback(client)
        if (fallback.compareSucceeded) {
            compareSucceeded = true
            likedIds.addAll(fallback.likedIds)
        }
        likedFingerprints.addAll(fallback.likedFingerprints)

        if (!compareSucceeded) {
            return NeteaseLikedIdsFetchResult(
                likedIds = emptySet(),
                compareSucceeded = false,
                message = NETEASE_COMPARE_FAILED_MESSAGE
            )
        }

        replaceRecentNeteaseLikedIds(likedIds)

        return NeteaseLikedIdsFetchResult(
            likedIds = likedIds,
            likedFingerprints = likedFingerprints,
            compareSucceeded = true
        )
    }

    private suspend fun fetchNeteaseLikedIdsDirect(client: NeteaseClient): NeteaseLikedIdsFetchResult = withContext(Dispatchers.IO) {
        val likedRaw = runCatching { client.getUserLikedSongIds(0) }
            .getOrElse { error ->
                NPLogger.e("LocalPlaylistRepo", "getUserLikedSongIds failed: ${error.message}", error)
                ""
            }

        if (parseNeteaseCode(likedRaw) == 301) {
            runCatching { client.ensureWeapiSession() }.onFailure {
                NPLogger.w("LocalPlaylistRepo", "ensureWeapiSession retry failed: ${it.message}")
            }
            val retriedRaw = runCatching { client.getUserLikedSongIds(0) }
                .getOrElse { error ->
                    NPLogger.e("LocalPlaylistRepo", "getUserLikedSongIds retry failed: ${error.message}", error)
                    ""
                }
            return@withContext parseNeteaseLikedIdsFetchResult(retriedRaw)
        }

        parseNeteaseLikedIdsFetchResult(likedRaw)
    }

    private fun parseNeteaseLikedIdsFetchResult(raw: String): NeteaseLikedIdsFetchResult {
        val parsed = parseNeteaseLikedSongIds(raw)
        return NeteaseLikedIdsFetchResult(
            likedIds = parsed.ids,
            compareSucceeded = parsed.success
        )
    }

    private fun parseNeteaseLikedSongIds(raw: String): ParsedNeteaseIds {
        if (raw.isBlank()) return ParsedNeteaseIds(ids = emptySet(), success = false)
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("code", -1) != 200) {
                return@runCatching ParsedNeteaseIds(ids = emptySet(), success = false)
            }
            val idsArray = root.optJSONArray("ids")
                ?: root.optJSONObject("data")?.optJSONArray("ids")
                ?: root.optJSONArray("data")
            val ids = mutableSetOf<Long>()
            if (idsArray != null) {
                for (i in 0 until idsArray.length()) {
                    val id = idsArray.optLong(i)
                    if (id > 0L) ids.add(id)
                }
            }
            ParsedNeteaseIds(ids = ids, success = true)
        }.getOrElse { error ->
            NPLogger.e("LocalPlaylistRepo", "Failed to parse liked ids: ${error.message}", error)
            ParsedNeteaseIds(ids = emptySet(), success = false)
        }
    }

    private suspend fun fetchNeteaseLikedIdsFallback(client: NeteaseClient): NeteaseLikedIdsFetchResult = withContext(Dispatchers.IO) {
        val likedPlaylistRaw = runCatching { client.getLikedPlaylistId(0) }
            .getOrElse { error ->
                NPLogger.e("LocalPlaylistRepo", "getLikedPlaylistId failed: ${error.message}", error)
                return@withContext NeteaseLikedIdsFetchResult(emptySet(), compareSucceeded = false)
            }
        val likedPlaylist = parseNeteaseLikedPlaylistId(likedPlaylistRaw)
        if (!likedPlaylist.success || likedPlaylist.playlistId == null) {
            return@withContext NeteaseLikedIdsFetchResult(emptySet(), compareSucceeded = false)
        }

        val playlistRaw = runCatching { client.getPlaylistDetail(likedPlaylist.playlistId) }
            .getOrElse { error ->
                NPLogger.e("LocalPlaylistRepo", "getPlaylistDetail failed: ${error.message}", error)
                return@withContext NeteaseLikedIdsFetchResult(emptySet(), compareSucceeded = false)
            }

        val parsed = parseNeteaseTrackIdsFromPlaylistDetail(playlistRaw)
        if (!parsed.success) {
            return@withContext NeteaseLikedIdsFetchResult(
                likedIds = emptySet(),
                compareSucceeded = false
            )
        }
        if (parsed.trackIds.isEmpty() && parsed.trackCount > 0) {
            NPLogger.w(
                "LocalPlaylistRepo",
                "Liked playlist detail returned empty trackIds but trackCount=${parsed.trackCount}"
            )
            return@withContext NeteaseLikedIdsFetchResult(
                likedIds = emptySet(),
                compareSucceeded = false
            )
        }

        val likedIds = LinkedHashSet<Long>(parsed.trackIds.size)
        likedIds.addAll(parsed.trackIds)
        val likedFingerprints = mutableSetOf<String>()

        if (parsed.trackCount > parsed.trackIds.size) {
            NPLogger.w(
                "LocalPlaylistRepo",
                "Liked playlist trackIds incomplete: parsed=${parsed.trackIds.size}, expected=${parsed.trackCount}"
            )
        }
        if (parsed.trackIds.isNotEmpty()) {
            val detailSummary = fetchNeteaseLikedSongDetailSummaryByPages(client, parsed.trackIds)
            likedIds.addAll(detailSummary.ids)
            likedFingerprints.addAll(detailSummary.fingerprints)
        }

        NeteaseLikedIdsFetchResult(
            likedIds = likedIds,
            likedFingerprints = likedFingerprints,
            compareSucceeded = true
        )
    }

    private fun parseNeteaseLikedPlaylistId(raw: String): ParsedNeteasePlaylistId {
        if (raw.isBlank()) return ParsedNeteasePlaylistId(playlistId = null, success = false)
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("code", -1) != 200) {
                return@runCatching ParsedNeteasePlaylistId(playlistId = null, success = false)
            }
            val id = root.optLong("playlistId", 0L)
            ParsedNeteasePlaylistId(
                playlistId = id.takeIf { it > 0L },
                success = true
            )
        }.getOrElse { error ->
            NPLogger.e("LocalPlaylistRepo", "Failed to parse liked playlist id: ${error.message}", error)
            ParsedNeteasePlaylistId(playlistId = null, success = false)
        }
    }

    private fun parseNeteaseTrackIdsFromPlaylistDetail(raw: String): ParsedNeteasePlaylistTrackIds {
        if (raw.isBlank()) {
            return ParsedNeteasePlaylistTrackIds(
                trackIds = emptyList(),
                trackCount = 0,
                success = false
            )
        }
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("code", -1) != 200) {
                return@runCatching ParsedNeteasePlaylistTrackIds(
                    trackIds = emptyList(),
                    trackCount = 0,
                    success = false
                )
            }
            val playlist = root.optJSONObject("playlist")
            val trackIdsArr = playlist?.optJSONArray("trackIds")
            val ids = LinkedHashSet<Long>()
            if (trackIdsArr != null) {
                for (i in 0 until trackIdsArr.length()) {
                    val id = trackIdsArr.optJSONObject(i)?.optLong("id", 0L) ?: 0L
                    if (id > 0L) {
                        ids.add(id)
                    }
                }
            }
            ParsedNeteasePlaylistTrackIds(
                trackIds = ids.toList(),
                trackCount = playlist?.optInt("trackCount", ids.size) ?: ids.size,
                success = true
            )
        }.getOrElse { error ->
            NPLogger.e("LocalPlaylistRepo", "Failed to parse track ids: ${error.message}", error)
            ParsedNeteasePlaylistTrackIds(
                trackIds = emptyList(),
                trackCount = 0,
                success = false
            )
        }
    }

    private data class NeteaseSongDetailSummary(
        val ids: Set<Long>,
        val fingerprints: Set<String>
    )

    private fun fetchNeteaseLikedSongDetailSummaryByPages(
        client: NeteaseClient,
        trackIds: List<Long>
    ): NeteaseSongDetailSummary {
        if (trackIds.isEmpty()) {
            return NeteaseSongDetailSummary(
                ids = emptySet(),
                fingerprints = emptySet()
            )
        }

        val resolvedIds = LinkedHashSet<Long>(trackIds.size)
        val fingerprints = mutableSetOf<String>()
        trackIds.chunked(300).forEachIndexed { pageIndex, ids ->
            val raw = runCatching { client.getSongDetail(ids) }
                .getOrElse { error ->
                    NPLogger.e(
                        "LocalPlaylistRepo",
                        "getSongDetail page ${pageIndex + 1} failed: ${error.message}",
                        error
                    )
                    return@forEachIndexed
                }
            val parsed = parseNeteaseSongDetailSummary(raw)
            if (!parsed.success) {
                NPLogger.w(
                    "LocalPlaylistRepo",
                    "getSongDetail page ${pageIndex + 1} returned invalid payload"
                )
                return@forEachIndexed
            }
            resolvedIds.addAll(parsed.ids)
            fingerprints.addAll(parsed.fingerprints)
        }
        return NeteaseSongDetailSummary(
            ids = resolvedIds,
            fingerprints = fingerprints
        )
    }

    private data class ParsedNeteaseSongDetailSummary(
        val ids: Set<Long>,
        val fingerprints: Set<String>,
        val success: Boolean
    )

    private fun parseNeteaseSongDetailSummary(raw: String): ParsedNeteaseSongDetailSummary {
        if (raw.isBlank()) {
            return ParsedNeteaseSongDetailSummary(
                ids = emptySet(),
                fingerprints = emptySet(),
                success = false
            )
        }
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("code", -1) != 200) {
                return@runCatching ParsedNeteaseSongDetailSummary(
                    ids = emptySet(),
                    fingerprints = emptySet(),
                    success = false
                )
            }
            val songs = root.optJSONArray("songs")
            val ids = LinkedHashSet<Long>()
            val fingerprints = mutableSetOf<String>()
            if (songs != null) {
                for (i in 0 until songs.length()) {
                    val song = songs.optJSONObject(i) ?: continue
                    val id = song.optLong("id", 0L)
                    if (id > 0L) {
                        ids.add(id)
                    }
                    buildNeteaseFingerprint(
                        name = song.optString("name", ""),
                        artist = parseNeteaseSongArtist(song),
                        durationMs = song.optLong("dt", 0L)
                    )?.let(fingerprints::add)
                }
            }
            ParsedNeteaseSongDetailSummary(
                ids = ids,
                fingerprints = fingerprints,
                success = true
            )
        }.getOrElse { error ->
            NPLogger.e("LocalPlaylistRepo", "Failed to parse song detail ids: ${error.message}", error)
            ParsedNeteaseSongDetailSummary(
                ids = emptySet(),
                fingerprints = emptySet(),
                success = false
            )
        }
    }

    private fun fetchResolvableNeteaseSongIds(
        client: NeteaseClient,
        ids: List<Long>,
        logLabel: String
    ): Set<Long>? {
        if (ids.isEmpty()) return emptySet()

        fun requestSongDetail(): String {
            return client.getSongDetail(ids)
        }

        val raw = runCatching { requestSongDetail() }
            .getOrElse { error ->
                NPLogger.e("LocalPlaylistRepo", "$logLabel failed: ${error.message}", error)
                return null
            }

        val retriedRaw = if (parseNeteaseCode(raw) == 301 && client.hasLogin()) {
            runCatching { client.ensureWeapiSession() }.onFailure {
                NPLogger.w("LocalPlaylistRepo", "$logLabel ensureWeapiSession retry failed: ${it.message}")
            }
            runCatching { requestSongDetail() }
                .getOrElse { error ->
                    NPLogger.e("LocalPlaylistRepo", "$logLabel retry failed: ${error.message}", error)
                    return null
                }
        } else {
            raw
        }

        val parsed = parseNeteaseSongDetailSummary(retriedRaw)
        if (!parsed.success) {
            NPLogger.w("LocalPlaylistRepo", "$logLabel returned invalid payload")
            return null
        }
        return parsed.ids
    }

    private fun parseNeteaseCode(raw: String): Int {
        if (raw.isBlank()) return -1
        return runCatching { JSONObject(raw).optInt("code", -1) }.getOrElse { -1 }
    }

    private suspend fun isSongAlreadyLikedByCloud(
        client: NeteaseClient,
        candidate: NeteaseResolvedCandidate
    ): Boolean {
        val refreshed = fetchNeteaseLikedIdsMerged(client)
        if (!refreshed.compareSucceeded) return false
        return candidate.neteaseId in refreshed.likedIds ||
            candidate.song.toNeteaseFingerprint() in refreshed.likedFingerprints
    }

    private fun isNeteaseSongIdStillResolvable(
        client: NeteaseClient,
        songId: Long
    ): Boolean {
        val resolvedIds = fetchResolvableNeteaseSongIds(
            client = client,
            ids = listOf(songId),
            logLabel = "isNeteaseSongIdStillResolvable"
        ) ?: return true
        return songId in resolvedIds
    }

    private fun SongItem.toNeteaseFingerprint(): String? {
        return buildNeteaseFingerprint(
            name = originalName ?: customName ?: name,
            artist = originalArtist ?: customArtist ?: artist,
            durationMs = durationMs
        )
    }

    private fun buildNeteaseFingerprint(
        name: String?,
        artist: String?,
        durationMs: Long
    ): String? {
        val normalizedName = normalizeFingerprintToken(name)
        val normalizedArtist = normalizeArtistToken(artist)
        if (normalizedName.isBlank() || normalizedArtist.isBlank()) return null
        val durationBucket = if (durationMs > 0L) ((durationMs + 2_500L) / 5_000L).toString() else "0"
        return "$normalizedName|$normalizedArtist|$durationBucket"
    }

    private fun parseNeteaseSongArtist(song: JSONObject): String {
        val artists = song.optJSONArray("ar") ?: return ""
        val names = ArrayList<String>(artists.length())
        for (i in 0 until artists.length()) {
            val name = artists.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
            if (name.isNotBlank()) {
                names += name
            }
        }
        return names.joinToString(" / ")
    }

    private fun normalizeArtistToken(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.splitToSequence("/", "&", " feat. ", " feat ", ",", "，", "、")
            .map(::normalizeFingerprintToken)
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString("|")
    }

    private fun normalizeFingerprintToken(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val lowered = raw.lowercase(Locale.ROOT)
        val builder = StringBuilder(lowered.length)
        lowered.forEach { ch ->
            if (Character.isLetterOrDigit(ch)) {
                builder.append(ch)
            }
        }
        return builder.toString()
    }

    private fun String?.isNeteaseCoverUrl(): Boolean {
        if (this.isNullOrBlank()) return false
        return contains("music.126.net", ignoreCase = true)
    }

    companion object {
        const val MAX_PLAYLIST_NAME_LENGTH = 10
        private const val LOCAL_METADATA_REFRESH_BATCH_SIZE = 48
        private const val LOCAL_METADATA_REFRESH_PARALLELISM = 4
        private const val NETEASE_ALBUM_PREFIX = "Netease"
        private const val NETEASE_COMPARE_FAILED_MESSAGE =
            "网易云云端比对失败，已停止同步以避免误同步"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: LocalPlaylistRepository? = null

        fun getInstance(context: Context): LocalPlaylistRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocalPlaylistRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        internal fun createForTest(
            context: Context,
            file: File,
            normalizePlaylists: (List<LocalPlaylist>) -> List<LocalPlaylist> = { it },
            autoSyncEnabled: Boolean = false,
            storage: LocalPlaylistStorage = LocalPlaylistFileStorage(file, context.filesDir),
            syncMutationStore: LocalPlaylistSyncMutationStore? = null,
            autoSyncTrigger: (() -> Unit)? = null
        ): LocalPlaylistRepository {
            return LocalPlaylistRepository(
                context = context,
                file = file,
                normalizePlaylists = normalizePlaylists,
                autoSyncEnabled = autoSyncEnabled,
                storage = storage,
                providedSyncMutationStore =
                    syncMutationStore ?: InMemoryLocalPlaylistSyncMutationStore(),
                providedAutoSyncTrigger = autoSyncTrigger
            )
        }
    }
}
