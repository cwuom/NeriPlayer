package moe.ouom.neriplayer.platform.lyrics.api.client

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.lyrics.lrclib.LrcLibRecord
import moe.ouom.neriplayer.common.logging.NPLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class LrcLibClient(private val okHttpClient: OkHttpClient) {
    suspend fun getLyrics(
        trackName: String,
        artistName: String,
        durationSeconds: Long
    ): LrcLibRecord? = withContext(Dispatchers.IO) {
        val encodedTrack = URLEncoder.encode(trackName, "UTF-8")
        val encodedArtist = URLEncoder.encode(artistName, "UTF-8")
        val body = executeString(
            "$BASE_URL/get?track_name=$encodedTrack&artist_name=$encodedArtist&duration=$durationSeconds"
        ) ?: return@withContext null
        parseLrcLibRecord(JSONObject(body))
    }

    suspend fun searchLyrics(keyword: String): List<LrcLibRecord> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(keyword, "UTF-8")
        val body = executeString("$BASE_URL/search?q=$encodedQuery") ?: return@withContext emptyList()
        val array = JSONArray(body)
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::parseLrcLibRecord)?.let(::add)
            }
        }
    }

    private fun executeString(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                NPLogger.d(TAG, "LRCLIB request returned ${response.code} for ${request.url.encodedPath}")
                null
            } else {
                response.body.string()
            }
        }
    }

    private companion object {
        const val TAG = "LrcLibClient"
        const val BASE_URL = "https://lrclib.net/api"
        const val USER_AGENT = "NeriPlayer/1.0 (https://github.com/cwuom/NeriPlayer)"
    }
}

private fun parseLrcLibRecord(json: JSONObject): LrcLibRecord? {
    val duration = json.optDouble("duration", Double.NaN)
        .takeIf { it > 0.0 && !it.isNaN() && !it.isInfinite() }
        ?.toLong()
        ?: return null
    return LrcLibRecord(
        syncedLyrics = json.optString("syncedLyrics").takeIf { it.isNotBlank() },
        plainLyrics = json.optString("plainLyrics").takeIf { it.isNotBlank() },
        trackName = json.optString("trackName"),
        artistName = json.optString("artistName"),
        durationSeconds = duration
    )
}
