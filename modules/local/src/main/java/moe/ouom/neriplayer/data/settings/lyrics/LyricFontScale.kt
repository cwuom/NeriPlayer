package moe.ouom.neriplayer.data.settings.lyrics

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
 * File: moe.ouom.neriplayer.data.settings/LyricFontScale
 * Updated: 2026/3/24
 */

import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.data.settings.defaultLyricFontScales
const val MIN_LYRIC_FONT_SCALE = 0.5f
const val MAX_LYRIC_FONT_SCALE = 1.6f

// 统一歌词字号缩放范围，避免滑杆百分比和实际渲染结果不一致
fun normalizeLyricFontScale(scale: Float): Float =
    scale.coerceIn(MIN_LYRIC_FONT_SCALE, MAX_LYRIC_FONT_SCALE)

fun scaledLyricFontSize(baseSizeSp: Float, scale: Float): Float =
    baseSizeSp * normalizeLyricFontScale(scale)

private fun resolveLyricFontScale(explicitScale: Float?, fallbackScale: Float): Float =
    normalizeLyricFontScale(explicitScale ?: fallbackScale)

fun resolveLyricFontScales(
    legacyScale: Float?,
    coverLyric: Float?,
    coverTranslation: Float?,
    lyricsPageLyric: Float?,
    lyricsPageTranslation: Float?,
    defaults: LyricFontScales = defaultLyricFontScales(smallestScreenWidthDp = 0)
): LyricFontScales {
    // 旧版统一字号只有实际保存时才优先于设备默认
    val fallback = legacyScale?.let { scale ->
        LyricFontScales(scale, scale, scale, scale)
    } ?: defaults
    return LyricFontScales(
        coverLyric = resolveLyricFontScale(coverLyric, fallback.coverLyric),
        coverTranslation = resolveLyricFontScale(coverTranslation, fallback.coverTranslation),
        lyricsPageLyric = resolveLyricFontScale(lyricsPageLyric, fallback.lyricsPageLyric),
        lyricsPageTranslation = resolveLyricFontScale(
            lyricsPageTranslation, fallback.lyricsPageTranslation
        )
    )
}
