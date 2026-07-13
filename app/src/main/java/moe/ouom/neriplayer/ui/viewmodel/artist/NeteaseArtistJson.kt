package moe.ouom.neriplayer.ui.viewmodel.artist

import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import org.json.JSONArray
import org.json.JSONObject

internal fun parseNeteaseArtistSummaries(array: JSONArray?): List<NeteaseArtistSummary> {
    if (array == null) return emptyList()
    val artists = ArrayList<NeteaseArtistSummary>(array.length())
    for (index in 0 until array.length()) {
        val obj = array.optJSONObject(index) ?: continue
        val id = obj.optLong("id", 0L)
        val name = obj.optString("name", "").trim()
        if (id > 0L && name.isNotBlank()) {
            artists.add(NeteaseArtistSummary(id = id, name = name))
        }
    }
    return artists
}

internal fun parseNeteaseArtistsFromSongJson(song: JSONObject?): List<NeteaseArtistSummary> {
    if (song == null) return emptyList()
    return parseNeteaseArtistSummaries(song.optJSONArray("ar"))
        .ifEmpty { parseNeteaseArtistSummaries(song.optJSONArray("artists")) }
}

internal fun parseNeteaseArtistsFromSongDetail(raw: String): List<NeteaseArtistSummary> {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) return emptyList()
    val song = root.optJSONArray("songs")?.optJSONObject(0) ?: return emptyList()
    return parseNeteaseArtistsFromSongJson(song)
}
