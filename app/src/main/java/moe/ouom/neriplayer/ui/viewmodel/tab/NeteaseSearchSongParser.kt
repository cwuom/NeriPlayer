package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.platform.netease.mapping.parseNeteaseSearchPlaylists
import moe.ouom.neriplayer.data.platform.netease.mapping.parseNeteaseSearchSongs
import moe.ouom.neriplayer.util.json.mapObjectsNotNull
import org.json.JSONObject

internal data class ParsedNeteaseSearchResult(
    val items: List<ExploreSearchResult>,
    val totalCount: Int?
)

internal fun parseNeteaseSearchResults(
    raw: String,
    type: NeteaseExploreSearchType
): ParsedNeteaseSearchResult {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) {
        return ParsedNeteaseSearchResult(items = emptyList(), totalCount = null)
    }

    val result = root.optJSONObject("result")
        ?: return ParsedNeteaseSearchResult(items = emptyList(), totalCount = null)

    return when (type) {
        NeteaseExploreSearchType.SONG -> ParsedNeteaseSearchResult(
            items = parseNeteaseSearchSongs(raw).map { ExploreSearchResult.Song(it) },
            totalCount = result.optIntOrNull("songCount")
        )
        NeteaseExploreSearchType.PLAYLIST -> ParsedNeteaseSearchResult(
            items = parseNeteaseSearchPlaylists(result).map { ExploreSearchResult.Playlist(it) },
            totalCount = result.optIntOrNull("playlistCount")
        )
        NeteaseExploreSearchType.ARTIST -> ParsedNeteaseSearchResult(
            items = parseNeteaseSearchArtists(result).map { ExploreSearchResult.Artist(it) },
            totalCount = result.optIntOrNull("artistCount")
        )
    }
}

private fun parseNeteaseSearchArtists(result: JSONObject): List<NeteaseSearchArtistResult> {
    val artists = result.optJSONArray("artists") ?: return emptyList()
    return artists.mapObjectsNotNull { artist ->
        val id = artist.optLong("id", 0L)
        val name = artist.optString("name", "")
        if (id <= 0L || name.isBlank()) return@mapObjectsNotNull null
        NeteaseSearchArtistResult(
            artist = NeteaseArtistSummary(id = id, name = name),
            picUrl = toHttps(
                artist.optString("picUrl", "")
                    .ifBlank { artist.optString("img1v1Url", "") }
            ).takeIf { it.isNotBlank() },
            musicSize = artist.optInt("musicSize", 0),
            albumSize = artist.optInt("albumSize", 0)
        )
    }
}

private fun JSONObject.optIntOrNull(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

private fun toHttps(url: String?): String {
    return url.orEmpty().replaceFirst("http://", "https://")
}
