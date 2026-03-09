package moe.ouom.neriplayer.data

import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import java.io.File

data class FavoritePlaylist(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val source: String,
    val songs: List<SongItem>,
    val addedTime: Long = System.currentTimeMillis()
)

class FavoritePlaylistRepository private constructor(private val context: Context) {
    private val gson = Gson()
    private val file = File(context.filesDir, "favorite_playlists.json")

    private val _favorites = MutableStateFlow<List<FavoritePlaylist>>(emptyList())
    val favorites: StateFlow<List<FavoritePlaylist>> = _favorites

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val list = try {
            if (!file.exists()) {
                emptyList()
            } else {
                val type = object : TypeToken<List<FavoritePlaylist>>() {}.type
                gson.fromJson<List<FavoritePlaylist>>(file.readText(), type).orEmpty()
            }
        } catch (_: Exception) {
            emptyList()
        }
        _favorites.value = list.sortedByDescending { it.addedTime }
    }

    private fun saveToDisk() {
        runCatching {
            val json = gson.toJson(_favorites.value)
            val parent = file.parentFile ?: context.filesDir
            val tmp = File(parent, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        }
    }

    suspend fun addFavorite(
        id: Long,
        name: String,
        coverUrl: String?,
        trackCount: Int,
        source: String,
        songs: List<SongItem>
    ) {
        withContext(Dispatchers.IO) {
            val list = _favorites.value.toMutableList()
            val existingIndex = list.indexOfFirst { it.id == id && it.source == source }
            val existing = list.getOrNull(existingIndex)

            val mergedSongs = buildList {
                addAll(existing?.songs.orEmpty())
                addAll(songs)
            }.distinctBy { it.identity() }

            val merged = FavoritePlaylist(
                id = id,
                name = name,
                coverUrl = coverUrl ?: existing?.coverUrl,
                trackCount = maxOf(trackCount, existing?.trackCount ?: 0, mergedSongs.size),
                source = source,
                songs = if (mergedSongs.isNotEmpty()) mergedSongs else existing?.songs.orEmpty(),
                addedTime = System.currentTimeMillis()
            )

            if (existingIndex >= 0) {
                list[existingIndex] = merged
            } else {
                list += merged
            }

            _favorites.value = list.sortedByDescending { it.addedTime }
            saveToDisk()
        }
    }

    suspend fun removeFavorite(id: Long, source: String) {
        withContext(Dispatchers.IO) {
            _favorites.value = _favorites.value.filterNot { it.id == id && it.source == source }
            saveToDisk()
        }
    }

    fun isFavorite(id: Long, source: String): Boolean {
        return _favorites.value.any { it.id == id && it.source == source }
    }

    fun getFavorite(id: Long, source: String): FavoritePlaylist? {
        return _favorites.value.firstOrNull { it.id == id && it.source == source }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: FavoritePlaylistRepository? = null

        fun getInstance(context: Context): FavoritePlaylistRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FavoritePlaylistRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
