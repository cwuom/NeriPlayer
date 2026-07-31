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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.BuildConfig
import moe.ouom.neriplayer.core.api.lyrics.AmllTtmlClient
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.metadata.AmllLyricsResolver
import moe.ouom.neriplayer.util.network.awaitResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

@Serializable private data class QQMusicSearchResponse(val data: QQMusicSearchData?)
@Serializable private data class QQMusicSearchData(val song: QQMusicSearchSong?)
@Serializable private data class QQMusicSearchSong(val list: List<QQMusicSongSummary>?)
@Serializable private data class QQMusicSongSummary(
    @SerialName("songmid") val songMid: String,
    @SerialName("songname") val songName: String,
    val singer: List<QQMusicArtist>,
    @SerialName("albummid") val albumMid: String?,
    @SerialName("albumname") val albumName: String?,
    val interval: Long // 歌曲时长 (秒)
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
    val album: QQMusicAlbum,
    val interval: Long = 0L
)
@Serializable private data class QQMusicAlbum(val name: String, val mid: String)

/**
 * 没有歌词时接口整个字段都不下发
 *
 * 可空但缺默认值在 kotlinx.serialization 里仍算必填, 会直接抛 MissingFieldException
 */
@Serializable data class QQMusicLyricResponse(
    val lyric: String? = null,
    val trans: String? = null
)

@Serializable data class QQMusicLyricContainer(
    val req: QQMusicLyricEnvelope? = null
)

@Serializable data class QQMusicLyricEnvelope(
    val code: Int = 0,
    val data: QQMusicLyricResponse? = null
)

private val QQMusicBase64Pattern = Regex("^[A-Za-z0-9+/=]+$")
private val QQMusicLrcTimestampPattern = Regex(
    "\\[(\\d{1,3}):(\\d{2})(?:[.:]\\d{1,3})?]"
)

/**
 * QQ 用一行 // 占位表示这句没有翻译
 *
 * 保留时间戳让翻译 matcher 可以消费这个空槽, 最终显示层会忽略占位文本
 */
fun stripUntranslatedPlaceholderLines(lyric: String?): String? {
    val source = lyric?.takeIf { it.isNotBlank() } ?: return null
    return source.lineSequence()
        .joinToString("\n") { line ->
            val closingBracket = line.indexOf(']')
            if (closingBracket < 0) {
                line
            } else {
                val text = line.substring(closingBracket + 1).trim()
                if (isQQMusicUntranslatedPlaceholder(text)) {
                    line.substring(0, closingBracket + 1) + "//"
                } else {
                    line
                }
            }
        }
        .takeIf { it.isNotBlank() }
}

private fun isQQMusicUntranslatedPlaceholder(text: String): Boolean {
    val normalized = text
        .replace('／', '/')
        .filterNot(Char::isWhitespace)
    return normalized.length >= 2 && normalized.all { it == '/' }
}

fun decodeQQMusicLyricPayload(rawValue: String?): String? {
    val sanitized = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val plainText = htmlUnescapeQQMusic(sanitized)
    if (QQMusicLrcTimestampPattern.containsMatchIn(plainText)) {
        return plainText
    }
    val decoded = decodeQQMusicBase64Lyric(plainText) ?: return null
    return htmlUnescapeQQMusic(decoded)
        .takeIf { QQMusicLrcTimestampPattern.containsMatchIn(it) }
}

fun decodeQQMusicBase64Lyric(value: String): String? {
    val compact = value.filterNot(Char::isWhitespace)
    if (compact.isEmpty() || compact.length % 4 != 0 || !QQMusicBase64Pattern.matches(compact)) {
        return null
    }
    return runCatching {
        String(Base64.getDecoder().decode(compact), Charsets.UTF_8)
    }.getOrNull()?.takeIf { QQMusicLrcTimestampPattern.containsMatchIn(it) }
}

fun chooseQQMusicLyrics(
    qqLyric: String?,
    qqTranslatedLyric: String?,
    amllLyric: String?
): Pair<String?, String?> {
    return if (amllLyric.isNullOrBlank()) {
        qqLyric to qqTranslatedLyric
    } else {
        amllLyric to null
    }
}

private fun htmlUnescapeQQMusic(value: String): String {
    return value
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
}


class QQMusicSearchApi(
    private val amllTtmlClient: AmllTtmlClient = AppContainer.amllTtmlClient,
    private val amllLyricsEnabledProvider: suspend () -> Boolean = {
        AppContainer.settingsRepo.amllLyricsEnabledFlow.first()
    }
) : SearchApi {

    companion object {
        private const val TAG = "QQMusicSearchApi"
        private const val DEBUG_JSON_PREVIEW_MAX_CHARS = 512
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
            val songData = fetchSongData(id)

            coroutineScope {
                val lyricDeferred = async { fetchQQMusicLyric(id) }
                val amllLyricDeferred = async {
                    fetchAmllWordLyricIfEnabled(songData)
                }

                val (qqLyric, qqTranslatedLyric) = lyricDeferred.await()
                val amllLyric = amllLyricDeferred.await()
                val (lyric, translatedLyric) = chooseQQMusicLyrics(
                    qqLyric = qqLyric,
                    qqTranslatedLyric = qqTranslatedLyric,
                    amllLyric = amllLyric
                )
                songData.toSongDetails(
                    lyric = lyric,
                    translatedLyric = translatedLyric
                )
            }
        }
    }

    suspend fun getNativeSongInfo(id: String): SongDetails {
        return withContext(Dispatchers.IO) {
            val songData = fetchSongData(id)
            val (lyric, translatedLyric) = fetchQQMusicLyric(id)
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

    private suspend fun fetchAmllWordLyricIfEnabled(songData: QQMusicTrackInfo): String? {
        return try {
            if (amllLyricsEnabledProvider()) {
                val durationMs = songData.interval.takeIf { it > 0L }?.times(1000L) ?: 0L
                AmllLyricsResolver.loadRawByMetadata(
                    trackName = songData.name,
                    artistName = songData.singer.joinToString("/") { it.name },
                    durationMs = durationMs,
                    amllTtmlClient = amllTtmlClient,
                    requireDurationMatch = durationMs > 0L
                )?.rawLyrics
            } else {
                null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.d(TAG, "AMLL QQ lyric lookup failed: ${error.message}")
            null
        }
    }

    private fun logDetailResponse(label: String, responseJson: String) {
        val preview = responseJson
            .replace(Regex("\\s+"), " ")
            .take(DEBUG_JSON_PREVIEW_MAX_CHARS)
        if (BuildConfig.DEBUG) {
            NPLogger.d(TAG, "获取歌曲详情响应: label=$label, length=${responseJson.length}, preview=$preview")
            return
        }
        NPLogger.d(TAG, "获取歌曲详情响应: labelHash=${label.hashCode()}, length=${responseJson.length}")
    }

    private suspend fun fetchQQMusicLyric(songMid: String): Pair<String?, String?> {
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
