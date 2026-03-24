package moe.ouom.neriplayer.ui.viewmodel.tab

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
 * File: moe.ouom.neriplayer.ui.viewmodel.tab/YouTubeMusicModels
 * Updated: 2026/3/23
 */

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId

@Suppress("unused")
@Parcelize
data class YouTubeMusicPlaylist(
    val browseId: String,
    val playlistId: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val trackCount: Int = 0
) : Parcelable

fun YouTubeMusicPlaylist.favoriteId(): Long {
    return stableYouTubeMusicId(playlistId.ifBlank { browseId })
}
