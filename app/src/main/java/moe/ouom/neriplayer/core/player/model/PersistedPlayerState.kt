package moe.ouom.neriplayer.core.player.model

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
 * File: moe.ouom.neriplayer.core.player.model/PersistedPlayerState
 * Updated: 2026/3/23
 */

import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal data class PersistedState(
    val playlist: List<SongItem>,
    val index: Int,
    val mediaUrl: String? = null,
    val positionMs: Long = 0L,
    val shouldResumePlayback: Boolean = false,
    val repeatMode: Int? = null,
    val shuffleEnabled: Boolean? = null
)
