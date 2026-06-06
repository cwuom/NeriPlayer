package moe.ouom.neriplayer.data.local.playlist.system

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
 * File: moe.ouom.neriplayer.data.local.playlist.system/SystemPlaylistNameCompat
 * Updated: 2026/3/23
 */


import java.nio.charset.Charset

private val legacyMojibakeCharsets: List<Charset> = buildList {
    runCatching { Charset.forName("GBK") }.getOrNull()?.let(::add)
    runCatching { Charset.forName("GB18030") }.getOrNull()?.let(::add)
}
private const val NUL_CHAR = '\u0000'

internal fun buildSystemPlaylistCandidateNames(
    canonicalChineseName: String,
    canonicalEnglishName: String,
    localizedName: String
): Set<String> {
    return buildSet {
        add(canonicalChineseName)
        add(canonicalEnglishName)
        add(localizedName)
        addAll(generateLegacyMojibakeVariants(canonicalChineseName))
    }
}

private fun generateLegacyMojibakeVariants(sourceName: String): Set<String> {
    if (sourceName.isBlank() || legacyMojibakeCharsets.isEmpty()) return emptySet()

    // 这里只兼容历史上一层 UTF-8 -> ANSI 误解码的脏值，避免把二次/三次乱码继续扩散进源码语义
    return legacyMojibakeCharsets.mapNotNullTo(linkedSetOf()) { charset ->
        runCatching {
            String(sourceName.toByteArray(Charsets.UTF_8), charset)
                .trimEnd(NUL_CHAR)
                .takeIf { it.isNotBlank() && it != sourceName }
        }.getOrNull()
    }
}
