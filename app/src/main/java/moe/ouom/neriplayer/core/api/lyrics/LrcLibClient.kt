package moe.ouom.neriplayer.core.api.lyrics

/*
 * LRCLIB 歌词 API 客户端
 * https://lrclib.net — 免费开源的同步歌词数据库
 * 无需 API Key，支持按歌曲名+艺术家+时长精确匹配
 */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.util.NPLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class LrcLibResult(
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val trackName: String = "",
    val artistName: String = ""
)

class LrcLibClient(private val okHttpClient: OkHttpClient) {

    companion object {
        private const val TAG = "LrcLibClient"
        private const val BASE_URL = "https://lrclib.net/api"
        private const val USER_AGENT = "NeriPlayer/1.0 (https://github.com/cwuom/NeriPlayer)"
    }

    /**
     * 通过歌曲名、艺术家和时长精确获取歌词。
     * @param trackName 歌曲名
     * @param artistName 艺术家名
     * @param durationSeconds 歌曲时长（秒）
     * @return LrcLibResult 或 null（未找到）
     */
    suspend fun getLyrics(
        trackName: String,
        artistName: String,
        durationSeconds: Int
    ): LrcLibResult? = withContext(Dispatchers.IO) {
        try {
            val encodedTrack = URLEncoder.encode(trackName, "UTF-8")
            val encodedArtist = URLEncoder.encode(artistName, "UTF-8")
            val url = "$BASE_URL/get?track_name=$encodedTrack&artist_name=$encodedArtist&duration=$durationSeconds"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                NPLogger.d(TAG, "LRCLIB get returned ${response.code} for '$trackName' by '$artistName'")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            LrcLibResult(
                syncedLyrics = json.optString("syncedLyrics").takeIf { it.isNotBlank() },
                plainLyrics = json.optString("plainLyrics").takeIf { it.isNotBlank() },
                trackName = json.optString("trackName"),
                artistName = json.optString("artistName")
            )
        } catch (e: Exception) {
            NPLogger.d(TAG, "LRCLIB getLyrics failed: ${e.message}")
            null
        }
    }

    /**
     * 通过关键词搜索歌词（模糊匹配），返回第一个匹配结果。
     * @param query 搜索关键词（歌曲名+艺术家）
     * @return LrcLibResult 或 null
     */
    suspend fun searchLyrics(query: String): LrcLibResult? = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$BASE_URL/search?q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                NPLogger.d(TAG, "LRCLIB search returned ${response.code} for '$query'")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val arr = org.json.JSONArray(body)
            if (arr.length() == 0) return@withContext null

            // 优先选择有同步歌词的结果
            for (i in 0 until arr.length()) {
                val json = arr.optJSONObject(i) ?: continue
                val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() }
                if (synced != null) {
                    return@withContext LrcLibResult(
                        syncedLyrics = synced,
                        plainLyrics = json.optString("plainLyrics").takeIf { it.isNotBlank() },
                        trackName = json.optString("trackName"),
                        artistName = json.optString("artistName")
                    )
                }
            }

            // 没有同步歌词，返回第一个有纯文本歌词的
            val first = arr.optJSONObject(0) ?: return@withContext null
            LrcLibResult(
                syncedLyrics = null,
                plainLyrics = first.optString("plainLyrics").takeIf { it.isNotBlank() },
                trackName = first.optString("trackName"),
                artistName = first.optString("artistName")
            )
        } catch (e: Exception) {
            NPLogger.d(TAG, "LRCLIB searchLyrics failed: ${e.message}")
            null
        }
    }
}
