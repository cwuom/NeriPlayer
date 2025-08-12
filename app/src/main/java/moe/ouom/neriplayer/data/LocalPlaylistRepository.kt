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
 *
 * File: moe.ouom.neriplayer.data/LocalPlaylistRepository
 * Created: 2025/8/11
 */

import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.ui.viewmodel.SongItem
import java.io.File

/** 本地歌单数据模型 */
data class LocalPlaylist(
    val id: Long,
    val name: String,
    val songs: MutableList<SongItem> = mutableListOf()
)

/**
 * 管理本地歌单与收藏的简单仓库。
 * 所有数据持久化到应用 filesDir 下的 JSON 文件中
 */
class LocalPlaylistRepository private constructor(private val context: Context) {
    private val gson = Gson()
    private val file: File = File(context.filesDir, "local_playlists.json")

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val list = try {
            if (file.exists()) {
                val type = object : TypeToken<List<LocalPlaylist>>() {}.type
                gson.fromJson<List<LocalPlaylist>>(file.readText(), type) ?: emptyList()
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }.toMutableList()

        if (list.none { it.name == "我喜欢的音乐" }) {
            list.add(0, LocalPlaylist(id = System.currentTimeMillis(), name = "我喜欢的音乐"))
        }
        _playlists.value = list
        saveToDisk()
    }

    private fun saveToDisk() {
        try {
            file.writeText(gson.toJson(_playlists.value))
        } catch (_: Exception) {
        }
    }

    /** 创建一个新的本地歌单 */
    suspend fun createPlaylist(name: String) {
        withContext(Dispatchers.IO) {
            val list = _playlists.value.toMutableList()
            list.add(LocalPlaylist(id = System.currentTimeMillis(), name = name))
            _playlists.value = list
            saveToDisk()
        }
    }

    /** 将歌曲添加到“我喜欢的音乐” */
    suspend fun addToFavorites(song: SongItem) {
        val fav = _playlists.value.firstOrNull { it.name == "我喜欢的音乐" } ?: return
        addSongToPlaylist(fav.id, song)
    }

    /** 从“我喜欢的音乐”移除歌曲 */
    suspend fun removeFromFavorites(songId: Long) {
        withContext(Dispatchers.IO) {
            val updated = _playlists.value.map { pl ->
                if (pl.name == "我喜欢的音乐")
                    pl.copy(songs = pl.songs.filter { it.id != songId }.toMutableList())
                else pl
            }
            _playlists.value = updated
            saveToDisk()
        }
    }

    /** 将歌曲添加到指定歌单 */
    suspend fun addSongToPlaylist(playlistId: Long, song: SongItem) {
        withContext(Dispatchers.IO) {
            val old = _playlists.value
            val updated = old.map { pl ->
                if (pl.id == playlistId) {
                    if (pl.songs.any { it.id == song.id }) pl
                    else pl.copy(songs = (pl.songs + song).toMutableList())
                } else pl
            }
            _playlists.value = updated
            saveToDisk()
        }
    }
    /** 从指定歌单移除歌曲 */
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        withContext(Dispatchers.IO) {
            val list = _playlists.value.toMutableList()
            val pl = list.find { it.id == playlistId } ?: return@withContext
            val removed = pl.songs.removeAll { it.id == songId }
            if (removed) {
                _playlists.value = list
                saveToDisk()
            }
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
