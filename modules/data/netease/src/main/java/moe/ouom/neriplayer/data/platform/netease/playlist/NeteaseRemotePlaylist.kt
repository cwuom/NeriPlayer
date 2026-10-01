package moe.ouom.neriplayer.data.platform.netease.playlist

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
 * File: moe.ouom.neriplayer.data.platform.netease.playlist/NeteaseRemotePlaylist
 * Created: 2026/8/10
 */

import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseRemotePlaylist
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

fun parseNeteaseRemotePlaylists(
    raw: String,
    ownerUserId: Long? = null
): List<NeteaseRemotePlaylist> {
    val array = parseNeteaseRemotePlaylistResponse(raw)
    val result = ArrayList<NeteaseRemotePlaylist>(array.length())
    val seenIds = LinkedHashSet<Long>()
    for (index in 0 until array.length()) {
        val playlist = array.optJSONObject(index) ?: continue
        val item = parseOwnedNeteaseRemotePlaylist(playlist, ownerUserId) ?: continue
        if (seenIds.add(item.id)) {
            result += item
        }
    }
    return result
}

private fun parseNeteaseRemotePlaylistResponse(raw: String): JSONArray {
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

    return root.optJSONArray("playlist")
        ?: root.optJSONArray("playlists")
        ?: throw IOException("NetEase playlist response is missing the playlist list")
}

private fun parseOwnedNeteaseRemotePlaylist(
    playlist: JSONObject,
    ownerUserId: Long?
): NeteaseRemotePlaylist? {
    val id = playlist.optLong("id", 0L)
    val name = playlist.optString("name", "").trim()
    if (!playlist.belongsToNeteaseUser(ownerUserId)) return null
    if (!isValidNeteaseRemotePlaylistIdentity(id, name)) return null
    return NeteaseRemotePlaylist(
        id = id,
        name = name,
        trackCount = playlist.optInt("trackCount", 0)
    )
}

private fun JSONObject.belongsToNeteaseUser(ownerUserId: Long?): Boolean {
    val creatorId = optJSONObject("creator")?.optLong("userId", 0L) ?: 0L
    return ownerUserId == null || creatorId == ownerUserId
}

private fun isValidNeteaseRemotePlaylistIdentity(id: Long, name: String): Boolean {
    return id > 0L && name.isNotBlank()
}
