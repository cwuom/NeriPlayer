package moe.ouom.neriplayer.ui.viewmodel.artist

import org.json.JSONArray

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
