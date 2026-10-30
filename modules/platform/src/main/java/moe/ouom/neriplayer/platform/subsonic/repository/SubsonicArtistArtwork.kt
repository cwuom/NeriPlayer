package moe.ouom.neriplayer.platform.subsonic.repository

import java.util.Locale
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONException
import org.json.JSONObject

private val ARTIST_IMAGE_CREDENTIAL_PARAMETERS = setOf(
    "u", "p", "t", "s", "user", "username", "password", "passwd", "token", "access_token",
    "apikey", "api_key", "authorization", "auth", "credentials", "secret", "salt", "signature"
)

internal fun publicArtistImageUrl(raw: String?): String? {
    val url = raw?.trim()?.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
        url.host == ServerSongRef.RESOURCE_HOST ||
        url.queryParameterNames.any { it.lowercase(Locale.ROOT) in ARTIST_IMAGE_CREDENTIAL_PARAMETERS }) return null
    return url.toString()
}

internal fun songArtistArtwork(profileId: String, rows: List<JSONObject>): Map<String, String> = buildMap {
    for (row in rows) {
        val coverArt = (row.opt("coverArt") as? String)?.takeIf { it.isNotBlank() && it != "null" } ?: continue
        val cover = ServerSongRef(profileId, row.getString("id")).resourceUrl("cover", coverArt)
        val ids = linkedSetOf<String>()
        for (field in listOf("artistId", "albumArtistId")) {
            (row.opt(field) as? String)?.takeIf { it.isNotEmpty() }?.let(ids::add)
        }
        for (field in listOf("artists", "albumArtists")) {
            val artists = row.optJSONArray(field) ?: continue
            for (index in 0 until artists.length()) {
                (artists.optJSONObject(index)?.opt("id") as? String)?.takeIf { it.isNotEmpty() }?.let(ids::add)
            }
        }
        ids.forEach { put(it, cover) }
    }
}

internal fun encodeArtistArtwork(artwork: Map<String, String>?): String? = artwork?.let {
    JSONObject().put("version", 1).put("artistArtwork", JSONObject(it)).toString()
}

internal fun decodeArtistArtwork(raw: String?): Map<String, String>? {
    if (raw == null) return null
    return try {
        val json = JSONObject(raw)
        if (json.optInt("version") != 1) return null
        val artwork = json.optJSONObject("artistArtwork") ?: return null
        buildMap {
            for (id in artwork.keys()) {
                val cover = artwork.opt(id) as? String ?: return null
                if (id.isEmpty() || cover.isBlank()) return null
                put(id, cover)
            }
        }
    } catch (_: JSONException) { null }
}

internal fun projectArtistArtwork(
    input: ServerArtist,
    root: ServerArtist?,
    detail: ServerArtist?,
    artwork: Map<String, String>?
): ServerArtist = (detail ?: root ?: input).copy(
    coverUrl = if (detail != null || root != null) detail?.coverUrl ?: root?.coverUrl else input.coverUrl,
    fallbackCoverUrl = artwork?.get(input.id)
)
