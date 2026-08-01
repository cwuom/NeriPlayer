package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.ui.viewmodel.artist.parseNeteaseArtistSummaries
import org.json.JSONObject

internal data class ParsedNeteaseSearchResult(
    val items: List<ExploreSearchResult>,
    val totalCount: Int?
)

internal fun parseNeteaseSearchSongs(raw: String): List<SongItem> {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) return emptyList()

    val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
    return parseNeteaseSongArray(songs)
}

internal fun parseNeteaseSongDetail(raw: String): SongItem? {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) return null
    val songs = root.optJSONArray("songs") ?: return null
    return parseNeteaseSongArray(songs).firstOrNull()
}

private fun parseNeteaseSongArray(songs: org.json.JSONArray): List<SongItem> {
    return buildList(songs.length()) {
        for (index in 0 until songs.length()) {
            val song = songs.optJSONObject(index) ?: continue
            val songId = song.optLong("id", 0L)
            val name = song.optString("name", "")
            if (songId <= 0L || name.isBlank()) continue

            val artistItems = parseNeteaseArtistSummaries(song.optJSONArray("ar"))
                .ifEmpty { parseNeteaseArtistSummaries(song.optJSONArray("artists")) }
            val album = song.optJSONObject("al") ?: song.optJSONObject("album")
            val albumName = album?.optString("name", "").orEmpty()
            add(
                SongItem(
                    id = songId,
                    name = name,
                    artist = artistItems.joinToString(" / ") { it.name },
                    album = albumName,
                    albumId = album?.optLong("id", 0L) ?: 0L,
                    durationMs = song.optLong("dt", 0L),
                    coverUrl = album?.optString("picUrl", "")
                        ?.replaceFirst("http://", "https://")
                        ?.takeIf { it.isNotBlank() },
                    channelId = "netease",
                    audioId = songId.toString(),
                    neteaseArtists = artistItems
                )
            )
        }
    }
}

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

private fun parseNeteaseSearchPlaylists(result: JSONObject): List<PlaylistSummary> {
    val playlists = result.optJSONArray("playlists") ?: return emptyList()
    return buildList(playlists.length()) {
        for (index in 0 until playlists.length()) {
            val playlist = playlists.optJSONObject(index) ?: continue
            val id = playlist.optLong("id", 0L)
            val name = playlist.optString("name", "")
            if (id <= 0L || name.isBlank()) continue
            add(
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
            )
        }
    }
}

private fun parseNeteaseSearchArtists(result: JSONObject): List<NeteaseSearchArtistResult> {
    val artists = result.optJSONArray("artists") ?: return emptyList()
    return buildList(artists.length()) {
        for (index in 0 until artists.length()) {
            val artist = artists.optJSONObject(index) ?: continue
            val id = artist.optLong("id", 0L)
            val name = artist.optString("name", "")
            if (id <= 0L || name.isBlank()) continue
            add(
                NeteaseSearchArtistResult(
                    artist = NeteaseArtistSummary(id = id, name = name),
                    picUrl = toHttps(
                        artist.optString("picUrl", "")
                            .ifBlank { artist.optString("img1v1Url", "") }
                    ).takeIf { it.isNotBlank() },
                    musicSize = artist.optInt("musicSize", 0),
                    albumSize = artist.optInt("albumSize", 0)
                )
            )
        }
    }
}

private fun JSONObject.optIntOrNull(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

private fun toHttps(url: String?): String {
    return url.orEmpty().replaceFirst("http://", "https://")
}
