package moe.ouom.neriplayer.data.local.media

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
 * File: moe.ouom.neriplayer.data.local.media/LocalSongAlbumDisplay
 * Updated: 2026/3/23
 */

import android.content.Context
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.SongItem

internal fun normalizeLocalAlbumIdentity(
    album: String?,
    usesFallbackAlbum: Boolean
): String {
    val normalized = album?.trim().orEmpty()
    if (normalized.isBlank()) return LocalSongSupport.LOCAL_ALBUM_IDENTITY
    if (usesFallbackAlbum) return LocalSongSupport.LOCAL_ALBUM_IDENTITY

    // 下载器把来源直接拼在专辑名前, 这里只清理本地导入产生的来源标记
    val withoutSourcePrefix = if (
        normalized.length >= LOCAL_SOURCE_ALBUM_PREFIX.length &&
            normalized.regionMatches(
                0,
                LOCAL_SOURCE_ALBUM_PREFIX,
                0,
                LOCAL_SOURCE_ALBUM_PREFIX.length,
                ignoreCase = true
            ) && (
                normalized.length == LOCAL_SOURCE_ALBUM_PREFIX.length ||
                    !normalized[LOCAL_SOURCE_ALBUM_PREFIX.length].isWhitespace()
                )
    ) {
        normalized.substring(LOCAL_SOURCE_ALBUM_PREFIX.length)
            .trim()
            .trimStart('-', ':', '_', '|')
            .trim()
    } else {
        normalized
    }
    return withoutSourcePrefix.takeIf { it.isNotBlank() }
        ?: LocalSongSupport.LOCAL_ALBUM_IDENTITY
}

fun SongItem.displayAlbum(context: Context): String {
    val normalized = album.trim()
    if (normalized.isBlank()) return normalized
    val displayValue = if (LocalSongSupport.isLocalSong(this, context)) {
        normalizeLocalAlbumIdentity(normalized, usesFallbackAlbum = false)
    } else {
        normalized
    }
    return if (
        displayValue == LocalSongSupport.LOCAL_ALBUM_IDENTITY ||
        LocalFilesPlaylist.matches(displayValue, context)
    ) {
        context.getString(R.string.local_files)
    } else {
        displayValue
    }
}

private const val LOCAL_SOURCE_ALBUM_PREFIX = "Netease"
