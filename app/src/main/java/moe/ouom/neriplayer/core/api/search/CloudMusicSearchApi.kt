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
 * File: moe.ouom.neriplayer.core.api.search/CloudMusicApi
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
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.util.NPLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable private data class CloudMusicSearchResponse(val result: CloudMusicSearchResult?)
@Serializable private data class CloudMusicSearchResult(val songs: List<CloudMusicSongSummary>?)
@Serializable private data class CloudMusicSongSummary(
    val id: Long,
    val name: String,
    @SerialName("dt") val duration: Long,
    @SerialName("ar") val artists: List<CloudMusicArtist>,
    @SerialName("al") val album: CloudMusicAlbum
)

@Serializable private data class CloudMusicSongDetail(
    val name: String,
    @SerialName("artists") val artists: List<CloudMusicArtist>,
    @SerialName("album") val album: CloudMusicAlbum,
)

@Serializable private data class CloudMusicSongDetailResponse(val songs: List<CloudMusicSongDetail>)
@Serializable private data class CloudMusicArtist(val name: String)
@Serializable private data class CloudMusicAlbum(val name: String, val picUrl: String?)
@Serializable private data class CloudMusicLyricResponse(val lrc: CloudMusicLrc?)
@Serializable private data class CloudMusicLrc(val lyric: String?)

class CloudMusicSearchApi(private val neteaseClient: NeteaseClient) : SearchApi {

    companion object {
        private const val TAG = "CloudMusicSearchApi"
    }

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(keyword: String, page: Int): List<SongSearchInfo> {
        return withContext(Dispatchers.IO) {
            val offset = (page - 1).coerceAtLeast(0) * 20
            val responseJson = neteaseClient.searchSongs(keyword, limit = 20, offset = offset)
            logLongJson(TAG, responseJson)

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

            coroutineScope {
                val lyricDeferred = async {
                    val lyricUrl = "https://music.163.com/api/song/lyric?id=${id}&lv=-1"
                    val lyricJson = executeRequest(lyricUrl) as String
                    json.decodeFromString<CloudMusicLyricResponse>(lyricJson).lrc?.lyric
                }

                SongDetails(
                    id = id,
                    songName = songData.name,
                    singer = songData.artists.joinToString("/") { it.name },
                    album = songData.album.name,
                    coverUrl = songData.album.picUrl,
                    lyric = lyricDeferred.await()
                )
            }
        }
    }

    @Throws(IOException::class)
    private fun executeRequest(url: String, asBytes: Boolean = false): Any {
        val cookies = neteaseClient.getCookies()
        val cookieString = cookies.map { "${it.key}=${it.value}" }.joinToString("; ")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36")
            .header("Referer", "https://music.163.com/")
            .header("Cookie", cookieString)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("请求失败: ${response.code} for url: $url")
            val body = response.body

            if (asBytes) {
                return body.bytes()
            } else {
                val jsonString = body.string()
                NPLogger.d(TAG, "Request URL: $url")
                logLongJson(TAG, jsonString)
                return jsonString
            }
        }
    }

    private fun logLongJson(tag: String, json: String) {
        if (json.length > 3000) {
            NPLogger.d(tag, "Response JSON (chunk 1): ${json.substring(0, 3000)}")
            var i = 3000
            while (i < json.length) {
                val chunk = json.substring(i, (i + 3000).coerceAtMost(json.length))
                NPLogger.d(tag, "Response JSON (chunk ${i / 3000 + 1}): $chunk")
                i += 3000
            }
        } else {
            NPLogger.d(tag, "Response JSON: $json")
        }
    }

    @SuppressLint("DefaultLocale")
    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }
}
