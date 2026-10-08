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
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.SongItem

internal fun normalizeLocalAlbumIdentity(
    album: String?,
    usesFallbackAlbum: Boolean,
    stripManagedSourcePrefix: Boolean = false
): String {
    val normalized = album?.trim().orEmpty()
    if (normalized.isBlank() || usesFallbackAlbum) return LocalSongSupport.LOCAL_ALBUM_IDENTITY

    // 只有带受管下载来源身份的歌曲才清理历史来源前缀
    val withoutSourcePrefix = if (stripManagedSourcePrefix && hasLegacySourceAlbumPrefix(normalized)) {
        normalized.substring(LOCAL_SOURCE_ALBUM_PREFIX.length)
            .trim()
            .trimStart('-', ':', '_', '|')
            .trim()
    } else {
        normalized
    }
    return withoutSourcePrefix.ifBlank { LocalSongSupport.LOCAL_ALBUM_IDENTITY }
}

private fun hasLegacySourceAlbumPrefix(album: String): Boolean =
    album.startsWith(LOCAL_SOURCE_ALBUM_PREFIX, ignoreCase = true) &&
        album.getOrNull(LOCAL_SOURCE_ALBUM_PREFIX.length)?.isWhitespace() != true

internal fun isNeteaseManagedSourceStableKey(sourceStableKey: String?): Boolean {
    val parts = sourceStableKey.orEmpty().trim().split('|', limit = 3)
    return parts.size == 3 &&
        parts[0].toLongOrNull() != null &&
        parts[1].equals("netease", ignoreCase = true) &&
        parts[2].isBlank()
}

fun SongItem.displayAlbum(context: Context): String {
    val normalized = album.trim()
    if (normalized.isBlank()) return normalized
    val displayValue = if (LocalSongSupport.isLocalSong(this, context)) {
        normalizeLocalAlbumIdentity(
            album = normalized,
            usesFallbackAlbum = false,
            stripManagedSourcePrefix = isNeteaseManagedSourceStableKey(sourceStableKey)
        )
    } else {
        normalized
    }
    return if (
        displayValue == LocalSongSupport.LOCAL_ALBUM_IDENTITY ||
        LocalFilesPlaylist.matches(displayValue, context)
    ) {
        context.getString(CoreCommonR.string.local_files)
    } else {
        displayValue
    }
}

private const val LOCAL_SOURCE_ALBUM_PREFIX = "Netease"
