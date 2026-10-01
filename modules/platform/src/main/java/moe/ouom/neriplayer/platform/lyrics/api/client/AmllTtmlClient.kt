package moe.ouom.neriplayer.platform.lyrics.api.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.lyrics.amll.AmllTtmlLyrics
import moe.ouom.neriplayer.data.model.lyrics.amll.AmllTtmlSearchResult
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.network.http.awaitResponse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AmllTtmlClient(
    private val okHttpClient: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL
) {
    private val rootUrl = baseUrl.trimEnd('/').toHttpUrl()

    suspend fun searchLyrics(query: String): List<AmllTtmlSearchResult> = withContext(Dispatchers.IO) {
        val searchQuery = query.trim().takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        val requestBody = JSONObject()
            .put("query", searchQuery)
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

    private suspend fun executeString(request: Request): String? {
        return try {
            okHttpClient.newCall(request).awaitResponse { response ->
                if (!response.isSuccessful) {
                    NPLogger.d(TAG, "AMLL request returned ${response.code} for ${request.url.redactedForLog()}")
                    return@awaitResponse null
                }
                response.body.string().takeIf { it.isNotBlank() }
            }
        } catch (error: CancellationException) {
            throw error
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
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
