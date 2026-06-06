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
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal fun normalizeLocalAlbumIdentity(
    album: String?,
    usesFallbackAlbum: Boolean
): String {
    val normalized = album?.trim().orEmpty()
    if (normalized.isBlank()) return LocalSongSupport.LOCAL_ALBUM_IDENTITY
    return if (usesFallbackAlbum) LocalSongSupport.LOCAL_ALBUM_IDENTITY else normalized
}

fun SongItem.displayAlbum(context: Context): String {
    val normalized = album.trim()
    if (normalized.isBlank()) return normalized
    return if (
        normalized == LocalSongSupport.LOCAL_ALBUM_IDENTITY ||
        LocalFilesPlaylist.matches(normalized, context)
    ) {
        context.getString(R.string.local_files)
    } else {
        normalized
    }
}
