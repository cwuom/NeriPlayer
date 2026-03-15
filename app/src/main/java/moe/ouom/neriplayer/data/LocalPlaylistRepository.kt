package moe.ouom.neriplayer.data

import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.github.CoverUrlMapper
import moe.ouom.neriplayer.data.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.github.SecureTokenStorage
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.Locale

data class LocalPlaylist(
    val id: Long,
    val name: String,
    val songs: MutableList<SongItem> = mutableListOf(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val customCoverUrl: String? = null
)

data class NeteaseLikeSyncResult(
    val totalSongs: Int,
    val supportedSongs: Int,
    val skippedUnsupported: Int,
    val skippedExisting: Int,
    val added: Int,
    val failed: Int,
    val message: String? = null
)

data class NeteaseLikeSyncPlan(
    val totalSongs: Int,
    val supportedSongs: Int,
    val skippedUnsupported: Int,
    val skippedExisting: Int,
    val pendingSongs: List<SongItem>,
    val compareSucceeded: Boolean,
    val message: String? = null
)

class LocalPlaylistRepository private constructor(private val context: Context) {
    private val gson = Gson()
    private val file = File(context.filesDir, "local_playlists.json")
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

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val loaded = try {
            if (!file.exists()) {
                emptyList()
            } else {
                val type = object : TypeToken<List<LocalPlaylist>>() {}.type
                gson.fromJson<List<LocalPlaylist>>(file.readText(), type).orEmpty()
            }
        } catch (e: Exception) {
            NPLogger.e("LocalPlaylistRepo", "Failed to read playlists", e)
            emptyList()
        }

        _playlists.value = SystemLocalPlaylists.normalize(loaded, context)
        saveToDisk(triggerSync = false)
    }

    private fun saveToDisk(triggerSync: Boolean = true) {
        runCatching {
            val json = gson.toJson(SystemLocalPlaylists.normalize(_playlists.value, context))
            val parent = file.parentFile ?: context.filesDir
            val tmp = File(parent, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        }.onFailure {
            NPLogger.e("LocalPlaylistRepo", "Failed to write playlists", it)
        }

        if (triggerSync) {
            triggerAutoSync()
        }
    }

    private fun publish(playlists: List<LocalPlaylist>, triggerSync: Boolean = true) {
        _playlists.value = SystemLocalPlaylists.normalize(playlists, context)
        saveToDisk(triggerSync)
    }

    private fun triggerAutoSync() {
        try {
            val storage = SecureTokenStorage(context)
            storage.markSyncMutation()
            if (!storage.isAutoSyncEnabled()) {
                NPLogger.d("LocalPlaylistRepo", "Auto sync disabled, skip")
                return
            }
            GitHubSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false)
        } catch (e: Exception) {
            NPLogger.e("LocalPlaylistRepo", "Failed to schedule sync", e)
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

    private fun isLocalFilesPlaylist(playlistId: Long, playlistName: String? = null): Boolean {
        return playlistId == LocalFilesPlaylist.SYSTEM_ID ||
            (playlistId < 0 && playlistName != null && LocalFilesPlaylist.matches(playlistName, context))
    }

    suspend fun createPlaylist(name: String) {
        withContext(Dispatchers.IO) {
            val list = _playlists.value.toMutableList()
            list.add(
                LocalPlaylist(
                    id = System.currentTimeMillis(),
                    name = sanitizePlaylistName(name),
                    modifiedAt = System.currentTimeMillis()
                )
            )
            publish(list)
        }
    }

    suspend fun addToFavorites(song: SongItem) {
        withContext(Dispatchers.IO) {
            val list = _playlists.value.toMutableList()
            val index = list.indexOfFirst { FavoritesPlaylist.isSystemPlaylist(it, context) }
            if (index == -1) return@withContext

            val favorites = list[index]
            if (favorites.songs.any { it.sameIdentityAs(song) }) return@withContext

            list[index] = favorites.copy(
                songs = (favorites.songs + song).toMutableList(),
                modifiedAt = System.currentTimeMillis()
            )
            publish(list)
        }
    }

    suspend fun removeFromFavorites(song: SongItem) {
        withContext(Dispatchers.IO) {
            val list = _playlists.value.toMutableList()
            val index = list.indexOfFirst { FavoritesPlaylist.isSystemPlaylist(it, context) }
            if (index == -1) return@withContext

            val favorites = list[index]
            val updatedSongs = favorites.songs.filterNot { it.sameIdentityAs(song) }.toMutableList()
            if (updatedSongs.size == favorites.songs.size) return@withContext

            list[index] = favorites.copy(
                songs = updatedSongs,
                modifiedAt = System.currentTimeMillis()
            )
            publish(list)
        }
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String) {
        withContext(Dispatchers.IO) {
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
            publish(updated)
        }
    }

    suspend fun removeSongsFromPlaylistByIdentity(playlistId: Long, songs: List<SongItem>) {
        withContext(Dispatchers.IO) {
            if (songs.isEmpty()) return@withContext
            val toRemove = songSet(songs)
            val updated = _playlists.value.map { playlist ->
                if (playlist.id != playlistId) return@map playlist
                val filtered = playlist.songs.filterNot { it.identity() in toRemove }.toMutableList()
                if (filtered.size == playlist.songs.size) {
                    playlist
                } else {
                    playlist.copy(songs = filtered, modifiedAt = System.currentTimeMillis())
                }
            }
            publish(updated)
        }
    }

    suspend fun removeSongsFromPlaylistById(playlistId: Long, songIds: List<Long>) {
        withContext(Dispatchers.IO) {
            if (songIds.isEmpty()) return@withContext
            val updated = _playlists.value.map { playlist ->
                if (playlist.id != playlistId) return@map playlist
                val filtered = playlist.songs.filterNot { it.id in songIds }.toMutableList()
                if (filtered.size == playlist.songs.size) {
                    playlist
                } else {
                    playlist.copy(songs = filtered, modifiedAt = System.currentTimeMillis())
                }
            }
            publish(updated)
        }
    }

    suspend fun deletePlaylist(playlistId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return@withContext false
            if (SystemLocalPlaylists.isSystemPlaylist(playlist, context)) return@withContext false

            val updated = _playlists.value.filterNot { it.id == playlistId }
            runCatching {
                SecureTokenStorage(context).addDeletedPlaylistId(playlistId)
            }.onFailure {
                NPLogger.e("LocalPlaylistRepo", "Failed to track deleted playlist", it)
            }
            publish(updated)
            true
        }
    }

    suspend fun moveSong(playlistId: Long, fromIndex: Int, toIndex: Int) {
        withContext(Dispatchers.IO) {
            val updated = _playlists.value.map { playlist ->
                if (playlist.id != playlistId) return@map playlist
                if (fromIndex !in playlist.songs.indices || toIndex !in playlist.songs.indices) return@map playlist

                val songs = playlist.songs.toMutableList().apply {
                    val song = removeAt(fromIndex)
                    add(toIndex, song)
                }
                playlist.copy(songs = songs, modifiedAt = System.currentTimeMillis())
            }
            publish(updated)
        }
    }

    suspend fun reorderSongs(playlistId: Long, newOrder: List<SongIdentity>) {
        withContext(Dispatchers.IO) {
            val updated = _playlists.value.map { playlist ->
                if (playlist.id != playlistId) return@map playlist
                val byIdentity = playlist.songs.associateBy { it.identity() }
                val ordered = newOrder.mapNotNull { byIdentity[it] }.toMutableList()
                playlist.songs.forEach { song ->
                    if (ordered.none { it.sameIdentityAs(song) }) {
                        ordered += song
                    }
                }
                playlist.copy(songs = ordered, modifiedAt = System.currentTimeMillis())
            }
            publish(updated)
        }
    }

    suspend fun addSongsToPlaylist(playlistId: Long, songs: List<SongItem>) {
        withContext(Dispatchers.IO) {
            if (songs.isEmpty()) return@withContext
            val updated = _playlists.value.map { playlist ->
                if (playlist.id != playlistId) return@map playlist
                if (isLocalFilesPlaylist(playlist.id, playlist.name)) {
                    return@map playlist
                }

                val existing = songSet(playlist.songs).toMutableSet()
                val toAdd = songs.filter { existing.add(it.identity()) }
                if (toAdd.isEmpty()) {
                    playlist
                } else {
                    playlist.copy(
                        songs = (playlist.songs + toAdd).toMutableList(),
                        modifiedAt = System.currentTimeMillis()
                    )
                }
            }
            publish(updated)
        }
    }

    suspend fun syncLocalFilesPlaylist(
        songs: List<SongItem>,
        allowEmptyReplacement: Boolean = false
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val normalizedSongs = songs
                .distinctBy { it.identity() }
                .toMutableList()
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
                return@withContext false
            }

            val updated = _playlists.value.map { playlist ->
                if (!isLocalFilesPlaylist(playlist.id, playlist.name)) {
                    playlist
                } else {
                    playlist.copy(
                        songs = normalizedSongs,
                        modifiedAt = System.currentTimeMillis()
                    )
                }
            }
            publish(updated)
            true
        }
    }

    suspend fun addSongsToLocalFilesPlaylist(songs: List<SongItem>) {
        withContext(Dispatchers.IO) {
            if (songs.isEmpty()) return@withContext
            val updated = _playlists.value.map { playlist ->
                if (!isLocalFilesPlaylist(playlist.id, playlist.name)) {
                    return@map playlist
                }

                val existing = songSet(playlist.songs).toMutableSet()
                val toAdd = songs.filter { existing.add(it.identity()) }
                if (toAdd.isEmpty()) {
                    playlist
                } else {
                    playlist.copy(
                        songs = (playlist.songs + toAdd).toMutableList(),
                        modifiedAt = System.currentTimeMillis()
                    )
                }
            }
            publish(updated)
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
            val source = _playlists.value.firstOrNull { it.id == sourcePlaylistId } ?: return@withContext
            val wanted = songSet(songs)
            val inSourceOrder = source.songs.filter { it.identity() in wanted }
            addSongsToPlaylist(targetPlaylistId, inSourceOrder)
        }
    }

    suspend fun exportSongsToPlaylistById(sourcePlaylistId: Long, targetPlaylistId: Long, songIds: List<Long>) {
        withContext(Dispatchers.IO) {
            val source = _playlists.value.firstOrNull { it.id == sourcePlaylistId } ?: return@withContext
            val inSourceOrder = source.songs.filter { it.id in songIds }
            addSongsToPlaylist(targetPlaylistId, inSourceOrder)
        }
    }

    suspend fun updateSongMetadata(originalSong: SongItem, newSongInfo: SongItem) {
        withContext(Dispatchers.IO) {
            saveCoverMapping(newSongInfo)
            val updated = _playlists.value.map { playlist ->
                val songIndex = playlist.songs.indexOfFirst { it.sameIdentityAs(originalSong) }
                if (songIndex == -1) {
                    playlist
                } else {
                    val songs = playlist.songs.toMutableList()
                    songs[songIndex] = newSongInfo
                    playlist.copy(songs = songs, modifiedAt = System.currentTimeMillis())
                }
            }
            publish(updated)
        }
    }

    suspend fun updateSongMetadata(songId: Long, albumIdentifier: String, newSongInfo: SongItem) {
        updateSongMetadata(
            originalSong = newSongInfo.copy(id = songId, album = albumIdentifier),
            newSongInfo = newSongInfo
        )
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

    suspend fun updatePlaylists(playlists: List<LocalPlaylist>) {
        withContext(Dispatchers.IO) {
            val preservedLocalFiles = LocalFilesPlaylist.firstOrNull(_playlists.value, context)
            val merged = playlists
                .filterNot { LocalFilesPlaylist.isSystemPlaylist(it, context) }
                .toMutableList()
            preservedLocalFiles?.let(merged::add)
            publish(merged, triggerSync = false)
        }
    }

    suspend fun reorderPlaylists(newOrder: List<Long>) {
        withContext(Dispatchers.IO) {
            val current = _playlists.value
            val system = current.filter { SystemLocalPlaylists.isSystemPlaylist(it, context) }
            val others = current.filterNot { SystemLocalPlaylists.isSystemPlaylist(it, context) }
            if (others.size <= 1) return@withContext

            val byId = others.associateBy { it.id }
            val ordered = newOrder.mapNotNull { byId[it] }.toMutableList()
            others.forEach { playlist ->
                if (ordered.none { it.id == playlist.id }) ordered += playlist
            }
            publish(ordered + system)
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
                // 同一首网易云歌曲只保留最早出现的那条，保证顺序稳定。
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

    private suspend fun validateNeteaseSyncCandidates(
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

        if (recentNeteaseLikedIds.isNotEmpty()) {
            likedIds.addAll(recentNeteaseLikedIds)
        }

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
        if (!parsed.success || parsed.trackIds.isEmpty()) {
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
        val detailSummary = fetchNeteaseLikedSongDetailSummaryByPages(client, parsed.trackIds)
        likedIds.addAll(detailSummary.ids)
        likedFingerprints.addAll(detailSummary.fingerprints)

        NeteaseLikedIdsFetchResult(
            likedIds = likedIds,
            likedFingerprints = likedFingerprints,
            compareSucceeded = likedIds.isNotEmpty()
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

    private suspend fun fetchNeteaseLikedSongDetailSummaryByPages(
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

    private suspend fun fetchResolvableNeteaseSongIds(
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

    private suspend fun isNeteaseSongIdStillResolvable(
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
        return raw.split("/", "&", " feat. ", " feat ", ",", "，", "、")
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
    }
}
