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

class LocalPlaylistRepository private constructor(private val context: Context) {
    private val gson = Gson()
    private val file = File(context.filesDir, "local_playlists.json")
    private val recentNeteaseLikedIds = Collections.synchronizedSet(mutableSetOf<Long>())

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
        if (songs.isEmpty()) return emptyList()
        val seen = mutableSetOf<SongIdentity>()
        val result = ArrayList<SongItem>(songs.size)
        for (song in songs) {
            if (resolveNeteaseSongId(song) == null) continue
            if (seen.add(song.identity())) {
                result.add(song)
            }
        }
        return result
    }

    suspend fun filterNeteaseLikeSyncCandidatesExcludingLiked(
        client: NeteaseClient,
        songs: List<SongItem>
    ): List<SongItem> {
        if (songs.isEmpty()) return emptyList()
        val candidates = filterNeteaseLikeSyncCandidates(songs)
        if (candidates.isEmpty()) return emptyList()
        if (!client.hasLogin()) return candidates

        runCatching { client.ensureWeapiSession() }.onFailure {
            NPLogger.w("LocalPlaylistRepo", "ensureWeapiSession failed: ${it.message}")
        }

        val likedRaw = runCatching { client.getUserLikedSongIds(0) }
            .getOrElse { error ->
                NPLogger.e("LocalPlaylistRepo", "getUserLikedSongIds failed: ${error.message}", error)
                ""
            }
        val likedCode = parseNeteaseCode(likedRaw)
        val likedIds = if (likedCode == 200) {
            parseNeteaseLikedSongIds(likedRaw).toMutableSet()
        } else {
            mutableSetOf()
        }
        if (likedIds.isEmpty() && likedCode == 200) {
            val fallback = fetchNeteaseLikedIdsFallback(client)
            if (fallback.isNotEmpty()) {
                likedIds.addAll(fallback)
            }
        }
        if (recentNeteaseLikedIds.isNotEmpty()) {
            likedIds.addAll(recentNeteaseLikedIds)
        }
        if (likedIds.isEmpty()) return candidates

        val seenNeteaseIds = mutableSetOf<Long>()
        val filtered = ArrayList<SongItem>(candidates.size)
        for (song in candidates) {
            val neteaseId = resolveNeteaseSongId(song) ?: continue
            if (neteaseId in likedIds) continue
            if (seenNeteaseIds.add(neteaseId)) {
                filtered.add(song)
            }
        }
        return filtered
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
            if (songs.isEmpty()) {
                return@withContext NeteaseLikeSyncResult(
                    totalSongs = 0,
                    supportedSongs = 0,
                    skippedUnsupported = 0,
                    skippedExisting = 0,
                    added = 0,
                    failed = 0
                )
            }

            if (!client.hasLogin()) {
                return@withContext NeteaseLikeSyncResult(
                    totalSongs = songs.size,
                    supportedSongs = 0,
                    skippedUnsupported = songs.size,
                    skippedExisting = 0,
                    added = 0,
                    failed = 0
                )
            }

            runCatching { client.ensureWeapiSession() }.onFailure {
                NPLogger.w("LocalPlaylistRepo", "ensureWeapiSession failed: ${it.message}")
            }

            var supported = 0
            var skippedUnsupported = 0
            var skippedExisting = 0
            var added = 0
            var failed = 0
            val seenLocalIds = mutableSetOf<Long>()
            val candidates = mutableListOf<Long>()

            for (song in songs) {
                val neteaseId = resolveNeteaseSongId(song)
                if (neteaseId == null) {
                    skippedUnsupported += 1
                    continue
                }
                if (!seenLocalIds.add(neteaseId)) {
                    skippedExisting += 1
                    continue
                }
                supported += 1
                candidates += neteaseId
            }

            if (candidates.isEmpty()) {
                return@withContext NeteaseLikeSyncResult(
                    totalSongs = songs.size,
                    supportedSongs = supported,
                    skippedUnsupported = skippedUnsupported,
                    skippedExisting = skippedExisting,
                    added = 0,
                    failed = 0
                )
            }

            val likedRaw = runCatching { client.getUserLikedSongIds(0) }
                .getOrElse { error ->
                    NPLogger.e("LocalPlaylistRepo", "getUserLikedSongIds failed: ${error.message}", error)
                    ""
                }
            val likedCode = parseNeteaseCode(likedRaw)
            if (likedCode != 200) {
                return@withContext NeteaseLikeSyncResult(
                    totalSongs = songs.size,
                    supportedSongs = supported,
                    skippedUnsupported = skippedUnsupported,
                    skippedExisting = skippedExisting,
                    added = 0,
                    failed = candidates.size
                )
            }
            val likedIds = parseNeteaseLikedSongIds(likedRaw).toMutableSet()
            if (likedIds.isEmpty()) {
                val fallback = fetchNeteaseLikedIdsFallback(client)
                if (fallback.isNotEmpty()) {
                    likedIds.addAll(fallback)
                }
            }
            if (recentNeteaseLikedIds.isNotEmpty()) {
                likedIds.addAll(recentNeteaseLikedIds)
            }

            for (neteaseId in candidates) {
                if (likedIds.contains(neteaseId)) {
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
                    if (parseNeteaseCode(retry) == 200) {
                        added += 1
                        likedIds.add(neteaseId)
                        recentNeteaseLikedIds.add(neteaseId)
                    } else {
                        failed += 1
                    }
                } else {
                    failed += 1
                }
            }

            NeteaseLikeSyncResult(
                totalSongs = songs.size,
                supportedSongs = supported,
                skippedUnsupported = skippedUnsupported,
                skippedExisting = skippedExisting,
                added = added,
                failed = failed
            )
        }
    }

    private fun resolveNeteaseSongId(song: SongItem): Long? {
        if (song.isLocalSong()) return null
        if (song.album.startsWith(NETEASE_ALBUM_PREFIX)) {
            return song.id.takeIf { it > 0 }
        }
        if (song.matchedLyricSource == MusicPlatform.CLOUD_MUSIC) {
            val matched = song.matchedSongId?.toLongOrNull()
            if (matched != null && matched > 0) return matched
        }
        return null
    }

    private fun parseNeteaseLikedSongIds(raw: String): Set<Long> {
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("code", -1) != 200) return@runCatching emptySet<Long>()
            val idsArray = root.optJSONArray("ids")
                ?: root.optJSONObject("data")?.optJSONArray("ids")
                ?: root.optJSONArray("data")
            if (idsArray == null) return@runCatching emptySet<Long>()
            val ids = mutableSetOf<Long>()
            for (i in 0 until idsArray.length()) {
                ids.add(idsArray.optLong(i))
            }
            ids
        }.getOrElse { error ->
            NPLogger.e("LocalPlaylistRepo", "Failed to parse liked ids: ${error.message}", error)
            emptySet()
        }
    }

    private fun fetchNeteaseLikedIdsFallback(client: NeteaseClient): Set<Long> {
        val likedPlaylistRaw = runCatching { client.getLikedPlaylistId(0) }
            .getOrElse { error ->
                NPLogger.e("LocalPlaylistRepo", "getLikedPlaylistId failed: ${error.message}", error)
                return emptySet()
            }
        val likedPlaylistId = parseNeteaseLikedPlaylistId(likedPlaylistRaw) ?: return emptySet()

        val playlistRaw = runCatching { client.getPlaylistDetail(likedPlaylistId) }
            .getOrElse { error ->
                NPLogger.e("LocalPlaylistRepo", "getPlaylistDetail failed: ${error.message}", error)
                return emptySet()
            }

        return parseNeteaseTrackIdsFromPlaylistDetail(playlistRaw)
    }

    private fun parseNeteaseLikedPlaylistId(raw: String): Long? {
        if (raw.isBlank()) return null
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("code", -1) != 200) return@runCatching null
            val id = root.optLong("playlistId", 0L)
            id.takeIf { it > 0 }
        }.getOrElse { error ->
            NPLogger.e("LocalPlaylistRepo", "Failed to parse liked playlist id: ${error.message}", error)
            null
        }
    }

    private fun parseNeteaseTrackIdsFromPlaylistDetail(raw: String): Set<Long> {
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("code", -1) != 200) return@runCatching emptySet<Long>()
            val trackIdsArr = root.optJSONObject("playlist")?.optJSONArray("trackIds") ?: return@runCatching emptySet<Long>()
            val ids = mutableSetOf<Long>()
            for (i in 0 until trackIdsArr.length()) {
                val id = trackIdsArr.optJSONObject(i)?.optLong("id", 0L) ?: 0L
                if (id > 0) ids.add(id)
            }
            ids
        }.getOrElse { error ->
            NPLogger.e("LocalPlaylistRepo", "Failed to parse track ids: ${error.message}", error)
            emptySet()
        }
    }

    private fun parseNeteaseCode(raw: String): Int {
        if (raw.isBlank()) return -1
        return runCatching { JSONObject(raw).optInt("code", -1) }.getOrElse { -1 }
    }

    companion object {
        const val MAX_PLAYLIST_NAME_LENGTH = 10
        private const val NETEASE_ALBUM_PREFIX = "Netease"

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
