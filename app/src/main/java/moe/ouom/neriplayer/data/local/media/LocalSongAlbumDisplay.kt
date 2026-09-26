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
    usesFallbackAlbum: Boolean,
    stripManagedSourcePrefix: Boolean = false
): String {
    val normalized = album?.trim().orEmpty()
    if (normalized.isBlank()) return LocalSongSupport.LOCAL_ALBUM_IDENTITY
    if (usesFallbackAlbum) return LocalSongSupport.LOCAL_ALBUM_IDENTITY

    // 只有带受管下载来源身份的歌曲才清理历史来源前缀
    val withoutSourcePrefix = if (
        stripManagedSourcePrefix &&
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

internal fun isNeteaseManagedSourceStableKey(sourceStableKey: String?): Boolean {
    val normalized = sourceStableKey?.trim()?.takeIf(String::isNotBlank) ?: return false
    val firstSeparator = normalized.indexOf('|')
    if (firstSeparator <= 0 || normalized.substring(0, firstSeparator).toLongOrNull() == null) {
        return false
    }
    val secondSeparator = normalized.indexOf('|', firstSeparator + 1)
    if (secondSeparator <= firstSeparator + 1) return false
    val sourceAlbum = normalized.substring(firstSeparator + 1, secondSeparator)
    val sourceUri = normalized.substring(secondSeparator + 1)
    return sourceAlbum.equals("netease", ignoreCase = true) && sourceUri.isBlank()
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
        context.getString(R.string.local_files)
    } else {
        displayValue
    }
}

private const val LOCAL_SOURCE_ALBUM_PREFIX = "Netease"
