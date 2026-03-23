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
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.ui.component.LyricEntry
import moe.ouom.neriplayer.ui.component.parseNeteaseLrc
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject
internal object PlayerLyricsProvider {

    suspend fun getNeteaseLyrics(
        songId: Long,
        neteaseClient: NeteaseClient
    ): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            try {
                val raw = neteaseClient.getLyricNew(songId)
                val lrc = JSONObject(raw).optJSONObject("lrc")?.optString("lyric") ?: ""
                parseNeteaseLrc(lrc)
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
                val translated = JSONObject(raw).optJSONObject("tlyric")?.optString("lyric") ?: ""
                if (translated.isBlank()) {
                    emptyList()
                } else {
                    parseNeteaseLrc(translated)
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
        val localTranslatedLyrics = AudioDownloadManager.getTranslatedLyricContent(application, song)
        if (!localTranslatedLyrics.isNullOrBlank()) {
            try {
                return parseNeteaseLrc(localTranslatedLyrics)
            } catch (error: Exception) {
                NPLogger.w("NERI-PlayerManager", "本地翻译歌词读取失败: ${error.message}")
            }
        }

        if (isYouTubeMusicSong(song)) {
            return emptyList()
        }

        if (song.album.startsWith(biliSourceTag)) {
            return when (song.matchedLyricSource) {
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

        return when (song.matchedLyricSource) {
            null,
            MusicPlatform.CLOUD_MUSIC -> getNeteaseTranslatedLyrics(song.id, neteaseClient)
            else -> emptyList()
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
        if (isYouTubeMusicSong(song)) {
            return getYouTubeMusicLyrics(song, youtubeMusicClient, lrcLibClient, ytMusicLyricsCache)
        }

        if (!song.matchedLyric.isNullOrBlank()) {
            try {
                return parseNeteaseLrc(song.matchedLyric)
            } catch (error: Exception) {
                NPLogger.w("NERI-PlayerManager", "匹配歌词解析失败: ${error.message}")
            }
        }

        val localLyricContent = AudioDownloadManager.getLyricContent(application, song)
        if (!localLyricContent.isNullOrBlank()) {
            try {
                return parseNeteaseLrc(localLyricContent)
            } catch (error: Exception) {
                NPLogger.w("NERI-PlayerManager", "本地歌词读取失败: ${error.message}")
            }
        }

        return when {
            song.album.startsWith(biliSourceTag) -> emptyList()
            else -> getNeteaseLyrics(song.id, neteaseClient)
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
                    val entries = parseNeteaseLrc(lrcLibResult!!.syncedLyrics!!)
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
                    parseNeteaseLrc(lyricsText)
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
