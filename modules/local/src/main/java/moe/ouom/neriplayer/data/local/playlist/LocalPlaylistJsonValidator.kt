package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

internal fun validateLocalPlaylistJson(text: String, source: String) {
    val root = JsonParser.parseString(text)
    require(root.isJsonArray) { "Playlist $source root must be an array" }
    root.asJsonArray.forEach { element ->
        require(element.isJsonObject) { "Playlist $source contains a non-object entry" }
        validatePlaylistObject(element.asJsonObject, source)
    }
}

private fun validatePlaylistObject(playlist: JsonObject, source: String) {
    requireFields(playlist, PLAYLIST_FIELDS, "Playlist $source entry")
    require(playlist.get("songs")?.isJsonArray == true) {
        "Playlist $source entry is missing songs"
    }
    playlist.getAsJsonArray("songs").forEach { songElement ->
        require(songElement.isJsonObject) {
            "Playlist $source contains an invalid song entry"
        }
        requireFields(songElement.asJsonObject, SONG_FIELDS, "Playlist $source song")
    }
}

private class RequiredJsonField(
    val name: String,
    val description: String,
    val accepts: (JsonPrimitive) -> Boolean
)

private fun requireFields(target: JsonObject, fields: List<RequiredJsonField>, owner: String) {
    fields.forEach { field ->
        require(target.get(field.name)?.asJsonPrimitive?.let(field.accepts) == true) {
            "$owner is missing ${field.description}"
        }
    }
}

private fun numericField(name: String) = RequiredJsonField(name, "numeric $name", JsonPrimitive::isNumber)

private fun textField(name: String) = RequiredJsonField(name, name, JsonPrimitive::isString)

private val PLAYLIST_FIELDS = listOf(numericField("id"), textField("name"))

private val SONG_FIELDS = listOf(
    numericField("id"),
    textField("name"),
    textField("artist"),
    textField("album"),
    numericField("albumId"),
    numericField("durationMs")
)
