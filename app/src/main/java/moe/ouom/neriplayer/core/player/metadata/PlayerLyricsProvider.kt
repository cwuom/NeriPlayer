package moe.ouom.neriplayer.core.player.metadata

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
 * File: moe.ouom.neriplayer.core.player.metadata/PlayerLyricsProvider
 * Updated: 2026/3/23
 */

import android.app.Application
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.api.lyrics.LrcLibClient
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicClient
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.ui.component.LyricEntry
import moe.ouom.neriplayer.ui.component.parseNeteaseLyricsAuto
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject

internal fun extractPreferredNeteaseLyricContent(rawResponse: String): String {
    val payload = JSONObject(rawResponse)
    val yrc = payload.optJSONObject("yrc")?.optString("lyric").orEmpty()
    if (yrc.isNotBlank()) {
        return yrc
    }
    return payload.optJSONObject("lrc")?.optString("lyric").orEmpty()
}

internal enum class LocalLyricOverrideState {
    ABSENT,
    CLEARED,
    PRESENT
}

internal fun resolveLocalLyricOverrideState(rawLyric: String?): LocalLyricOverrideState {
    return when {
        rawLyric == null -> LocalLyricOverrideState.ABSENT
        rawLyric.isBlank() -> LocalLyricOverrideState.CLEARED
        else -> LocalLyricOverrideState.PRESENT
    }
}

internal object PlayerLyricsProvider {

    private fun parseBestLyricEntries(rawLyric: String): List<LyricEntry> {
        return parseNeteaseLyricsAuto(rawLyric)
    }

    private fun parseLocalLyricOverride(
        rawLyric: String?,
        logPrefix: String
    ): List<LyricEntry>? {
        return when (resolveLocalLyricOverrideState(rawLyric)) {
            LocalLyricOverrideState.ABSENT -> null
            LocalLyricOverrideState.CLEARED -> emptyList()
            LocalLyricOverrideState.PRESENT -> {
                try {
                    parseBestLyricEntries(rawLyric!!)
                } catch (error: Exception) {
                    NPLogger.w("NERI-PlayerManager", "$logPrefix: ${error.message}")
                    null
                }
            }
        }
    }

