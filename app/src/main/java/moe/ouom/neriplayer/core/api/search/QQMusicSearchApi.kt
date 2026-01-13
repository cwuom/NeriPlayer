package moe.ouom.neriplayer.core.api.search

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
 * File: moe.ouom.neriplayer.core.api.search/QQMusicSearchApi
 * Created: 2025/8/17
 */

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.util.NPLogger
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import moe.ouom.neriplayer.core.di.AppContainer

@Serializable private data class QQMusicSearchResponse(val data: QQMusicSearchData?)
@Serializable private data class QQMusicSearchData(val song: QQMusicSearchSong?)
@Serializable private data class QQMusicSearchSong(val list: List<QQMusicSongSummary>?)
@Serializable private data class QQMusicSongSummary(
    @SerialName("songmid") val songMid: String,
    @SerialName("songname") val songName: String,
    val singer: List<QQMusicArtist>,
    @SerialName("albummid") val albumMid: String?,
    @SerialName("albumname") val albumName: String?,
    val interval: Long // 歌曲时长（秒）
)

@Serializable private data class QQMusicArtist(val name: String)

@Serializable private data class QQMusicDetailContainer(
    @SerialName("songinfo") val songInfo: QQMusicDetailResponse
)
@Serializable private data class QQMusicDetailResponse(val data: QQMusicDetailData?)
@Serializable private data class QQMusicDetailData(@SerialName("track_info") val trackInfo: QQMusicTrackInfo?)
@Serializable private data class QQMusicTrackInfo(
    val mid: String,
    val name: String,
    val singer: List<QQMusicArtist>,
    val album: QQMusicAlbum
)
@Serializable private data class QQMusicAlbum(val name: String, val mid: String)

@Serializable private data class QQMusicLyricResponse(val lyric: String?, val trans: String?)


class QQMusicSearchApi : SearchApi {

    companion object {
        private const val TAG = "QQMusicSearchApi"
    }

    private val client: OkHttpClient = AppContainer.sharedOkHttpClient
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

    override suspend fun getSongInfo(id: String): SongDetails { // id is songMid
        return withContext(Dispatchers.IO) {
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
            NPLogger.d(TAG, "获取歌曲详情的原始 JSON 响应: $responseJson")

            val songInfoJson = JSONObject(responseJson).optJSONObject("songinfo")?.toString()
                ?: throw IOException("响应中找不到 songinfo 字段")

            val songData = json.decodeFromString<QQMusicDetailResponse>(songInfoJson).data?.trackInfo
                ?: throw IOException("找不到ID为 $id 的歌曲详情")

            coroutineScope {
                val lyricDeferred = async { fetchQQMusicLyric(id) }

                val (lyric, translatedLyric) = lyricDeferred.await()
                SongDetails(
                    id = songData.mid,
                    songName = songData.name,
                    singer = songData.singer.joinToString("/") { it.name },
                    album = songData.album.name,
                    coverUrl = "https://y.qq.com/music/photo_new/T002R800x800M000${songData.album.mid}.jpg",
                    lyric = lyric,
                    translatedLyric = translatedLyric
                )
            }
        }
    }

    private fun fetchQQMusicLyric(songMid: String): Pair<String?, String?> {
        return try {
            val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg".toHttpUrl().newBuilder()
                .addQueryParameter("songmid", songMid)
                .addQueryParameter("format", "json")
                .addQueryParameter("inCharset", "utf8")
                .addQueryParameter("outCharset", "utf-8")
                .build()

            val request = Request.Builder().url(url)
                .header("Referer", "https://y.qq.com")
                .build()

            val responseJson = executeRequest(request) as String
            val lyricResponse = json.decodeFromString<QQMusicLyricResponse>(responseJson)

            val lyric = if (lyricResponse.lyric != null) {
                String(Base64.getDecoder().decode(lyricResponse.lyric))
            } else {
                null
            }

            val translatedLyric = if (lyricResponse.trans != null) {
                String(Base64.getDecoder().decode(lyricResponse.trans))
            } else {
                null
            }

            Pair(lyric, translatedLyric)
        } catch (e: Exception) {
            NPLogger.e(TAG, "获取QQ音乐歌词失败", e)
            Pair(null, null)
        }
    }

    @Throws(IOException::class)
    private fun executeRequest(url: String, asBytes: Boolean = false): Any {
        val request = Request.Builder().url(url).build()
        return executeRequest(request, asBytes)
    }

    @Throws(IOException::class)
    private fun executeRequest(request: Request, asBytes: Boolean = false): Any {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("请求失败: ${response.code} for url: ${request.url}")
            val body = response.body
            return if (asBytes) body.bytes() else body.string()
        }
    }

    @SuppressLint("DefaultLocale")
    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }
}