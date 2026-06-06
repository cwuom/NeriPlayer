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
 * File: moe.ouom.neriplayer.core.player.model/SongUrlResult
 * Updated: 2026/3/23
 */

internal sealed class SongUrlResult {
    data class Success(
        val url: String,
        val durationMs: Long? = null,
        val mimeType: String? = null,
        val noticeMessage: String? = null,
        val expectedContentLength: Long? = null,
        val audioInfo: PlaybackAudioInfo? = null
    ) : SongUrlResult()

    object WaitingForAuthoritativeStream : SongUrlResult()
    object RequiresLogin : SongUrlResult()
    object Failure : SongUrlResult()
}
