package moe.ouom.neriplayer.core.api.lyrics

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.api.lyrics/LrcLibClient
 * Updated: 2026/3/23
 */

/*
 * LRCLIB 歌词 API 客户端
 * https://lrclib.net - 免费开源的同步歌词数据库
 * 无需 API Key, 支持按歌曲名+艺术家+时长精确匹配
 */

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.logging.NPLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer

data class LrcLibResult(
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val trackName: String = "",
    val artistName: String = "",
    val durationSeconds: Long? = null
)

class LrcLibClient(private val okHttpClient: OkHttpClient) {

    companion object {
        private const val TAG = "LrcLibClient"
        private const val BASE_URL = "https://lrclib.net/api"
        private const val USER_AGENT = "NeriPlayer/1.0 (https://github.com/cwuom/NeriPlayer)"
    }

    /**
     * 通过歌曲名, 艺术家和时长精确获取歌词
     * @param trackName 歌曲名
     * @param artistName 艺术家名
     * @param durationSeconds 歌曲时长 (秒)
     * @return LrcLibResult 或 null (未找到)
     */
    suspend fun getLyrics(
        trackName: String,
        artistName: String,
        durationSeconds: Long
    ): LrcLibResult? = withContext(Dispatchers.IO) {
        if (durationSeconds <= 0L) return@withContext null
        try {
            val encodedTrack = URLEncoder.encode(trackName, "UTF-8")
            val encodedArtist = URLEncoder.encode(artistName, "UTF-8")
            val url = "$BASE_URL/get?track_name=$encodedTrack&artist_name=$encodedArtist&duration=$durationSeconds"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    NPLogger.d(TAG, "LRCLIB get returned ${response.code} for '$trackName' by '$artistName'")
                    return@withContext null
                }

                val body = response.body.string()
                val json = JSONObject(body)

                parseResult(json)?.takeIf {
                    isLrcLibResultCompatible(
                        result = it,
                        trackName = trackName,
                        artistName = artistName,
                        durationSeconds = durationSeconds
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NPLogger.d(TAG, "LRCLIB getLyrics failed: ${e.message}")
            null
        }
    }

    /**
     * 通过歌曲名、艺术家和时长搜索歌词，只返回元数据全部匹配的结果
     * @param trackName 歌曲名
     * @param artistName 艺术家名
     * @param durationSeconds 歌曲时长（秒）
     * @return LrcLibResult 或 null
     */
    suspend fun searchLyrics(
        trackName: String,
        artistName: String,
        durationSeconds: Long
    ): LrcLibResult? = withContext(Dispatchers.IO) {
        if (durationSeconds <= 0L) return@withContext null
        try {
            val query = "$trackName $artistName".trim()
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$BASE_URL/search?q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    NPLogger.d(TAG, "LRCLIB search returned ${response.code} for '$query'")
                    return@withContext null
                }

                val body = response.body.string()
                val arr = org.json.JSONArray(body)
                buildList {
                    for (index in 0 until arr.length()) {
                        val result = arr.optJSONObject(index)
                            ?.let(::parseResult)
                            ?: continue
                        if (
                            isLrcLibResultCompatible(
                                result = result,
                                trackName = trackName,
                                artistName = artistName,
                                durationSeconds = durationSeconds
                            )
                        ) {
                            add(result)
                        }
                    }
                }.sortedWith(
                    compareByDescending<LrcLibResult> { !it.syncedLyrics.isNullOrBlank() }
                        .thenBy { absDurationDeltaSeconds(it.durationSeconds, durationSeconds) }
                ).firstOrNull()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NPLogger.d(TAG, "LRCLIB searchLyrics failed: ${e.message}")
            null
        }
    }

    suspend fun searchLyricsCandidates(keyword: String): List<LrcLibResult> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        try {
            val encodedQuery = URLEncoder.encode(keyword, "UTF-8")
            val url = "$BASE_URL/search?q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    NPLogger.d(TAG, "LRCLIB candidate search returned ${response.code} for '$keyword'")
                    return@withContext emptyList()
                }

                val body = response.body.string()
                val arr = org.json.JSONArray(body)
                buildList {
                    for (index in 0 until arr.length()) {
                        val result = arr.optJSONObject(index)
                            ?.let(::parseResult)
                            ?: continue
                        if (!result.syncedLyrics.isNullOrBlank() || !result.plainLyrics.isNullOrBlank()) {
                            add(result)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NPLogger.d(TAG, "LRCLIB searchLyricsCandidates failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseResult(json: JSONObject): LrcLibResult? {
        val duration = json.optDouble("duration", Double.NaN)
            .takeIf { it > 0.0 && !it.isNaN() && !it.isInfinite() }
            ?.toLong()
            ?: return null
        return LrcLibResult(
            syncedLyrics = json.optString("syncedLyrics").takeIf { it.isNotBlank() },
            plainLyrics = json.optString("plainLyrics").takeIf { it.isNotBlank() },
            trackName = json.optString("trackName"),
            artistName = json.optString("artistName"),
            durationSeconds = duration
        )
    }
}

internal fun isLrcLibResultCompatible(
    result: LrcLibResult,
    trackName: String,
    artistName: String,
    durationSeconds: Long
): Boolean {
    val normalizedTrackName = normalizeLrcLibMatchText(trackName)
    val normalizedArtistNames = normalizeLrcLibArtistNames(artistName)
    val candidateTrackName = normalizeLrcLibMatchText(result.trackName)
    val candidateArtistNames = normalizeLrcLibArtistNames(result.artistName)
    val candidateDurationSeconds = result.durationSeconds ?: return false
    return normalizedTrackName.isNotBlank() &&
        normalizedArtistNames.isNotEmpty() &&
        candidateTrackName == normalizedTrackName &&
        candidateArtistNames == normalizedArtistNames &&
        isExternalLyricDurationCompatible(
            expectedDurationMs = durationSeconds * 1_000L,
            candidateDurationMs = candidateDurationSeconds * 1_000L
        )
}

private fun normalizeLrcLibMatchText(value: String): String {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

private fun normalizeLrcLibArtistNames(value: String): Set<String> {
    return value.split(
        Regex(
            "[/,，、&+]|\\b(?:feat\\.?|ft\\.?|featuring)\\b|\\s+[xX]\\s+",
            RegexOption.IGNORE_CASE
        )
    ).asSequence()
        .map(::normalizeLrcLibMatchText)
        .filter { it.isNotBlank() }
        .toSet()
}

private fun absDurationDeltaSeconds(candidateDuration: Long?, expectedDuration: Long): Long {
    return candidateDuration?.let { kotlin.math.abs(it - expectedDuration) } ?: Long.MAX_VALUE
}
