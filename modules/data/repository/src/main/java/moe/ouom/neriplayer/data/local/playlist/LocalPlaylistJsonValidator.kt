package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal fun validateLocalPlaylistJson(text: String, source: String) {
    val root = JsonParser.parseString(text)
    require(root.isJsonArray) { "Playlist $source root must be an array" }
    root.asJsonArray.forEach { element ->
        require(element.isJsonObject) { "Playlist $source contains a non-object entry" }
        validatePlaylistObject(element.asJsonObject, source)
    }
}

private fun validatePlaylistObject(playlist: JsonObject, source: String) {
    require(playlist.get("id")?.asJsonPrimitive?.isNumber == true) {
        "Playlist $source entry is missing numeric id"
    }
    require(playlist.get("name")?.asJsonPrimitive?.isString == true) {
        "Playlist $source entry is missing name"
    }
    require(playlist.get("songs")?.isJsonArray == true) {
        "Playlist $source entry is missing songs"
    }
    playlist.getAsJsonArray("songs").forEach { songElement ->
        require(songElement.isJsonObject) {
            "Playlist $source contains an invalid song entry"
        }
        validateSongObject(songElement.asJsonObject, source)
    }
}

private fun validateSongObject(song: JsonObject, source: String) {
    require(song.get("id")?.asJsonPrimitive?.isNumber == true) {
        "Playlist $source song is missing numeric id"
    }
    require(song.get("name")?.asJsonPrimitive?.isString == true) {
        "Playlist $source song is missing name"
    }
    require(song.get("artist")?.asJsonPrimitive?.isString == true) {
        "Playlist $source song is missing artist"
    }
    require(song.get("album")?.asJsonPrimitive?.isString == true) {
        "Playlist $source song is missing album"
    }
    require(song.get("albumId")?.asJsonPrimitive?.isNumber == true) {
        "Playlist $source song is missing numeric albumId"
    }
    require(song.get("durationMs")?.asJsonPrimitive?.isNumber == true) {
        "Playlist $source song is missing numeric durationMs"
    }
}