    suspend fun getNeteaseLyrics(
        songId: Long,
        neteaseClient: NeteaseClient
    ): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            try {
                val raw = neteaseClient.getLyricNew(songId)
                val preferredLyric = extractPreferredNeteaseLyricContent(raw)
                if (preferredLyric.isBlank()) emptyList() else parseBestLyricEntries(preferredLyric)
            } catch (error: Exception) {
                NPLogger.e("NERI-PlayerManager", "getNeteaseLyrics failed: ${error.message}", error)
                emptyList()
            }
        }
    }

    suspend fun getNeteaseTranslatedLyrics(
        songId: Long,
        neteaseClient: NeteaseClient
    ): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            try {
                val raw = neteaseClient.getLyricNew(songId)
                val payload = JSONObject(raw)
                val translated = payload.optJSONObject("ytlrc")?.optString("lyric")
                    ?: payload.optJSONObject("tlyric")?.optString("lyric")
                    ?: ""
                if (translated.isBlank()) {
                    emptyList()
                } else {
                    parseBestLyricEntries(translated)
                }
            } catch (error: Exception) {
                NPLogger.e(
                    "NERI-PlayerManager",
                    "getNeteaseTranslatedLyrics failed: ${error.message}",
                    error
                )
                emptyList()
            }
        }
    }

    suspend fun getTranslatedLyrics(
        song: SongItem,
        application: Application,
        neteaseClient: NeteaseClient,
        biliSourceTag: String
    ): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            parseLocalLyricOverride(
                rawLyric = song.matchedTranslatedLyric,
                logPrefix = "本地翻译歌词解析失败"
            )?.let { return@withContext it }
            parseLocalLyricOverride(
                rawLyric = AudioDownloadManager.getTranslatedLyricContent(application, song),
                logPrefix = "本地翻译歌词读取失败"
            )?.let { return@withContext it }

            if (isYouTubeMusicSong(song)) {
                return@withContext emptyList()
            }

            if (song.album.startsWith(biliSourceTag)) {
                return@withContext when (song.matchedLyricSource) {
                    MusicPlatform.CLOUD_MUSIC -> {
                        val matchedId = song.matchedSongId?.toLongOrNull()
                        if (matchedId != null) {
                            getNeteaseTranslatedLyrics(matchedId, neteaseClient)
                        } else {
                            emptyList()
                        }
                    }
                    else -> emptyList()
                }
            }

            when (song.matchedLyricSource) {
                null,
                MusicPlatform.CLOUD_MUSIC -> getNeteaseTranslatedLyrics(song.id, neteaseClient)
                else -> emptyList()
            }
        }
    }

    suspend fun getLyrics(
        song: SongItem,
        application: Application,
        neteaseClient: NeteaseClient,
        youtubeMusicClient: YouTubeMusicClient,
        lrcLibClient: LrcLibClient,
        ytMusicLyricsCache: LruCache<String, List<LyricEntry>>,
        biliSourceTag: String
    ): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            parseLocalLyricOverride(
                rawLyric = song.matchedLyric,
                logPrefix = "匹配歌词解析失败"
            )?.let { return@withContext it }
            parseLocalLyricOverride(
                rawLyric = AudioDownloadManager.getLyricContent(application, song),
                logPrefix = "本地歌词读取失败"
            )?.let { return@withContext it }

            if (isYouTubeMusicSong(song)) {
                return@withContext getYouTubeMusicLyrics(song, youtubeMusicClient, lrcLibClient, ytMusicLyricsCache)
            }

            when {
                song.album.startsWith(biliSourceTag) -> emptyList()
                else -> getNeteaseLyrics(song.id, neteaseClient)
            }
        }
    }

    private suspend fun getYouTubeMusicLyrics(
        song: SongItem,
        youtubeMusicClient: YouTubeMusicClient,
        lrcLibClient: LrcLibClient,
        ytMusicLyricsCache: LruCache<String, List<LyricEntry>>
    ): List<LyricEntry> {
        val cacheKey = song.id.toString()
        ytMusicLyricsCache.get(cacheKey)?.let { cached ->
            NPLogger.d("NERI-PlayerManager", "Using cached YT Music lyrics for '${song.name}'")
            return cached
        }

        val videoId = extractYouTubeMusicVideoId(song.mediaUri)
        return withContext(Dispatchers.IO) {
            try {
                val lrcLibResult = try {
                    val durationSeconds = (song.durationMs / 1000).toInt()
                    lrcLibClient.getLyrics(
                        trackName = song.name,
                        artistName = song.artist,
                        durationSeconds = durationSeconds
                    ) ?: lrcLibClient.searchLyrics("${song.name} ${song.artist}")
                } catch (error: Exception) {
                    NPLogger.d("NERI-PlayerManager", "LRCLIB lookup failed: ${error.message}")
                    null
                }

                if (!lrcLibResult?.syncedLyrics.isNullOrBlank()) {
                    NPLogger.d("NERI-PlayerManager", "Using LRCLIB synced lyrics for '${song.name}'")
                    val entries = parseNeteaseLyricsAuto(lrcLibResult!!.syncedLyrics!!)
                    if (entries.isNotEmpty()) {
                        ytMusicLyricsCache.put(cacheKey, entries)
                    }
                    return@withContext entries
                }

                if (!lrcLibResult?.plainLyrics.isNullOrBlank()) {
                    NPLogger.d("NERI-PlayerManager", "Using LRCLIB plain lyrics for '${song.name}'")
                    val entries = convertPlainLyricsToEntries(
                        lrcLibResult!!.plainLyrics!!,
                        song.durationMs
                    )
                    if (entries.isNotEmpty()) {
                        ytMusicLyricsCache.put(cacheKey, entries)
                    }
                    return@withContext entries
                }

                if (videoId.isNullOrBlank()) {
                    return@withContext emptyList()
                }

                val youtubeLyrics = youtubeMusicClient.getLyrics(videoId)
                    ?: return@withContext emptyList()
                val lyricsText = youtubeLyrics.lyrics
                if (lyricsText.isBlank()) {
                    return@withContext emptyList()
                }

                NPLogger.d("NERI-PlayerManager", "Using YouTube Music API lyrics for '${song.name}'")
                val entries = if (lyricsText.contains(Regex("\\[\\d{2}:\\d{2}"))) {
                    parseNeteaseLyricsAuto(lyricsText)
                } else {
                    convertPlainLyricsToEntries(lyricsText, song.durationMs)
                }
                if (entries.isNotEmpty()) {
                    ytMusicLyricsCache.put(cacheKey, entries)
                }
                entries
            } catch (error: Exception) {
                NPLogger.e("NERI-PlayerManager", "getYouTubeMusicLyrics failed: ${error.message}", error)
                emptyList()
            }
        }
    }
}
