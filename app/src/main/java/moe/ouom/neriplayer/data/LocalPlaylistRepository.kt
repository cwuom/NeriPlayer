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
import moe.ouom.neriplayer.data.github.GitHubSyncWorker
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.R
import java.io.File

/** 本地歌单数据模型 */
data class LocalPlaylist(
    val id: Long,
    val name: String,
    val songs: MutableList<SongItem> = mutableListOf(),
    val modifiedAt: Long = System.currentTimeMillis()
)

/**
 * 管理本地歌单与收藏的仓库
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

        val favoritesName = context.getString(R.string.favorite_my_music)

        // 语言迁移：检查是否存在其他语言版本的"我喜欢的音乐"歌单
        val possibleFavoriteNames = listOf("我喜欢的音乐", "My Favorite Music")
        val existingFavorite = list.firstOrNull { it.name in possibleFavoriteNames }

        if (existingFavorite != null && existingFavorite.name != favoritesName) {
            // 找到了旧语言的收藏歌单，重命名为当前语言
            val index = list.indexOf(existingFavorite)
            list[index] = existingFavorite.copy(
                name = favoritesName,
                modifiedAt = System.currentTimeMillis()
            )
            NPLogger.i("LocalPlaylistRepo", "Migrated favorites playlist from '${existingFavorite.name}' to '$favoritesName'")
        } else if (existingFavorite == null) {
            // 不存在收藏歌单，创建一个新的
            list.add(0, LocalPlaylist(
                id = System.currentTimeMillis(),
                name = favoritesName,
                modifiedAt = System.currentTimeMillis()
            ))
        }

        _playlists.value = list
        saveToDisk(triggerSync = false)  // 加载时不触发同步
    }

    // 原子写
    private fun saveToDisk(triggerSync: Boolean = true) {
        runCatching {
            val json = gson.toJson(_playlists.value)
            val parent = file.parentFile ?: context.filesDir
            val tmp = File(parent, file.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        }
        // 触发自动同步
        if (triggerSync) {
            triggerAutoSync()
        }
    }

    /** 触发自动同步（延迟5秒） */
    private fun triggerAutoSync() {
        try {
            // 检查是否启用自动同步
            val storage = moe.ouom.neriplayer.data.github.SecureTokenStorage(context)
            if (!storage.isAutoSyncEnabled()) {
                NPLogger.d("LocalPlaylistRepo", "Auto sync is disabled, skipping sync")
                return
            }

            GitHubSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false)
            NPLogger.d("LocalPlaylistRepo", "Sync scheduled after playlist change")
        } catch (e: Exception) {
            NPLogger.e("LocalPlaylistRepo", "Failed to trigger sync", e)
        }
    }

    /** 创建一个新的本地歌单 */
    suspend fun createPlaylist(name: String) {
        withContext(Dispatchers.IO) {
            val list = _playlists.value.toMutableList()
            list.add(LocalPlaylist(
                id = System.currentTimeMillis(),
                name = name,
                modifiedAt = System.currentTimeMillis()
            ))
            _playlists.value = list
            saveToDisk()
        }
    }

    /** 将歌曲添加到"我喜欢的音乐" */
    suspend fun addToFavorites(song: SongItem) {
        val favoritesName = context.getString(R.string.favorite_my_music)
        // 尝试多种可能的收藏夹名称
        val fav = _playlists.value.firstOrNull {
            it.name == favoritesName ||
            it.name == "我喜欢的音乐" ||
            it.name == "My Favorite Music" ||
            it.name.contains("Favorite", ignoreCase = true) ||
            it.name.contains("喜欢", ignoreCase = true)
        } ?: return
        addSongToPlaylist(fav.id, song)
    }

    /** 重命名歌单（收藏夹禁止） */
    suspend fun renamePlaylist(playlistId: Long, newName: String) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val favoritesName = context.getString(R.string.favorite_my_music)
            val updated = _playlists.value.map { pl ->
                if (pl.id == playlistId) {
                    if (pl.name == favoritesName) pl else pl.copy(name = newName, modifiedAt = now)
                } else pl
            }
            _playlists.value = updated
            saveToDisk()
        }
    }

    /** 批量删除 */
    suspend fun removeSongsFromPlaylist(playlistId: Long, songIds: List<Long>) {
        withContext(Dispatchers.IO) {
            if (songIds.isEmpty()) return@withContext
            val list = _playlists.value.toMutableList()
            val plIndex = list.indexOfFirst { it.id == playlistId }
            if (plIndex == -1) return@withContext

            val pl = list[plIndex]
            val idSet = songIds.toHashSet()
            val newSongs = pl.songs.filterNot { it.id in idSet }.toMutableList()

            if (newSongs.size != pl.songs.size) {
                list[plIndex] = pl.copy(songs = newSongs, modifiedAt = System.currentTimeMillis())
                _playlists.value = list
                saveToDisk()
            }
        }
    }

    /** 删除指定歌单(收藏夹禁止) -> 返回是否删除成功 */
    suspend fun deletePlaylist(playlistId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            val list = _playlists.value.toMutableList()
            val pl = list.find { it.id == playlistId } ?: return@withContext false
            val favoritesName = context.getString(R.string.favorite_my_music)
            if (pl.name == favoritesName) return@withContext false
            list.remove(pl)
            _playlists.value = list

            // 记录删除的歌单ID用于同步
            try {
                val storage = moe.ouom.neriplayer.data.github.SecureTokenStorage(context)
                storage.addDeletedPlaylistId(playlistId)
            } catch (e: Exception) {
                NPLogger.e("LocalPlaylistRepo", "Failed to track deleted playlist", e)
            }

            saveToDisk()
            true
        }
    }

    /** 按索引移动一首歌 */
    suspend fun moveSong(playlistId: Long, fromIndex: Int, toIndex: Int) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val updated = _playlists.value.map { pl ->
                if (pl.id != playlistId) return@map pl
                val songs = pl.songs
                if (fromIndex !in songs.indices || toIndex !in songs.indices) return@map pl
                val newSongs = songs.toMutableList().apply {
                    val s = removeAt(fromIndex)
                    add(toIndex, s)
                }
                pl.copy(songs = newSongs, modifiedAt = now)
            }
            _playlists.value = updated
            saveToDisk()
        }
    }

    /** 原子重排 */
    suspend fun reorderSongs(playlistId: Long, newOrderIds: List<Long>) {
        withContext(Dispatchers.IO) {
            val list = _playlists.value.toMutableList()
            val idx = list.indexOfFirst { it.id == playlistId }
            if (idx == -1) return@withContext
            val pl = list[idx]

            val byId = pl.songs.associateBy { it.id }
            val ordered = newOrderIds.mapNotNull { byId[it] }.toMutableList()
            // 防御：把遗漏的旧歌拼回末尾，避免丢失
            pl.songs.forEach { s -> if (ordered.none { it.id == s.id }) ordered.add(s) }

            list[idx] = pl.copy(songs = ordered, modifiedAt = System.currentTimeMillis())
            _playlists.value = list
            saveToDisk()
        }
    }

    /** 将歌曲添加到指定歌单 */
    suspend fun addSongsToPlaylist(playlistId: Long, songs: List<SongItem>) {
        withContext(Dispatchers.IO) {
            val list = _playlists.value.toMutableList()
            val idx = list.indexOfFirst { it.id == playlistId }
            if (idx == -1) return@withContext

            val pl = list[idx]
            val exists = pl.songs.asSequence().map { it.id }.toMutableSet()
            val toAdd = songs.filter { exists.add(it.id) }
            if (toAdd.isEmpty()) return@withContext

            list[idx] = pl.copy(songs = (pl.songs + toAdd).toMutableList(), modifiedAt = System.currentTimeMillis())
            _playlists.value = list
            saveToDisk()
        }
    }

    /** 将“单首”添加到指定歌单 */
    suspend fun addSongToPlaylist(playlistId: Long, song: SongItem) {
        addSongsToPlaylist(playlistId, listOf(song))
    }

    /** 从指定歌单移除歌曲 */
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        withContext(Dispatchers.IO) {
            val list = _playlists.value.toMutableList()
            val plIndex = list.indexOfFirst { it.id == playlistId }
            if (plIndex == -1) return@withContext
            val pl = list[plIndex]
            val newSongs = pl.songs.filter { it.id != songId }.toMutableList()
            if (newSongs.size != pl.songs.size) {
                list[plIndex] = pl.copy(songs = newSongs, modifiedAt = System.currentTimeMillis())
                _playlists.value = list
                saveToDisk()
            }
        }
    }

    /** 从一个歌单导出（拷贝）多首歌到另一个歌单（保持源内相对顺序；自动去重） */
    suspend fun exportSongsToPlaylist(sourcePlaylistId: Long, targetPlaylistId: Long, songIds: List<Long>) {
        withContext(Dispatchers.IO) {
            val source = _playlists.value.firstOrNull { it.id == sourcePlaylistId } ?: return@withContext
            val inSourceOrder = songIds.mapNotNull { id -> source.songs.firstOrNull { it.id == id } }
            // 直接调用批量添加（会自动去重）
            val list = _playlists.value.toMutableList()
            val idx = list.indexOfFirst { it.id == targetPlaylistId }
            if (idx == -1) return@withContext
            val pl = list[idx]

            val exists = pl.songs.asSequence().map { it.id }.toMutableSet()
            val toAdd = inSourceOrder.filter { exists.add(it.id) }
            if (toAdd.isEmpty()) return@withContext

            list[idx] = pl.copy(songs = (pl.songs + toAdd).toMutableList(), modifiedAt = System.currentTimeMillis())
            _playlists.value = list
            saveToDisk()
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

    /** 从"我喜欢的音乐"移除歌曲 */
    suspend fun removeFromFavorites(songId: Long) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val favoritesName = context.getString(R.string.favorite_my_music)
            val updated = _playlists.value.map { pl ->
                // 尝试多种可能的收藏夹名称
                if (pl.name == favoritesName ||
                    pl.name == "我喜欢的音乐" ||
                    pl.name == "My Favorite Music" ||
                    pl.name.contains("Favorite", ignoreCase = true) ||
                    pl.name.contains("喜欢", ignoreCase = true))
                    pl.copy(songs = pl.songs.filter { it.id != songId }.toMutableList(), modifiedAt = now)
                else pl
            }
            _playlists.value = updated
            saveToDisk()
        }
    }

    suspend fun updateSongMetadata(songId: Long, albumIdentifier: String, newSongInfo: SongItem) {
        withContext(Dispatchers.IO) {
            // 保存封面地址映射（如果新封面是本地地址）
            try {
                val mapper = moe.ouom.neriplayer.data.github.CoverUrlMapper.getInstance(context)

                // 保存主封面映射
                if (newSongInfo.coverUrl != null && newSongInfo.originalCoverUrl != null) {
                    mapper.saveCoverMapping(newSongInfo.coverUrl, newSongInfo.originalCoverUrl)
                }

                // 保存自定义封面映射
                if (newSongInfo.customCoverUrl != null && newSongInfo.originalCoverUrl != null) {
                    mapper.saveCoverMapping(newSongInfo.customCoverUrl, newSongInfo.originalCoverUrl)
                }
            } catch (e: Exception) {
                NPLogger.e("LocalPlaylistRepo", "Failed to save cover mapping", e)
            }

            val updatedPlaylists = _playlists.value.map { playlist ->
                val songIndex = playlist.songs.indexOfFirst { it.id == songId && it.album == albumIdentifier }

                if (songIndex != -1) {
                    val updatedSongs = playlist.songs.toMutableList().apply {
                        this[songIndex] = newSongInfo
                    }
                    playlist.copy(songs = updatedSongs, modifiedAt = System.currentTimeMillis())
                } else {
                    playlist
                }
            }

            _playlists.value = updatedPlaylists
            saveToDisk()
        }
    }

    /** 批量更新歌单列表（由同步管理器调用，不触发新的同步） */
    suspend fun updatePlaylists(playlists: List<LocalPlaylist>) {
        withContext(Dispatchers.IO) {
            _playlists.value = playlists
            saveToDisk(triggerSync = false)
        }
    }
}
