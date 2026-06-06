package moe.ouom.neriplayer.core.player.metadata

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
 * File: moe.ouom.neriplayer.core.player.metadata/PlayerLyricUtils
 * Updated: 2026/3/23
 */

import moe.ouom.neriplayer.ui.component.LyricEntry

internal fun convertPlainLyricsToEntries(text: String, durationMs: Long): List<LyricEntry> {
    val lines = text.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) {
        return emptyList()
    }

    val totalMs = durationMs.coerceAtLeast(1L)
    val intervalMs = totalMs / lines.size.coerceAtLeast(1)
    return lines.mapIndexed { index, line ->
        val startMs = index * intervalMs
        val endMs = if (index < lines.lastIndex) {
            (index + 1) * intervalMs
        } else {
            totalMs
        }
        LyricEntry(
            text = line.trim(),
            startTimeMs = startMs,
            endTimeMs = endMs
        )
    }
}
