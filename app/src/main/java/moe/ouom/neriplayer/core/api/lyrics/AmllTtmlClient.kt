package moe.ouom.neriplayer.core.api.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.logging.NPLogger
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

data class AmllTtmlLyrics(
    val lyrics: String,
    val file: String,
    val title: String = "",
    val artists: List<String> = emptyList(),
    val album: String = ""
)

data class AmllTtmlSearchResult(
    val file: String,
    val title: String,
    val titles: List<String>,
    val artist: String,
    val artists: List<String>,
    val albums: List<String>,
    val ncmIds: List<String>,
    val qqIds: List<String>,
    val score: Int
)

private const val MIN_AMLL_ARTIST_MATCH_SCORE = 30

class AmllTtmlClient(
    private val okHttpClient: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL
) {
    private val rootUrl = baseUrl.trimEnd('/').toHttpUrl()

    suspend fun searchLyrics(
        trackName: String,
        artistName: String
    ): List<AmllTtmlSearchResult> = withContext(Dispatchers.IO) {
        val query = trackName.trim().takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        val requestBody = JSONObject()
            .put("query", query)
            .put("type", "title")
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(rootUrl.newBuilder().addPathSegments("api/search-lyrics").build())
            .header("User-Agent", USER_AGENT)
            .post(requestBody)
            .build()

        val body = executeString(request) ?: return@withContext emptyList()
        val results = runCatching {
            parseSearchResults(body)
        }.onFailure { error ->
            NPLogger.d(TAG, "AMLL search response parse failed: ${error.message}")
        }.getOrDefault(emptyList())
        results
            .filter { result ->
                scoreAmllSearchResult(
                    trackName = trackName,
                    artistName = artistName,
                    result = result
                ) >= MIN_SEARCH_MATCH_SCORE
            }
            .sortedWith(
                compareByDescending<AmllTtmlSearchResult> {
                    scoreAmllSearchResult(trackName, artistName, it)
                }.thenByDescending { it.score }
            )
    }

    suspend fun getLyrics(searchResult: AmllTtmlSearchResult): AmllTtmlLyrics? {
        val rawLyrics = fetchRawLyrics(searchResult.file) ?: return null
        return AmllTtmlLyrics(
            lyrics = rawLyrics,
            file = searchResult.file,
            title = searchResult.title,
            artists = searchResult.artists,
            album = searchResult.albums.firstOrNull().orEmpty()
        )
    }

    private suspend fun fetchRawLyrics(file: String): String? {
        val safeFile = file.trim().takeIf { it.endsWith(".ttml") && '/' !in it } ?: return null
        return executeString(
            Request.Builder()
                .url(rootUrl.newBuilder().addPathSegment("raw-lyrics").addPathSegment(safeFile).build())
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        )
    }

    private suspend fun executeString(request: Request): String? = withContext(Dispatchers.IO) {
        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    NPLogger.d(TAG, "AMLL request returned ${response.code} for ${request.url.redactedForLog()}")
                    return@withContext null
                }
                response.body?.string()?.takeIf { it.isNotBlank() }
            }
        } catch (error: Exception) {
            NPLogger.d(TAG, "AMLL request failed: ${error.message}")
            null
        }
    }

    private fun parseSearchResults(body: String): List<AmllTtmlSearchResult> {
        val array = JSONArray(body)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val file = item.optString("file").takeIf { it.endsWith(".ttml") } ?: continue
                add(
                    AmllTtmlSearchResult(
                        file = file,
                        title = item.optString("title"),
                        titles = item.optStringArray("titles"),
                        artist = item.optString("artist"),
                        artists = item.optStringArray("artists"),
                        albums = item.optStringArray("albums"),
                        ncmIds = item.optStringArray("ncmIds"),
                        qqIds = item.optStringArray("qqIds"),
                        score = item.optInt("score")
                    )
                )
            }
        }
    }

    private fun JSONObject.optStringArray(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun HttpUrl.redactedForLog(): String {
        return newBuilder().query(null).build().toString()
    }

    companion object {
        private const val TAG = "AmllTtmlClient"
        private const val DEFAULT_BASE_URL = "https://amlldb.bikonoo.com"
        private const val USER_AGENT = "NeriPlayer/1.0 (https://github.com/cwuom/NeriPlayer)"
        private const val MIN_SEARCH_MATCH_SCORE = 70
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun scoreAmllSearchResult(
    trackName: String,
    artistName: String,
    result: AmllTtmlSearchResult
): Int {
    val requestedTitle = normalizeAmllSearchText(trackName)
    val requestedArtists = splitAmllArtists(artistName)
    if (requestedTitle.isBlank()) return 0

    val titleScore = result.titles
        .ifEmpty { listOf(result.title) }
        .maxOfOrNull { candidate ->
            val normalized = normalizeAmllSearchText(candidate)
            when {
                normalized.isBlank() -> 0
                normalized == requestedTitle -> 90
                normalized.startsWith("$requestedTitle ") -> 78
                normalized.contains(requestedTitle) || requestedTitle.contains(normalized) -> 64
                else -> tokenOverlapScore(requestedTitle, normalized) * 6
            }
        } ?: 0

    val artistScore = if (requestedArtists.isEmpty()) {
        0
    } else {
        result.artists
            .ifEmpty { listOf(result.artist) }
            .maxOfOrNull { candidate ->
                val normalized = normalizeAmllSearchText(candidate)
                requestedArtists.maxOf { requested ->
                    when {
                        normalized.isBlank() -> 0
                        normalized == requested -> 55
                        normalized.contains(requested) || requested.contains(normalized) -> 40
                        else -> tokenOverlapScore(requested, normalized) * 8
                    }
                }
            } ?: 0
    }

    if (requestedArtists.isNotEmpty() && artistScore < MIN_AMLL_ARTIST_MATCH_SCORE) {
        return 0
    }
    return titleScore + artistScore
}

internal fun isAmllDurationCompatible(
    expectedDurationMs: Long,
    candidateDurationMs: Long
): Boolean {
    if (expectedDurationMs <= 0L || candidateDurationMs <= 0L) return true
    val deltaMs = candidateDurationMs - expectedDurationMs
    // TTML 末行常早于音频结束，片尾空白和 outro 要比超长候选更宽容
    val toleranceMs = if (deltaMs < 0L) {
        max(30_000L, (expectedDurationMs * 15L) / 100L).coerceAtMost(60_000L)
    } else {
        max(12_000L, expectedDurationMs / 10L).coerceAtMost(30_000L)
    }
    return abs(deltaMs) <= toleranceMs
}

internal fun normalizeAmllSearchText(value: String): String {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace("&", " and ")
        .replace(Regex("""\b(feat|ft|featuring)\.?\b"""), " ")
        .replace(Regex("""[(){}\[\]【】（）]"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")
}

private fun splitAmllArtists(value: String): List<String> {
    return value.split(Regex("""[/,，、&+]|(?:\s+x\s+)""", RegexOption.IGNORE_CASE))
        .map(::normalizeAmllSearchText)
        .filter { it.isNotBlank() }
}

private fun tokenOverlapScore(left: String, right: String): Int {
    val leftTokens = left.split(' ').filter { it.isNotBlank() }.toSet()
    val rightTokens = right.split(' ').filter { it.isNotBlank() }.toSet()
    if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0
    return leftTokens.intersect(rightTokens).size
}
