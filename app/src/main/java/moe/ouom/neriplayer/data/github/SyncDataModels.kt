package moe.ouom.neriplayer.data.github

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
 * File: moe.ouom.neriplayer.data.github/SyncDataModels
 * Created: 2025/1/7
 */

import moe.ouom.neriplayer.data.FavoritePlaylist
import moe.ouom.neriplayer.data.LocalPlaylist
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

/**
 * 同步数据结构
 * 包含所有需要同步的数据和元信息
 */
data class SyncData(
    val version: String = "2.0",
    val deviceId: String,
    val deviceName: String,
    val lastModified: Long = System.currentTimeMillis(),
    val playlists: List<SyncPlaylist> = emptyList(),
    val favoritePlaylists: List<SyncFavoritePlaylist> = emptyList(),
    val recentPlays: List<SyncRecentPlay> = emptyList(),
    val syncLog: List<SyncLogEntry> = emptyList()
)

/**
 * 同步歌单
 * 包含时间戳用于冲突检测
 */
data class SyncPlaylist(
    val id: Long,
    val name: String,
    val songs: List<SyncSong>,
    val createdAt: Long,
    val modifiedAt: Long,
    val isDeleted: Boolean = false
) {
    companion object {
        fun fromLocalPlaylist(playlist: LocalPlaylist, modifiedAt: Long = System.currentTimeMillis()): SyncPlaylist {
            return SyncPlaylist(
                id = playlist.id,
                name = playlist.name,
                songs = playlist.songs.map { SyncSong.fromSongItem(it) },
                createdAt = playlist.id, // 使用ID作为创建时间
                modifiedAt = modifiedAt
            )
        }
    }

    fun toLocalPlaylist(): LocalPlaylist {
        return LocalPlaylist(
            id = id,
            name = name,
            songs = songs.map { it.toSongItem() }.toMutableList(),
            modifiedAt = modifiedAt
        )
    }
}

/**
 * 同步歌曲
 */
data class SyncSong(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val coverUrl: String?,
    val addedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromSongItem(song: SongItem): SyncSong {
            return SyncSong(
                id = song.id,
                name = song.name,
                artist = song.artist,
                album = song.album,
                albumId = song.albumId,
                durationMs = song.durationMs,
                coverUrl = song.coverUrl
            )
        }
    }

    fun toSongItem(): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = album,
            albumId = albumId,
            durationMs = durationMs,
            coverUrl = coverUrl
        )
    }
}

/**
 * 最近播放记录
 */
data class SyncRecentPlay(
    val songId: Long,
    val song: SyncSong,
    val playedAt: Long,
    val deviceId: String
)

/**
 * 收藏的歌单
 */
data class SyncFavoritePlaylist(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val source: String,
    val songs: List<SyncSong>,
    val addedTime: Long
) {
    companion object {
        fun fromFavoritePlaylist(playlist: FavoritePlaylist): SyncFavoritePlaylist {
            return SyncFavoritePlaylist(
                id = playlist.id,
                name = playlist.name,
                coverUrl = playlist.coverUrl,
                trackCount = playlist.trackCount,
                source = playlist.source,
                songs = playlist.songs.map { SyncSong.fromSongItem(it) },
                addedTime = playlist.addedTime
            )
        }
    }

    fun toFavoritePlaylist(): FavoritePlaylist {
        return FavoritePlaylist(
            id = id,
            name = name,
            coverUrl = coverUrl,
            trackCount = trackCount,
            source = source,
            songs = songs.map { it.toSongItem() },
            addedTime = addedTime
        )
    }
}

/**
 * 同步日志条目
 * 用于追踪操作历史,辅助冲突解决
 */
data class SyncLogEntry(
    val timestamp: Long,
    val deviceId: String,
    val action: SyncAction,
    val playlistId: Long? = null,
    val songId: Long? = null,
    val details: String? = null
)

/**
 * 同步操作类型
 */
enum class SyncAction {
    CREATE_PLAYLIST,
    DELETE_PLAYLIST,
    RENAME_PLAYLIST,
    ADD_SONG,
    REMOVE_SONG,
    REORDER_SONGS,
    PLAY_SONG
}

/**
 * 同步结果
 */
data class SyncResult(
    val success: Boolean,
    val message: String,
    val playlistsAdded: Int = 0,
    val playlistsUpdated: Int = 0,
    val playlistsDeleted: Int = 0,
    val songsAdded: Int = 0,
    val songsRemoved: Int = 0,
    val conflicts: List<SyncConflict> = emptyList()
)

/**
 * 同步冲突
 */
data class SyncConflict(
    val type: ConflictType,
    val playlistId: Long,
    val playlistName: String,
    val description: String,
    val resolution: ConflictResolution
)

/**
 * 冲突类型
 */
enum class ConflictType {
    PLAYLIST_RENAMED_BOTH_SIDES,
    SONG_ADDED_REMOVED_CONFLICT,
    PLAYLIST_DELETED_MODIFIED_CONFLICT
}

/**
 * 冲突解决方式
 */
enum class ConflictResolution {
    AUTO_MERGED,
    LOCAL_WINS,
    REMOTE_WINS,
    MANUAL_REQUIRED
}
