package moe.ouom.neriplayer.platform.netease.mapping

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.json.mapObjectsNotNull
import org.json.JSONArray
import org.json.JSONObject

fun parseNeteaseSearchSongs(raw: String): List<SongItem> {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) return emptyList()

    val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
    return parseNeteaseSongArray(songs)
}

fun parseNeteaseSongDetail(raw: String): SongItem? {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) return null
    val songs = root.optJSONArray("songs") ?: return null
    return parseNeteaseSongArray(songs).firstOrNull()
}

private fun parseNeteaseSongArray(songs: JSONArray): List<SongItem> {
    return songs.mapObjectsNotNull(::parseNeteaseSong)
}

private fun parseNeteaseSong(song: JSONObject): SongItem? {
    val songId = song.optLong("id", 0L)
    val name = song.optString("name", "")
    if (songId <= 0L || name.isBlank()) return null

    val artistItems = parseNeteaseArtistsFromSongJson(song)
    val album = song.optJSONObject("al") ?: song.optJSONObject("album")
    val albumName = album?.optString("name", "").orEmpty()
    return SongItem(
        id = songId,
        name = name,
        artist = artistItems.joinToString(" / ") { it.name },
        album = albumName,
        albumId = album?.optLong("id", 0L) ?: 0L,
        durationMs = song.optLong("dt", 0L),
        coverUrl = parseNeteaseAlbumCover(album),
        channelId = "netease",
        audioId = songId.toString(),
        neteaseArtists = artistItems
    )
}

private fun parseNeteaseAlbumCover(album: JSONObject?): String? {
    return album?.optString("picUrl", "")
        ?.replaceFirst("http://", "https://")
        ?.takeIf { it.isNotBlank() }
}
