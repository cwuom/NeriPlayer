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
import moe.ouom.neriplayer.data.github.CoverUrlMapper
import moe.ouom.neriplayer.data.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.github.SecureTokenStorage
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import java.io.File

data class LocalPlaylist(
    val id: Long,
    val name: String,
    val songs: MutableList<SongItem> = mutableListOf(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val customCoverUrl: String? = null
)

class LocalPlaylistRepository private constructor(private val context: Context) {
    private val gson = Gson()
    private val file = File(context.filesDir, "local_playlists.json")

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

        _playlists.value = FavoritesPlaylist.normalize(loaded, context)
        saveToDisk(triggerSync = false)
    }

    private fun saveToDisk(triggerSync: Boolean = true) {
        runCatching {
            val json = gson.toJson(FavoritesPlaylist.normalize(_playlists.value, context))
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
        _playlists.value = FavoritesPlaylist.normalize(playlists, context)
        saveToDisk(triggerSync)
    }

    private fun triggerAutoSync() {
        try {
            val storage = SecureTokenStorage(context)
            if (!storage.isAutoSyncEnabled()) {
                NPLogger.d("LocalPlaylistRepo", "Auto sync disabled, skip")
                return
            }
            GitHubSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false)
        } catch (e: Exception) {
            NPLogger.e("LocalPlaylistRepo", "Failed to schedule sync", e)
        }
    }

    private fun sanitizePlaylistName(name: String): String {
        val trimmed = name.trim().ifBlank { context.getString(R.string.playlist_create) }
        return if (FavoritesPlaylist.matches(trimmed, context)) {
            "${trimmed}_2"
        } else {
            trimmed
        }
    }

    private fun songSet(songs: List<SongItem>): Set<SongIdentity> = songs.map { it.identity() }.toSet()

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
                if (playlist.id != playlistId || FavoritesPlaylist.isSystemPlaylist(playlist, context)) {
                    playlist
                } else {
                    playlist.copy(
                        name = sanitizePlaylistName(newName),
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
            if (FavoritesPlaylist.isSystemPlaylist(playlist, context)) return@withContext false

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
            publish(playlists, triggerSync = false)
        }
    }

    companion object {
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
