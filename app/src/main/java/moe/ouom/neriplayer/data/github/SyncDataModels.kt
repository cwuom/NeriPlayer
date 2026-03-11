@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

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

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import moe.ouom.neriplayer.data.SystemLocalPlaylists
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.FavoritePlaylist
import moe.ouom.neriplayer.data.LocalSongSupport
import moe.ouom.neriplayer.data.LocalPlaylist
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

/**
 * 同步数据结构
 * 包含所有需要同步的数据和元信息
 */
@Serializable
data class SyncData(
    @ProtoNumber(1) val version: String = "2.0",
    @ProtoNumber(2) val deviceId: String,
    @ProtoNumber(3) val deviceName: String,
    @ProtoNumber(4) val lastModified: Long = System.currentTimeMillis(),
    @ProtoNumber(5) val playlists: List<SyncPlaylist> = emptyList(),
    @ProtoNumber(6) val favoritePlaylists: List<SyncFavoritePlaylist> = emptyList(),
    @ProtoNumber(7) val recentPlays: List<SyncRecentPlay> = emptyList(),
    @ProtoNumber(8) val syncLog: List<SyncLogEntry> = emptyList()
)

/**
 * 同步歌单
 * 包含时间戳用于冲突检测
 */
@Serializable
data class SyncPlaylist(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val songs: List<SyncSong>,
    @ProtoNumber(4) val createdAt: Long,
    @ProtoNumber(5) val modifiedAt: Long,
    @ProtoNumber(6) val isDeleted: Boolean = false
) {
    companion object {
        fun fromLocalPlaylist(playlist: LocalPlaylist, modifiedAt: Long = System.currentTimeMillis(), context: Context? = null): SyncPlaylist {
            val systemDescriptor = context?.let {
                SystemLocalPlaylists.resolve(playlist.id, playlist.name, it)
            }
            return SyncPlaylist(
                id = systemDescriptor?.id ?: playlist.id,
                name = systemDescriptor?.currentName ?: playlist.name,
                songs = playlist.songs.mapNotNull { SyncSong.fromSongItemOrNull(it, context) },
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
@Serializable
data class SyncSong(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val artist: String,
    @ProtoNumber(4) val album: String,
    @ProtoNumber(5) val albumId: Long,
    @ProtoNumber(6) val durationMs: Long,
    @ProtoNumber(7) val coverUrl: String?,
    @ProtoNumber(8) val mediaUri: String? = null,
    @ProtoNumber(9) val addedAt: Long = System.currentTimeMillis(),
    @ProtoNumber(10) val matchedLyric: String? = null,
    @ProtoNumber(11) val matchedTranslatedLyric: String? = null,
    @ProtoNumber(12) val matchedLyricSource: String? = null,
    @ProtoNumber(13) val matchedSongId: String? = null,
    @ProtoNumber(14) val userLyricOffsetMs: Long = 0L,
    @ProtoNumber(15) val customCoverUrl: String? = null,
    @ProtoNumber(16) val customName: String? = null,
    @ProtoNumber(17) val customArtist: String? = null,
    @ProtoNumber(18) val originalName: String? = null,
    @ProtoNumber(19) val originalArtist: String? = null,
    @ProtoNumber(20) val originalCoverUrl: String? = null,
    @ProtoNumber(21) val originalLyric: String? = null,
    @ProtoNumber(22) val originalTranslatedLyric: String? = null
) {
    companion object {
        fun fromSongItemOrNull(song: SongItem, context: Context? = null): SyncSong? {
            if (LocalSongSupport.isLocalSong(song, context)) {
                return null
            }
            return fromSongItem(song, context)
        }

        fun fromSongItem(song: SongItem, context: Context? = null): SyncSong {
            // 使用网络地址进行同步
            val mapper = context?.let { CoverUrlMapper.getInstance(it) }
            val syncCoverUrl = mapper?.getNetworkUrl(song.coverUrl) ?: song.coverUrl
            val syncCustomCoverUrl = mapper?.getNetworkUrl(song.customCoverUrl) ?: song.customCoverUrl
            val syncOriginalCoverUrl = mapper?.getNetworkUrl(song.originalCoverUrl) ?: song.originalCoverUrl

            return SyncSong(
                id = song.id,
                name = song.name,
                artist = song.artist,
                album = song.album,
                albumId = song.albumId,
                durationMs = song.durationMs,
                coverUrl = syncCoverUrl,
                mediaUri = LocalSongSupport.sanitizeMediaUriForSync(song.mediaUri),
                matchedLyric = song.matchedLyric,
                matchedTranslatedLyric = song.matchedTranslatedLyric,
                matchedLyricSource = song.matchedLyricSource?.name,
                matchedSongId = song.matchedSongId,
                userLyricOffsetMs = song.userLyricOffsetMs,
                customCoverUrl = syncCustomCoverUrl,
                customName = song.customName,
                customArtist = song.customArtist,
                originalName = song.originalName,
                originalArtist = song.originalArtist,
                originalCoverUrl = syncOriginalCoverUrl,
                originalLyric = song.originalLyric,
                originalTranslatedLyric = song.originalTranslatedLyric
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
            coverUrl = coverUrl,
            mediaUri = LocalSongSupport.sanitizeMediaUriForSync(mediaUri),
            matchedLyric = matchedLyric,
            matchedTranslatedLyric = matchedTranslatedLyric,
            matchedLyricSource = matchedLyricSource?.let {
                try { MusicPlatform.valueOf(it) } catch (e: Exception) { null }
            },
            matchedSongId = matchedSongId,
            userLyricOffsetMs = userLyricOffsetMs,
            customCoverUrl = customCoverUrl,
            customName = customName,
            customArtist = customArtist,
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl,
            originalLyric = originalLyric,
            originalTranslatedLyric = originalTranslatedLyric
        )
    }
}

/**
 * 最近播放记录
 */
@Serializable
data class SyncRecentPlay(
    @ProtoNumber(1) val songId: Long,
    @ProtoNumber(2) val song: SyncSong,
    @ProtoNumber(3) val playedAt: Long,
    @ProtoNumber(4) val deviceId: String
)

/**
 * 收藏的歌单
 */
@Serializable
data class SyncFavoritePlaylist(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val coverUrl: String?,
    @ProtoNumber(4) val trackCount: Int,
    @ProtoNumber(5) val source: String,
    @ProtoNumber(6) val songs: List<SyncSong>,
    @ProtoNumber(7) val addedTime: Long,
    @ProtoNumber(8) val modifiedAt: Long = addedTime,
    @ProtoNumber(9) val isDeleted: Boolean = false
) {
    companion object {
        fun fromFavoritePlaylist(playlist: FavoritePlaylist, context: Context? = null): SyncFavoritePlaylist {
            if (playlist.isDeleted) {
                return SyncFavoritePlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    coverUrl = playlist.coverUrl,
                    trackCount = 0,
                    source = playlist.source,
                    songs = emptyList(),
                    addedTime = playlist.addedTime,
                    modifiedAt = playlist.modifiedAt,
                    isDeleted = true
                )
            }
            val syncedSongs = playlist.songs.mapNotNull { SyncSong.fromSongItemOrNull(it, context) }
            val hasFilteredLocalSongs = syncedSongs.size != playlist.songs.size
            val syncedCoverUrl = playlist.coverUrl
                ?.takeUnless { LocalSongSupport.isLocalMediaUri(it) }
                ?: syncedSongs.firstOrNull()?.coverUrl
            return SyncFavoritePlaylist(
                id = playlist.id,
                name = playlist.name,
                coverUrl = syncedCoverUrl,
                trackCount = if (hasFilteredLocalSongs) {
                    syncedSongs.size
                } else {
                    maxOf(playlist.trackCount, syncedSongs.size)
                },
                source = playlist.source,
                songs = syncedSongs,
                addedTime = playlist.addedTime,
                modifiedAt = playlist.modifiedAt,
                isDeleted = false
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
            addedTime = addedTime,
            modifiedAt = modifiedAt,
            isDeleted = isDeleted
        )
    }
}

/**
 * 同步日志条目
 * 用于追踪操作历史,辅助冲突解决
 */
@Serializable
data class SyncLogEntry(
    @ProtoNumber(1) val timestamp: Long,
    @ProtoNumber(2) val deviceId: String,
    @ProtoNumber(3) val action: SyncAction,
    @ProtoNumber(4) val playlistId: Long? = null,
    @ProtoNumber(5) val songId: Long? = null,
    @ProtoNumber(6) val details: String? = null
)

/**
 * 同步操作类型
 */
@Serializable
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
