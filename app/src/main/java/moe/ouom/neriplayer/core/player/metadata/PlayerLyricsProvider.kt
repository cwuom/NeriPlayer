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
import moe.ouom.neriplayer.core.api.lyrics.AmllTtmlClient
import moe.ouom.neriplayer.core.api.lyrics.LrcLibClient
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicClient
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.lyrics.hasWordTimedEntries
import moe.ouom.neriplayer.ui.component.lyrics.parseNeteaseLyricsAuto
import moe.ouom.neriplayer.ui.component.lyrics.resolveStoredLyricText
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.network.isTransientHttp2StreamReset
import org.json.JSONObject

internal fun extractPreferredNeteaseLyricContent(rawResponse: String): String {
    val payload: JSONObject = JSONObject(rawResponse)
    val yrc: String = payload.optJSONObject("yrc")?.optString("lyric").orEmpty()
    if (yrc.isNotBlank()) {
        return yrc
    }
    return normalizeLegacyLrcTimestamps(
        payload.optJSONObject("lrc")?.optString("lyric").orEmpty()
    )
}

internal fun extractTranslatedNeteaseLyricContent(rawResponse: String): String {
    val payload: JSONObject = JSONObject(rawResponse)
    return payload.optJSONObject("ytlrc")?.optString("lyric")
        ?: payload.optJSONObject("tlyric")?.optString("lyric")
        ?: ""
}

internal fun extractRomanizedNeteaseLyricContent(rawResponse: String): String {
    val payload: JSONObject = JSONObject(rawResponse)
    return normalizeLegacyLrcTimestamps(
        payload.optJSONObject("romalrc")?.optString("lyric").orEmpty()
    )
}

