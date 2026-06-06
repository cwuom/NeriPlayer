package moe.ouom.neriplayer.data.settings

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
 * File: moe.ouom.neriplayer.data.settings/ThemeDefaults
 * Updated: 2026/3/23
 */

import java.util.Locale

object ThemeDefaults {
    const val DEFAULT_SEED_COLOR_HEX = "0061A4"
    val PRESET_COLORS = listOf(
        "0061A4",
        "6750A4",
        "B3261E",
        "C425A8",
        "00897B",
        "388E3C",
        "FBC02D",
        "E65100"
    )
    val PRESET_SET = PRESET_COLORS.map { it.uppercase(Locale.ROOT) }.toSet()
}
