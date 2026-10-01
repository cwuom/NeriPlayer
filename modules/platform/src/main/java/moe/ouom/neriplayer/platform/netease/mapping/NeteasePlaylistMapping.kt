package moe.ouom.neriplayer.platform.netease.mapping

import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.common.json.mapObjectsNotNull
import org.json.JSONObject

fun parseNeteaseSearchPlaylists(result: JSONObject): List<PlaylistSummary> {
    val playlists = result.optJSONArray("playlists") ?: return emptyList()
    return playlists.mapObjectsNotNull { playlist ->
        val id = playlist.optLong("id", 0L)
        val name = playlist.optString("name", "")
        if (id <= 0L || name.isBlank()) return@mapObjectsNotNull null
        PlaylistSummary(
            id = id,
            name = name,
            picUrl = toHttps(
                playlist.optString("coverImgUrl", "")
                    .ifBlank { playlist.optString("picUrl", "") }
            ),
            playCount = playlist.optLong("playCount", 0L),
            trackCount = playlist.optInt("trackCount", 0)
        )
    }
}

private fun toHttps(url: String?): String {
    return url.orEmpty().replaceFirst("http://", "https://")
}