internal data class NeteaseLyricsCacheEntry(
    val preferredLyricText: String,
    val romanizedLyricText: String,
    val preferredLyricEntries: List<LyricEntry>,
    val translatedLyricEntries: List<LyricEntry>,
    val romanizedLyricEntries: List<LyricEntry>
)

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
    private val amllLyricsCache = LruCache<String, List<LyricEntry>>(40)

    private fun parseBestLyricEntries(rawLyric: String): List<LyricEntry> {
        return parseNeteaseLyricsAuto(rawLyric)
    }

    internal fun clearAmllLyricsCache() {
        amllLyricsCache.evictAll()
    }

    private fun buildAmllLyricsCacheKey(
        song: SongItem,
        requireDurationMatch: Boolean
    ): String {
        return buildString {
            append(song.stableKey())
            append('|')
            append(song.name)
            append('|')
            append(song.artist)
            append('|')
            append(song.durationMs)
            append("|durationMatch=")
            append(requireDurationMatch)
        }
    }

    private suspend fun loadAmllLyricsWithCache(
        song: SongItem,
        amllTtmlClient: AmllTtmlClient,
        requireDurationMatch: Boolean
    ): List<LyricEntry> {
        val cacheKey = buildAmllLyricsCacheKey(song, requireDurationMatch)
        amllLyricsCache.get(cacheKey)?.let { cached ->
            NPLogger.d("NERI-PlayerManager", "Using cached AMLL lyrics for '${song.name}'")
            return cached
        }
        val entries = AmllLyricsResolver.loadForSong(
            song = song,
            amllTtmlClient = amllTtmlClient,
            requireDurationMatch = requireDurationMatch
        )
        amllLyricsCache.put(cacheKey, entries)
        return entries
    }

    private fun parseRemoteLyricEntriesOrEmpty(
        rawLyric: String,
        logPrefix: String
    ): List<LyricEntry> {
        if (rawLyric.isBlank()) {
            return emptyList()
        }
        return try {
            parseBestLyricEntries(rawLyric)
        } catch (error: Exception) {
            NPLogger.w("NERI-PlayerManager", "$logPrefix: ${error.message}")
            emptyList()
        }
    }

    private fun logNeteaseLyricLoadFailure(operation: String, error: Exception) {
        if (error.isTransientHttp2StreamReset()) {
            NPLogger.w(
                "NERI-PlayerManager",
                "$operation skipped after transient HTTP/2 reset: ${error.message.orEmpty()}"
            )
            return
        }
        NPLogger.e("NERI-PlayerManager", "$operation failed: ${error.message}", error)
    }

    internal fun buildNeteaseLyricsCacheEntry(rawResponse: String): NeteaseLyricsCacheEntry {
        val preferredLyric = extractPreferredNeteaseLyricContent(rawResponse)
        val translatedLyric = extractTranslatedNeteaseLyricContent(rawResponse)
        val romanizedLyric = extractRomanizedNeteaseLyricContent(rawResponse)
        return NeteaseLyricsCacheEntry(
            preferredLyricText = preferredLyric,
            romanizedLyricText = romanizedLyric,
            preferredLyricEntries = parseRemoteLyricEntriesOrEmpty(
                rawLyric = preferredLyric,
                logPrefix = "网易云原文歌词解析失败"
            ),
            translatedLyricEntries = parseRemoteLyricEntriesOrEmpty(
                rawLyric = translatedLyric,
                logPrefix = "网易云翻译歌词解析失败"
            ),
            romanizedLyricEntries = parseRemoteLyricEntriesOrEmpty(
                rawLyric = romanizedLyric,
                logPrefix = "网易云音译歌词解析失败"
            )
        )
    }

    internal suspend fun getOrLoadNeteaseLyricsCacheEntry(
        songId: Long,
        neteaseLyricsCache: LruCache<Long, NeteaseLyricsCacheEntry>,
        loader: suspend (Long) -> String
    ): NeteaseLyricsCacheEntry {
        neteaseLyricsCache.get(songId)?.let { cached ->
            NPLogger.d("NERI-PlayerManager", "Using cached NetEase lyrics for songId=$songId")
            return cached
        }

        val entry = buildNeteaseLyricsCacheEntry(loader(songId))
        neteaseLyricsCache.put(songId, entry)
        return entry
    }

    private suspend fun getCachedNeteaseLyricsEntry(
        songId: Long,
        neteaseClient: NeteaseClient,
        neteaseLyricsCache: LruCache<Long, NeteaseLyricsCacheEntry>
    ): NeteaseLyricsCacheEntry {
        return getOrLoadNeteaseLyricsCacheEntry(songId, neteaseLyricsCache) { id ->
            neteaseClient.getLyricNew(id)
        }
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

    private fun loadOnDemandLocalLyrics(
        application: Application,
        song: SongItem
    ): List<LyricEntry>? {
        if (!song.isLocalSong()) {
            return null
        }

        val localLyric = runCatching {
            LocalMediaSupport.inspect(application, song)?.lyricContent
        }.onFailure {
            NPLogger.w("NERI-PlayerManager", "本地歌词懒加载失败: ${it.message}")
        }.getOrNull()

        return parseLocalLyricOverride(
            rawLyric = localLyric,
            logPrefix = "本地歌词解析失败"
        )
    }

    suspend fun getNeteaseLyrics(
        songId: Long,
        neteaseClient: NeteaseClient,
        neteaseLyricsCache: LruCache<Long, NeteaseLyricsCacheEntry>
    ): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            try {
                getCachedNeteaseLyricsEntry(songId, neteaseClient, neteaseLyricsCache)
                    .preferredLyricEntries
            } catch (error: Exception) {
                logNeteaseLyricLoadFailure("getNeteaseLyrics", error)
                emptyList()
            }
        }
    }

    suspend fun getNeteaseTranslatedLyrics(
        songId: Long,
        neteaseClient: NeteaseClient,
        neteaseLyricsCache: LruCache<Long, NeteaseLyricsCacheEntry>
    ): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            try {
                getCachedNeteaseLyricsEntry(songId, neteaseClient, neteaseLyricsCache)
                    .translatedLyricEntries
            } catch (error: Exception) {
                logNeteaseLyricLoadFailure("getNeteaseTranslatedLyrics", error)
                emptyList()
            }
        }
    }

    suspend fun getNeteaseRomanizedLyrics(
        songId: Long,
        neteaseClient: NeteaseClient,
        neteaseLyricsCache: LruCache<Long, NeteaseLyricsCacheEntry>
    ): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            try {
                getCachedNeteaseLyricsEntry(songId, neteaseClient, neteaseLyricsCache)
                    .romanizedLyricEntries
            } catch (error: Exception) {
                logNeteaseLyricLoadFailure("getNeteaseRomanizedLyrics", error)
                emptyList()
            }
        }
    }

    suspend fun getPreferredNeteaseLyricContent(
        songId: Long,
        neteaseClient: NeteaseClient,
        neteaseLyricsCache: LruCache<Long, NeteaseLyricsCacheEntry>
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                getCachedNeteaseLyricsEntry(songId, neteaseClient, neteaseLyricsCache)
                    .preferredLyricText
            } catch (error: Exception) {
                logNeteaseLyricLoadFailure("getPreferredNeteaseLyricContent", error)
                ""
            }
        }
    }

    suspend fun getPreferredNeteaseRomanizedLyricContent(
        songId: Long,
        neteaseClient: NeteaseClient,
        neteaseLyricsCache: LruCache<Long, NeteaseLyricsCacheEntry>
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                getCachedNeteaseLyricsEntry(songId, neteaseClient, neteaseLyricsCache)
                    .romanizedLyricText
            } catch (error: Exception) {
                logNeteaseLyricLoadFailure("getPreferredNeteaseRomanizedLyricContent", error)
                ""
            }
        }
    }

    suspend fun getTranslatedLyrics(
        song: SongItem,
        application: Application,
        neteaseClient: NeteaseClient,
        neteaseLyricsCache: LruCache<Long, NeteaseLyricsCacheEntry>,
        biliSourceTag: String
    ): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            parseLocalLyricOverride(
                rawLyric = resolveStoredLyricText(
                    currentLyric = song.matchedTranslatedLyric,
                    legacyLyric = song.originalTranslatedLyric
                ),
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
                            getNeteaseTranslatedLyrics(
                                matchedId,
                                neteaseClient,
                                neteaseLyricsCache
                            )
                        } else {
                            emptyList()
                        }
                    }
                    else -> emptyList()
                }
            }

            when (song.matchedLyricSource) {
                null,
                MusicPlatform.CLOUD_MUSIC -> getNeteaseTranslatedLyrics(
                    song.id,
                    neteaseClient,
                    neteaseLyricsCache
                )
                else -> emptyList()
            }
        }
    }

    suspend fun getRomanizedLyrics(
        song: SongItem,
        neteaseClient: NeteaseClient,
        neteaseLyricsCache: LruCache<Long, NeteaseLyricsCacheEntry>,
        biliSourceTag: String
    ): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            if (isYouTubeMusicSong(song)) {
                return@withContext emptyList()
            }

            if (song.album.startsWith(biliSourceTag)) {
                return@withContext when (song.matchedLyricSource) {
                    MusicPlatform.CLOUD_MUSIC -> {
                        val matchedId = song.matchedSongId?.toLongOrNull()
                        if (matchedId != null) {
                            getNeteaseRomanizedLyrics(
                                matchedId,
                                neteaseClient,
                                neteaseLyricsCache
                            )
                        } else {
                            emptyList()
                        }
                    }
                    else -> emptyList()
                }
            }

            when (song.matchedLyricSource) {
                null,
                MusicPlatform.CLOUD_MUSIC -> getNeteaseRomanizedLyrics(
                    song.id,
                    neteaseClient,
                    neteaseLyricsCache
                )
                else -> emptyList()
            }
        }
    }

    suspend fun getLyrics(
        song: SongItem,
        application: Application,
        neteaseClient: NeteaseClient,
        neteaseLyricsCache: LruCache<Long, NeteaseLyricsCacheEntry>,
        youtubeMusicClient: YouTubeMusicClient,
        lrcLibClient: LrcLibClient,
        amllTtmlClient: AmllTtmlClient,
        amllLyricsEnabled: Boolean,
        ytMusicLyricsCache: LruCache<String, List<LyricEntry>>,
        biliSourceTag: String
    ): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            val storedLyric = resolveStoredLyricText(
                currentLyric = song.matchedLyric,
                legacyLyric = song.originalLyric
            )
            when (resolveLocalLyricOverrideState(storedLyric)) {
                LocalLyricOverrideState.CLEARED -> return@withContext emptyList()
                LocalLyricOverrideState.PRESENT -> {
                    parseLocalLyricOverride(
                        rawLyric = storedLyric,
                        logPrefix = "匹配歌词解析失败"
                    )?.let { entries ->
                        return@withContext entries
                    }
                }
                LocalLyricOverrideState.ABSENT -> Unit
            }
            parseLocalLyricOverride(
                rawLyric = AudioDownloadManager.getLyricContent(application, song),
                logPrefix = "本地歌词读取失败"
            )?.let { entries ->
                if (entries.isEmpty()) {
                    return@withContext emptyList()
                }
                return@withContext entries
            }
            loadOnDemandLocalLyrics(application, song)?.let { entries ->
                if (entries.isEmpty()) {
                    return@withContext emptyList()
                }
                return@withContext entries
            }

            if (isYouTubeMusicSong(song)) {
                return@withContext getYouTubeMusicLyrics(
                    song = song,
                    youtubeMusicClient = youtubeMusicClient,
                    lrcLibClient = lrcLibClient,
                    amllTtmlClient = amllTtmlClient,
                    amllLyricsEnabled = amllLyricsEnabled,
                    ytMusicLyricsCache = ytMusicLyricsCache
                )
            }

            val platformLyrics = when {
                song.album.startsWith(biliSourceTag) -> emptyList()
                song.matchedLyricSource == MusicPlatform.QQ_MUSIC -> emptyList()
                song.matchedLyricSource == MusicPlatform.CLOUD_MUSIC -> {
                    val matchedId = song.matchedSongId?.toLongOrNull() ?: song.id
                    getNeteaseLyrics(matchedId, neteaseClient, neteaseLyricsCache)
                }
                else -> getNeteaseLyrics(song.id, neteaseClient, neteaseLyricsCache)
            }

            if (platformLyrics.hasWordTimedEntries() || !amllLyricsEnabled) {
                platformLyrics
            } else {
                loadAmllLyricsWithCache(
                    song = song,
                    amllTtmlClient = amllTtmlClient,
                    requireDurationMatch = false
                ).ifEmpty { platformLyrics }
            }
        }
    }

    private suspend fun getYouTubeMusicLyrics(
        song: SongItem,
        youtubeMusicClient: YouTubeMusicClient,
        lrcLibClient: LrcLibClient,
        amllTtmlClient: AmllTtmlClient,
        amllLyricsEnabled: Boolean,
        ytMusicLyricsCache: LruCache<String, List<LyricEntry>>
    ): List<LyricEntry> {
        val cacheKey = "${song.id}:amll=$amllLyricsEnabled"
        ytMusicLyricsCache.get(cacheKey)?.let { cached ->
            NPLogger.d("NERI-PlayerManager", "Using cached YT Music lyrics for '${song.name}'")
            return cached
        }

        val videoId = extractYouTubeMusicVideoId(song.mediaUri)
        return withContext(Dispatchers.IO) {
            try {
                if (amllLyricsEnabled) {
                    val amllEntries = loadAmllLyricsWithCache(
                        song = song,
                        amllTtmlClient = amllTtmlClient,
                        requireDurationMatch = true
                    )
                    if (amllEntries.isNotEmpty()) {
                        ytMusicLyricsCache.put(cacheKey, amllEntries)
                        return@withContext amllEntries
                    }
                }

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
