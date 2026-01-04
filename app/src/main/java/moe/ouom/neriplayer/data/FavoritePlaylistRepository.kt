package moe.ouom.neriplayer.data

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
 */

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

/** 收藏的歌单数据模型 */
data class FavoritePlaylist(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val source: String, // "netease" | "bilibili"
    val songs: List<SongItem>,
    val addedTime: Long = System.currentTimeMillis()
)

/**
 * 管理收藏的歌单
 * 缓存歌单封面、名称和歌曲列表（收藏时的状态）
 */
class FavoritePlaylistRepository private constructor(private val context: Context) {
    private val gson = Gson()
    private val file: File = File(context.filesDir, "favorite_playlists.json")

    private val _favorites = MutableStateFlow<List<FavoritePlaylist>>(emptyList())
    val favorites: StateFlow<List<FavoritePlaylist>> = _favorites

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val list = try {
            if (file.exists()) {
                val type = object : TypeToken<List<FavoritePlaylist>>() {}.type
                gson.fromJson<List<FavoritePlaylist>>(file.readText(), type) ?: emptyList()
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        _favorites.value = list.sortedByDescending { it.addedTime }
    }

    private fun saveToDisk() {
        runCatching {
            val json = gson.toJson(_favorites.value)
            val parent = file.parentFile ?: context.filesDir
            val tmp = File(parent, file.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        }
    }

    /** 添加歌单到收藏 */
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
            // 如果已存在，先移除旧的
            list.removeAll { it.id == id && it.source == source }
            // 添加新的
            list.add(
                FavoritePlaylist(
                    id = id,
                    name = name,
                    coverUrl = coverUrl,
                    trackCount = trackCount,
                    source = source,
                    songs = songs,
                    addedTime = System.currentTimeMillis()
                )
            )
            _favorites.value = list.sortedByDescending { it.addedTime }
            saveToDisk()
        }
    }

    /** 从收藏中移除歌单 */
    suspend fun removeFavorite(id: Long, source: String) {
        withContext(Dispatchers.IO) {
            val list = _favorites.value.toMutableList()
            list.removeAll { it.id == id && it.source == source }
            _favorites.value = list
            saveToDisk()
        }
    }

    /** 检查歌单是否已收藏 */
    fun isFavorite(id: Long, source: String): Boolean {
        return _favorites.value.any { it.id == id && it.source == source }
    }

    /** 获取收藏的歌单 */
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
