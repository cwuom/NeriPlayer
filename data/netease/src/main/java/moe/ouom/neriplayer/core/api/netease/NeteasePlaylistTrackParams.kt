package moe.ouom.neriplayer.core.api.netease

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
 * File: moe.ouom.neriplayer.core.api.netease/NeteasePlaylistTrackParams
 * Created: 2026/8/11
 */

internal fun buildNeteasePlaylistAddTracksParams(
    playlistId: Long,
    songIds: List<Long>
): Map<String, Any> {
    require(playlistId > 0L) { "playlistId must be positive" }
    require(songIds.isNotEmpty()) { "songIds must not be empty" }
    val ids = songIds.asSequence()
        .filter { it > 0L }
        .distinct()
        .toList()
    require(ids.isNotEmpty()) { "songIds must contain a positive id" }
    val idsCsv = ids.joinToString(",")
    val idsJson = ids.joinToString(
        separator = ",",
        prefix = "[",
        postfix = "]"
    )
    return mapOf(
        "op" to "add",
        "pid" to playlistId.toString(),
        "id" to playlistId.toString(),
        "tracks" to idsCsv,
        "trackIds" to idsJson,
        "imme" to "true"
    )
}
