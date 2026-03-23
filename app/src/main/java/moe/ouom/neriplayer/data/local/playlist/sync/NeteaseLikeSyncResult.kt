package moe.ouom.neriplayer.data.local.playlist.sync

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
 * File: moe.ouom.neriplayer.data.local.playlist.sync/NeteaseLikeSyncResult
 * Updated: 2026/3/23
 */

data class NeteaseLikeSyncResult(
    val totalSongs: Int,
    val supportedSongs: Int,
    val skippedUnsupported: Int,
    val skippedExisting: Int,
    val added: Int,
    val failed: Int,
    val message: String? = null
)
