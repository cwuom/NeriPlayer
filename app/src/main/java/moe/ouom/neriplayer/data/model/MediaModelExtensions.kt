package moe.ouom.neriplayer.data.model

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
 * File: moe.ouom.neriplayer.data.model/MediaModelExtensions
 * Updated: 2026/3/23
 */

import android.content.Context
import android.os.Looper
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

fun SongItem.displayCoverUrl(): String? = customCoverUrl ?: coverUrl

fun SongItem.displayCoverUrl(context: Context): String? {
    customCoverUrl?.takeIf { it.isNotBlank() }?.let { return it }

    val current = coverUrl?.takeIf { it.isNotBlank() }
    val onMainThread = Looper.myLooper() == Looper.getMainLooper()
    if (!current.isNullOrBlank() && !current.isRemoteCoverSource()) {
        return current
    }
    if (!current.isNullOrBlank() && onMainThread) {
        return current
    }

    AudioDownloadManager.getLocalCoverUri(context, this)?.let { return it }
    if (!isLocalSong()) return current
    if (onMainThread) return current
    LocalMediaSupport.inspect(context, this)?.coverUri?.takeIf { it.isNotBlank() }?.let { return it }
    return current
}

fun SongItem.displayName(): String = customName ?: name
fun SongItem.displayArtist(): String = customArtist ?: artist

fun LocalPlaylist.displayCoverUrl(): String? = customCoverUrl ?: songs.lastOrNull()?.displayCoverUrl()

fun LocalPlaylist.displayCoverUrl(context: Context): String? {
    return customCoverUrl ?: songs.lastOrNull()?.displayCoverUrl(context)
}

private fun String.isRemoteCoverSource(): Boolean {
    return startsWith("http://", ignoreCase = true) ||
        startsWith("https://", ignoreCase = true)
}
