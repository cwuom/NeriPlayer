package moe.ouom.neriplayer.api.search.client

import android.annotation.SuppressLint
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.api.netease.client.NeteaseClient
import moe.ouom.neriplayer.api.search.SearchApi
import moe.ouom.neriplayer.api.search.model.CloudMusicLyricResponse
import moe.ouom.neriplayer.api.search.model.CloudMusicSearchResponse
import moe.ouom.neriplayer.api.search.model.CloudMusicSongDetailResponse
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.lyrics.normalizeLegacyLrcTimestamps
import moe.ouom.neriplayer.core.model.music.MusicPlatform
import moe.ouom.neriplayer.core.model.music.SongDetails
import moe.ouom.neriplayer.core.model.music.SongSearchInfo
import moe.ouom.neriplayer.util.network.awaitResponse
import okhttp3.OkHttpClient
import okhttp3.Request

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
 * File: moe.ouom.neriplayer.api.search.client/CloudMusicSearchApi
 * Created: 2025/8/17
 */

class CloudMusicSearchApi(
    private val neteaseClient: NeteaseClient,
    private val client: OkHttpClient,
    private val debugLogging: Boolean = false
) : SearchApi {

    companion object {
        private const val TAG = "CloudMusicSearchApi"
        private const val DEBUG_JSON_PREVIEW_MAX_CHARS = 512
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(keyword: String, page: Int): List<SongSearchInfo> {
        return withContext(Dispatchers.IO) {
            val offset = (page - 1).coerceAtLeast(0) * 20
            val responseJson = neteaseClient.searchSongsCancellable(
                keyword = keyword,
                limit = 20,
                offset = offset,
                usePersistedCookies = false
            )
            logResponseSummary(label = "netease-search", json = responseJson)

            val searchResponse = json.decodeFromString<CloudMusicSearchResponse>(responseJson)

            searchResponse.result?.songs?.map { song ->
                SongSearchInfo(
                    id = song.id.toString(),
                    songName = song.name,
                    singer = song.artists.joinToString("/") { it.name },
                    duration = formatDuration(song.duration / 1000),
                    source = MusicPlatform.CLOUD_MUSIC,
                    albumName = song.album.name,
                    coverUrl = song.album.picUrl
                )
            } ?: emptyList()
        }
    }

    override suspend fun getSongInfo(id: String): SongDetails {
        return withContext(Dispatchers.IO) {
            val songDetailUrl = "https://music.163.com/api/song/detail/?id=${id}&ids=[${id}]"
            val songInfoJson = executeRequest(songDetailUrl) as String
            val songData = json.decodeFromString<CloudMusicSongDetailResponse>(songInfoJson).songs.firstOrNull()
                ?: throw IOException("找不到ID为 $id 的歌曲")

            val (lyric, translatedLyric) = try {
                fetchLyrics(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(TAG, "网易云歌词获取失败，继续使用歌曲详情: ${error.message.orEmpty()}")
                null to null
            }
            SongDetails(
                id = id,
                songName = songData.name,
                singer = songData.artists.joinToString("/") { it.name },
                album = songData.album.name,
                coverUrl = songData.album.picUrl,
                lyric = lyric,
                translatedLyric = translatedLyric
            )
        }
    }

    private suspend fun fetchLyrics(id: String): Pair<String?, String?> {
        val lyricUrl =
            "https://music.163.com/api/song/lyric?id=${id}&lv=-1&tv=-1&rv=-1&yv=-1&ytv=-1&yrv=-1"
        val lyricJson = executeRequest(lyricUrl) as String
        val lyricResponse = json.decodeFromString<CloudMusicLyricResponse>(lyricJson)
        return Pair(
            lyricResponse.yrc?.lyric?.takeIf(String::isNotBlank)
                ?: lyricResponse.lrc?.lyric?.let(::normalizeLegacyLrcTimestamps),
            lyricResponse.ytlrc?.lyric?.takeIf(String::isNotBlank)
                ?: lyricResponse.tlyric?.lyric?.let(::normalizeLegacyLrcTimestamps)
        )
    }

    @Throws(IOException::class)
    private suspend fun executeRequest(url: String, asBytes: Boolean = false): Any {
        val cookies = neteaseClient.getCookies()
        val cookieString = cookies.map { "${it.key}=${it.value}" }.joinToString("; ")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36")
            .header("Referer", "https://music.163.com/")
            .header("Cookie", cookieString)
            .build()

        return client.newCall(request).awaitResponse { response ->
            if (!response.isSuccessful) throw IOException("请求失败: ${response.code} for url: $url")
            val body = response.body

            if (asBytes) {
                body.bytes()
            } else {
                val jsonString = body.string()
                logResponseSummary(label = request.url.encodedPath, json = jsonString)
                jsonString
            }
        }
    }

    private fun logResponseSummary(label: String, json: String) {
        val preview = json
            .replace(Regex("\\s+"), " ")
            .take(DEBUG_JSON_PREVIEW_MAX_CHARS)
        if (debugLogging) {
            NPLogger.d(TAG, "Response label=$label, length=${json.length}, preview=$preview")
            return
        }
        NPLogger.d(TAG, "Response received: labelHash=${label.hashCode()}, length=${json.length}")
    }

    @SuppressLint("DefaultLocale")
    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }
}
