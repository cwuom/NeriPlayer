package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.artist.parseNeteaseArtistSummaries
import org.json.JSONObject

internal fun parseNeteaseSearchSongs(raw: String): List<SongItem> {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) return emptyList()

    val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
    return buildList(songs.length()) {
        for (index in 0 until songs.length()) {
            val song = songs.optJSONObject(index) ?: continue
            val songId = song.optLong("id", 0L)
            val name = song.optString("name", "")
            if (songId <= 0L || name.isBlank()) continue

            val artistItems = parseNeteaseArtistSummaries(song.optJSONArray("ar"))
            val album = song.optJSONObject("al")
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
