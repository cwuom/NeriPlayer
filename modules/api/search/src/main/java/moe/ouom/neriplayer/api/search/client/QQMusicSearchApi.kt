package moe.ouom.neriplayer.api.search.client

import android.annotation.SuppressLint
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.api.search.NativeLyricSearchApi
import moe.ouom.neriplayer.api.search.codec.decodeQQMusicLyricPayload
import moe.ouom.neriplayer.api.search.codec.stripUntranslatedPlaceholderLines
import moe.ouom.neriplayer.api.search.model.QQMusicDetailResponse
import moe.ouom.neriplayer.api.search.model.QQMusicLyricContainer
import moe.ouom.neriplayer.api.search.model.QQMusicSearchResponse
import moe.ouom.neriplayer.api.search.model.QQMusicSongMetadata
import moe.ouom.neriplayer.api.search.model.QQMusicTrackInfo
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.model.music.MusicPlatform
import moe.ouom.neriplayer.core.model.music.SongDetails
import moe.ouom.neriplayer.core.model.music.SongSearchInfo
import moe.ouom.neriplayer.util.network.awaitResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

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
 * File: moe.ouom.neriplayer.api.search.client/QQMusicSearchApi
 * Created: 2025/8/17
 */

class QQMusicSearchApi(
    private val client: OkHttpClient,
    private val debugLogging: Boolean = false
) : NativeLyricSearchApi {

    companion object {
        private const val TAG = "QQMusicSearchApi"
        private const val DEBUG_JSON_PREVIEW_MAX_CHARS = 512
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(keyword: String, page: Int): List<SongSearchInfo> {
        return withContext(Dispatchers.IO) {
            val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp".toHttpUrl().newBuilder()
                .addQueryParameter("format", "json")
                .addQueryParameter("n", "20")
                .addQueryParameter("p", page.toString())
                .addQueryParameter("w", keyword)
                .addQueryParameter("cr", "1")
                .addQueryParameter("g_tk", "5381")
                .build()

            val responseJson = executeRequest(url.toString()) as String
            val searchResult = json.decodeFromString<QQMusicSearchResponse>(responseJson)

            searchResult.data?.song?.list?.map { song ->
                SongSearchInfo(
                    id = song.songMid,
                    songName = song.songName,
                    singer = song.singer.joinToString("/") { it.name },
                    duration = formatDuration(song.interval),
                    source = MusicPlatform.QQ_MUSIC,
                    albumName = song.albumName,
                    coverUrl = song.albumMid?.let { "https://y.qq.com/music/photo_new/T002R800x800M000$it.jpg" }
                )
            } ?: emptyList()
        }
    }

    override suspend fun getSongInfo(id: String): SongDetails = getNativeSongInfo(id)

    suspend fun getSongMetadata(id: String): QQMusicSongMetadata = withContext(Dispatchers.IO) {
        val song = fetchSongData(id)
        QQMusicSongMetadata(
            details = song.toSongDetails(lyric = null, translatedLyric = null),
            durationMs = song.interval.takeIf { it > 0L }?.times(1000L) ?: 0L
        )
    }

    override suspend fun getNativeSongInfo(id: String): SongDetails {
        return withContext(Dispatchers.IO) {
            val songData = fetchSongData(id)
            val (lyric, translatedLyric) = getNativeLyrics(id)
            songData.toSongDetails(
                lyric = lyric,
                translatedLyric = translatedLyric
            )
        }
    }

    private suspend fun fetchSongData(id: String): QQMusicTrackInfo {
        val detailRequestData = JSONObject().put(
            "songinfo", JSONObject()
                .put("method", "get_song_detail_yqq")
                .put("module", "music.pf_song_detail_svr")
                .put("param", JSONObject().put("song_mid", id))
        ).toString()

        val url = "https://u.y.qq.com/cgi-bin/musicu.fcg".toHttpUrl().newBuilder()
            .addQueryParameter("data", detailRequestData)
            .build()

        val responseJson = executeRequest(url.toString()) as String
        logDetailResponse(label = url.encodedPath, responseJson = responseJson)

        val songInfoJson = JSONObject(responseJson).optJSONObject("songinfo")?.toString()
            ?: throw IOException("响应中找不到 songinfo 字段")

        return json.decodeFromString<QQMusicDetailResponse>(songInfoJson).data?.trackInfo
            ?: throw IOException("找不到ID为 $id 的歌曲详情")
    }

    private fun QQMusicTrackInfo.toSongDetails(
        lyric: String?,
        translatedLyric: String?
    ): SongDetails {
        return SongDetails(
            id = mid,
            songName = name,
            singer = singer.joinToString("/") { it.name },
            album = album.name,
            coverUrl = "https://y.qq.com/music/photo_new/T002R800x800M000${album.mid}.jpg",
            lyric = lyric,
            translatedLyric = translatedLyric
        )
    }

    private fun logDetailResponse(label: String, responseJson: String) {
        val preview = responseJson
            .replace(Regex("\\s+"), " ")
            .take(DEBUG_JSON_PREVIEW_MAX_CHARS)
        if (debugLogging) {
            NPLogger.d(TAG, "获取歌曲详情响应: label=$label, length=${responseJson.length}, preview=$preview")
            return
        }
        NPLogger.d(TAG, "获取歌曲详情响应: labelHash=${label.hashCode()}, length=${responseJson.length}")
    }

    suspend fun getNativeLyrics(songMid: String): Pair<String?, String?> {
        return try {
            val lyricRequestData = JSONObject().put(
                "req", JSONObject()
                    .put("method", "GetPlayLyricInfo")
                    .put("module", "music.musichallSong.PlayLyricInfo")
                    .put(
                        "param", JSONObject()
                            .put("songMID", songMid)
                            // 不显式点名要翻译, 接口只回一个空的 trans
                            .put("trans", 1)
                            .put("qrc", 0)
                            .put("crypt", 0)
                    )
            ).toString()

            val url = "https://u.y.qq.com/cgi-bin/musicu.fcg".toHttpUrl().newBuilder()
                .addQueryParameter("format", "json")
                .addQueryParameter("data", lyricRequestData)
                .build()

            val request = Request.Builder().url(url)
                .header("Referer", "https://y.qq.com")
                .build()

            val responseJson = executeRequest(request) as String
            val envelope = json.decodeFromString<QQMusicLyricContainer>(responseJson).req
            if (envelope == null || envelope.code != 0) {
                if (envelope != null) {
                    NPLogger.w(
                        TAG,
                        "QQ lyric request rejected: songMid=$songMid code=${envelope.code}"
                    )
                }
                Pair(null, null)
            } else {
                val lyricResponse = envelope.data

                val lyric = decodeQQMusicLyricPayload(lyricResponse?.lyric)

                val translatedLyric = stripUntranslatedPlaceholderLines(
                    decodeQQMusicLyricPayload(lyricResponse?.trans)
                )

                Pair(lyric, translatedLyric)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.e(TAG, "获取QQ音乐歌词失败", error)
            Pair(null, null)
        }
    }

    @Throws(IOException::class)
    private suspend fun executeRequest(url: String, asBytes: Boolean = false): Any {
        val request = Request.Builder().url(url).build()
        return executeRequest(request, asBytes)
    }

    @Throws(IOException::class)
    private suspend fun executeRequest(request: Request, asBytes: Boolean = false): Any {
        return client.newCall(request).awaitResponse { response ->
            if (!response.isSuccessful) throw IOException("请求失败: ${response.code} for url: ${request.url}")
            val body = response.body
            if (asBytes) body.bytes() else body.string()
        }
    }

    @SuppressLint("DefaultLocale")
    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }
}
