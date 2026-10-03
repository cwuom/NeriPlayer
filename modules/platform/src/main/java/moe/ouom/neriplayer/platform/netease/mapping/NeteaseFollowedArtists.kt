package moe.ouom.neriplayer.platform.netease.mapping

import moe.ouom.neriplayer.common.json.mapObjectsNotNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class NeteaseFollowedArtist(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val musicSize: Int,
    val alias: String?
)

data class NeteaseFollowedArtistPage(
    val artists: List<NeteaseFollowedArtist>,
    val hasMore: Boolean,
    val rawCount: Int
)

fun parseNeteaseFollowedArtists(raw: String): NeteaseFollowedArtistPage {
    val root = try {
        JSONObject(raw)
    } catch (error: Exception) {
        throw IOException("Invalid artist subscriptions response", error)
    }
    if (root.optInt("code", -1) != 200) throw IOException("Artist subscriptions request failed: ${root.optInt("code", -1)}")
    val data = root.optJSONArray("data") ?: throw IOException("Artist subscriptions have no data array")
    val hasMore = root.opt("hasMore") as? Boolean ?: throw IOException("Artist subscriptions have no pagination state")
    if (hasMore && data.length() == 0) throw IOException("Artist subscriptions pagination did not advance")
    return NeteaseFollowedArtistPage(
        artists = parseUniqueFollowedArtists(data),
        hasMore = hasMore,
        rawCount = data.length()
    )
}

private fun parseUniqueFollowedArtists(data: JSONArray): List<NeteaseFollowedArtist> {
    val artists = linkedMapOf<Long, NeteaseFollowedArtist>()
    data.mapObjectsNotNull(::parseFollowedArtist).forEach { artist ->
        artists.putIfAbsent(artist.id, artist)
    }
    return artists.values.toList()
}

private fun parseFollowedArtist(artist: JSONObject): NeteaseFollowedArtist? {
    val id = artist.optLong("id", 0L)
    val name = artist.optString("name", "").trim()
    if (id <= 0L || name.isBlank()) return null
    return NeteaseFollowedArtist(
        id = id,
        name = name,
        coverUrl = followedArtistCover(artist),
        musicSize = artist.optInt("musicSize", 0).coerceAtLeast(0),
        alias = followedArtistAlias(artist)
    )
}

private fun followedArtistCover(artist: JSONObject): String? {
    val cover = listOf("picUrl", "img1v1Url", "cover")
        .firstNotNullOfOrNull { key -> artist.optString(key, "").trim().takeIf { it.isNotBlank() } }
        ?: return null
    return if (cover.startsWith("http://")) "https://${cover.removePrefix("http://")}" else cover
}

private fun followedArtistAlias(artist: JSONObject): String? {
    val aliases = artist.optJSONArray("alias") ?: return null
    val names = linkedSetOf<String>()
    var index = 0
    while (index < aliases.length()) {
        val name = aliases.optString(index).trim()
        if (name.isNotBlank()) names.add(name)
        index += 1
    }
    return names.joinToString(" / ").takeIf { it.isNotBlank() }
}
