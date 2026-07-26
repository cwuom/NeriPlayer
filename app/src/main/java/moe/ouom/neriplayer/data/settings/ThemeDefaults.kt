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
    const val DEFAULT_PALETTE_STYLE = "TonalSpot"
    const val DEFAULT_COLOR_SPEC = "SPEC_2021"
    val PALETTE_STYLES = listOf(
        "TonalSpot",
        "Neutral",
        "Vibrant",
        "Expressive",
        "Rainbow",
        "FruitSalad",
        "Monochrome",
        "Fidelity",
        "Content"
    )
    val COLOR_SPECS = listOf("SPEC_2021", "SPEC_2025")
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

    private val SEED_COLOR_HEX_REGEX = Regex("^[0-9A-F]{6}$")

    // 归一种子色 hex：去 # 前缀、转大写，仅接受合法 6 位 hex，否则回退默认。
    // 写入前拦截与主题解析前兜底共用，避免非法值使 toColorInt 在主题组合期崩溃
    fun sanitizeSeedColorHex(value: String?): String {
        val normalized = value?.trim()?.removePrefix("#")?.uppercase(Locale.ROOT)
        return normalized?.takeIf { SEED_COLOR_HEX_REGEX.matches(it) } ?: DEFAULT_SEED_COLOR_HEX
    }

    fun normalizePaletteStyle(value: String?): String {
        return PALETTE_STYLES.firstOrNull { it.equals(value, ignoreCase = true) }
            ?: DEFAULT_PALETTE_STYLE
    }

    fun normalizeColorSpec(value: String?): String {
        return COLOR_SPECS.firstOrNull { it.equals(value, ignoreCase = true) }
            ?: DEFAULT_COLOR_SPEC
    }
}
