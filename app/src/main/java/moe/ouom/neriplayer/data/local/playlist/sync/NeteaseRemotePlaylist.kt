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
 * File: moe.ouom.neriplayer.data.local.playlist.sync/NeteaseRemotePlaylist
 * Created: 2026/8/10
 */

import org.json.JSONObject
import java.io.IOException

data class NeteaseRemotePlaylist(
    val id: Long,
    val name: String,
    val trackCount: Int
)

internal fun parseNeteaseRemotePlaylists(
    raw: String,
    ownerUserId: Long? = null
): List<NeteaseRemotePlaylist> {
    if (raw.isBlank()) {
        throw IOException("NetEase playlist response is empty")
    }

    val root = try {
        JSONObject(raw)
    } catch (error: Exception) {
        throw IOException("Failed to parse NetEase playlist response", error)
    }
    val code = root.optInt("code", -1)
    if (code != 200) {
        val message = root.optString("msg", "").trim()
        throw IOException(
            message.ifBlank { "NetEase playlist request failed with code $code" }
        )
    }

    val array = root.optJSONArray("playlist")
        ?: root.optJSONArray("playlists")
        ?: throw IOException("NetEase playlist response is missing the playlist list")
    val result = ArrayList<NeteaseRemotePlaylist>(array.length())
    val seenIds = LinkedHashSet<Long>()
    for (index in 0 until array.length()) {
        val playlist = array.optJSONObject(index) ?: continue
        val id = playlist.optLong("id", 0L)
        val name = playlist.optString("name", "").trim()
        val creatorId = playlist.optJSONObject("creator")?.optLong("userId", 0L) ?: 0L
        if (ownerUserId != null && creatorId != ownerUserId) continue
        if (id <= 0L || name.isBlank() || !seenIds.add(id)) continue
        result += NeteaseRemotePlaylist(
            id = id,
            name = name,
            trackCount = playlist.optInt("trackCount", 0)
        )
    }
    return result
}
