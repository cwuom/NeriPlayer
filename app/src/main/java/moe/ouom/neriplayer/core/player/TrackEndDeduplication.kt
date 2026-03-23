package moe.ouom.neriplayer.core.player

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
 * File: moe.ouom.neriplayer.core.player/TrackEndDeduplication
 * Updated: 2026/3/23
 */

internal const val PENDING_TRACK_END_DEDUPLICATION_KEY = "__pending_track_end__"

internal fun trackEndDeduplicationKey(
    mediaId: String?,
    fallbackSongKey: String?
): String {
    return mediaId ?: fallbackSongKey ?: PENDING_TRACK_END_DEDUPLICATION_KEY
}

internal fun shouldHandleTrackEnd(
    lastHandledKey: String?,
    currentKey: String
): Boolean {
    return lastHandledKey != currentKey
}
