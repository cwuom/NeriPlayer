package moe.ouom.neriplayer.core.api.search

import kotlinx.serialization.Serializable

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
 * File: moe.ouom.neriplayer.core.api.search/SongData
 * Created: 2025/8/17
 */

enum class MusicPlatform {
    CLOUD_MUSIC, QQ_MUSIC
}

@Serializable
data class SongSearchInfo(
    val id: String,
    val songName: String,
    val singer: String,
    val duration: String,
    val source: MusicPlatform,
    val albumName: String?,
    val coverUrl: String?
)

@Serializable
data class SongDetails(
    val id: String,
    val songName: String,
    val singer: String,
    val album: String,
    val albumArt: ByteArray?,
    val lyric: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SongDetails

        if (id != other.id) return false
        if (songName != other.songName) return false
        if (singer != other.singer) return false
        if (album != other.album) return false
        if (!albumArt.contentEquals(other.albumArt)) return false
        if (lyric != other.lyric) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + songName.hashCode()
        result = 31 * result + singer.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + (albumArt?.contentHashCode() ?: 0)
        result = 31 * result + (lyric?.hashCode() ?: 0)
        return result
    }
}
