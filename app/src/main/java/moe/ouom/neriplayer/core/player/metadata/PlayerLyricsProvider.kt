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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.api.lyrics.AmllTtmlClient
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchCandidate
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchRequest
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchSource
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchConfidence
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricsMatcher
import moe.ouom.neriplayer.core.api.lyrics.LrcLibClient
import moe.ouom.neriplayer.core.api.lyrics.RankedEditableLyricMatch
import moe.ouom.neriplayer.core.api.lyrics.editableLyricMatchSourcePriority
import moe.ouom.neriplayer.core.api.lyrics.extractPlainLyricsFromCollapsedTimedLyrics
import moe.ouom.neriplayer.core.api.lyrics.hasLrcTimestamp
import moe.ouom.neriplayer.core.api.lyrics.isExternalLyricDurationCompatible
import moe.ouom.neriplayer.core.api.lyrics.isReliableLyricMatchIdentity
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicClient
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
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
import moe.ouom.neriplayer.data.storage.lyricsCacheDirectory
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.network.isTransientHttp2StreamReset
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock

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
    val translatedLyricText: String = "",
    val romanizedLyricText: String,
    val preferredLyricEntries: List<LyricEntry>,
    val translatedLyricEntries: List<LyricEntry>,
    val romanizedLyricEntries: List<LyricEntry>,
    val rawResponse: String = ""
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

internal fun resolveLocalFirstLyricText(
    localLyric: String?,
    storedLyric: String?,
    downloadedLyric: String?
): String? {
    return localLyric ?: storedLyric ?: downloadedLyric
}

internal fun resolveManagedDownloadFirstLyricText(
    localLyric: String?,
    storedLyric: String?,
    downloadedLyric: String?
): String? {
    return downloadedLyric ?: localLyric ?: storedLyric
}

internal fun resolveLyricTextForPlayback(
    isManagedLocalDownload: Boolean,
    localLyric: String?,
    storedLyric: String?,
    downloadedLyric: String?
): String? {
    return if (isManagedLocalDownload) {
        resolveManagedDownloadFirstLyricText(
            localLyric = localLyric,
            storedLyric = storedLyric,
            downloadedLyric = downloadedLyric
        )
    } else {
        resolveLocalFirstLyricText(
            localLyric = localLyric,
            storedLyric = storedLyric,
            downloadedLyric = downloadedLyric
        )
    }
}

internal fun shouldLoadRemoteLyrics(song: SongItem): Boolean {
    return !song.isLocalSong()
}

internal fun shouldReadManagedDownloadLyrics(
    song: SongItem,
    isManagedLocalDownload: Boolean
): Boolean {
    return !song.isLocalSong() || isManagedLocalDownload
}

private fun isManagedLocalDownloadForSong(application: Application, song: SongItem): Boolean {
    if (!song.isLocalSong()) {
        return false
    }
    return GlobalDownloadManager.hasDownloadedSongCached(song) ||
        ManagedDownloadStorage.peekDownloadedAudio(song) != null ||
        ManagedDownloadStorage.isLikelyManagedDownloadSongFast(application, song)
}

internal fun hasCollapsedLyricEntryTimeline(entries: List<LyricEntry>): Boolean {
    val contentEntries = entries.filter { it.text.isNotBlank() }
    if (contentEntries.size < 3) {
        return false
    }
    return contentEntries.asSequence()
        .map { it.startTimeMs }
        .distinct()
        .take(2)
        .count() < 2
}

internal data class YouTubeMusicLyricsCacheEntry(
    val lyrics: List<LyricEntry>,
    val translatedLyrics: List<LyricEntry> = emptyList(),
    val translationLookupComplete: Boolean = false,
    val externalMatchCacheKey: String? = null,
    val externalMatchSource: EditableLyricMatchSource? = null,
    val externalMatchDurationDeltaMs: Long = 0L
)

internal data class DurationMatchedExternalLyrics(
    val lyrics: List<LyricEntry>,
    val translatedLyrics: List<LyricEntry>,
    val source: EditableLyricMatchSource,
    val durationDeltaMs: Long
)

internal fun shouldBlockExternalYouTubeMusicTranslation(rawLyric: String?): Boolean {
    return when (resolveLocalLyricOverrideState(rawLyric)) {
        LocalLyricOverrideState.ABSENT -> false
        LocalLyricOverrideState.CLEARED -> true
        LocalLyricOverrideState.PRESENT -> {
            extractPlainLyricsFromCollapsedTimedLyrics(rawLyric!!) == null
        }
    }
}

internal fun resolveYouTubeMusicTranslationCacheEntry(
    cached: YouTubeMusicLyricsCacheEntry?,
    externalLyrics: DurationMatchedExternalLyrics?
): YouTubeMusicLyricsCacheEntry? {
    if (cached != null) {
        if (cached.translatedLyrics.isNotEmpty() || cached.translationLookupComplete) {
            return cached
        }
        return cached.copy(translationLookupComplete = true)
    }
    return externalLyrics?.let { matchedLyrics ->
        YouTubeMusicLyricsCacheEntry(
            lyrics = matchedLyrics.lyrics,
            translatedLyrics = matchedLyrics.translatedLyrics,
            translationLookupComplete = true
        )
    }
}

internal fun sanitizeYouTubeMusicLyricsCacheEntry(
    entry: YouTubeMusicLyricsCacheEntry
): YouTubeMusicLyricsCacheEntry? {
    if (hasCollapsedLyricEntryTimeline(entry.lyrics)) {
        return null
    }
    if (!hasCollapsedLyricEntryTimeline(entry.translatedLyrics)) {
        return entry
    }
    return entry.copy(
        translatedLyrics = emptyList(),
        translationLookupComplete = false
    )
}

internal object PlayerLyricsProvider {
    internal interface NeteaseLyricsCacheStore {
        fun get(songId: Long): NeteaseLyricsCacheEntry?

        fun put(songId: Long, entry: NeteaseLyricsCacheEntry)
    }

    private class LruNeteaseLyricsCacheStore(
        private val cache: LruCache<Long, NeteaseLyricsCacheEntry>
    ) : NeteaseLyricsCacheStore {
        override fun get(songId: Long): NeteaseLyricsCacheEntry? = cache.get(songId)

        override fun put(songId: Long, entry: NeteaseLyricsCacheEntry) {
            cache.put(songId, entry)
        }
    }

    private val amllLyricsCache = LruCache<String, List<LyricEntry>>(40)
    private val neteaseRefreshInFlight = ConcurrentHashMap.newKeySet<Long>()
    private val neteaseColdLoadLocks = ConcurrentHashMap<Long, Mutex>()
    private val lyricsCacheGeneration = AtomicLong(0L)
    private val lyricsCacheStateLock = ReentrantReadWriteLock()

    private fun parseBestLyricEntries(rawLyric: String): List<LyricEntry> {
        return parseNeteaseLyricsAuto(rawLyric)
    }

    internal fun clearAmllLyricsCache() {
        amllLyricsCache.evictAll()
    }

    internal fun clearLyricsCaches() {
        withLyricsCacheWriteLock {
            lyricsCacheGeneration.incrementAndGet()
            amllLyricsCache.evictAll()
            LocalMediaSupport.clearLyricsLookupCache()
        }
    }

    internal fun clearLyricsCaches(
        neteaseLyricsCache: LruCache<Long, NeteaseLyricsCacheEntry>,
        ytMusicLyricsCache: LruCache<String, YouTubeMusicLyricsCacheEntry>
    ) {
        withLyricsCacheWriteLock {
            lyricsCacheGeneration.incrementAndGet()
            amllLyricsCache.evictAll()
            LocalMediaSupport.clearLyricsLookupCache()
            neteaseLyricsCache.evictAll()
            ytMusicLyricsCache.evictAll()
        }
    }

    internal fun clearPersistentLyricCache(application: Application) {
        withLyricsCacheWriteLock {
            lyricsCacheGeneration.incrementAndGet()
            runCatching {
                lyricsCacheDirectory(application).deleteRecursively()
            }.onFailure {
                NPLogger.w("NERI-PlayerManager", "清理歌词缓存失败: ${it.message}")
            }
        }
    }

    private fun <T> withLyricsCacheReadLock(block: () -> T): T {
        val lock = lyricsCacheStateLock.readLock()
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private fun <T> withLyricsCacheWriteLock(block: () -> T): T {
        val lock = lyricsCacheStateLock.writeLock()
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private fun currentLyricsCacheGeneration(): Long {
        return withLyricsCacheReadLock { lyricsCacheGeneration.get() }
    }

    private fun buildYouTubeMusicLyricsCacheKey(song: SongItem): String {
        return "${song.id}:external-lyrics-v4"
    }

    internal fun buildYouTubeMusicExternalLyricMatchCacheKey(song: SongItem): String {
        return buildString {
            append(song.stableKey())
            append('|')
            append(song.name.trim())
            append('|')
            append(song.artist.trim())
            append('|')
            append(song.album.trim())
            append('|')
            append(song.durationMs)
            append("|sources=")
            append(automaticYouTubeExternalLyricSourceOrder.joinToString(",") { it.name })
        }
    }

    private fun getUsableYouTubeMusicLyricsCacheEntry(
        cacheKey: String,
        ytMusicLyricsCache: LruCache<String, YouTubeMusicLyricsCacheEntry>
    ): YouTubeMusicLyricsCacheEntry? {
        val cached = ytMusicLyricsCache.get(cacheKey) ?: return null
        val sanitized = sanitizeYouTubeMusicLyricsCacheEntry(cached)
        if (sanitized == null) {
            ytMusicLyricsCache.remove(cacheKey)
            NPLogger.w("NERI-PlayerManager", "Discarded cached collapsed YouTube lyrics")
            return null
        }
        if (sanitized == cached) {
            return cached
        }
        ytMusicLyricsCache.put(cacheKey, sanitized)
        NPLogger.w("NERI-PlayerManager", "Discarded cached collapsed YouTube lyric translation")
        return sanitized
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
        return parseSafeLyricEntries(
            rawLyric = rawLyric,
            durationMs = 0L,
            logPrefix = logPrefix
        ).orEmpty()
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
            translatedLyricText = translatedLyric,
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
            ),
            rawResponse = rawResponse
        )
    }

    internal suspend fun getOrLoadNeteaseLyricsCacheEntry(
        songId: Long,
        neteaseLyricsCache: LruCache<Long, NeteaseLyricsCacheEntry>,
        loader: suspend (Long) -> String
    ): NeteaseLyricsCacheEntry {
        return getOrLoadNeteaseLyricsCacheEntry(
            songId = songId,
            neteaseLyricsCache = LruNeteaseLyricsCacheStore(neteaseLyricsCache),
            loader = loader
        )
    }

    internal suspend fun getOrLoadNeteaseLyricsCacheEntry(
        songId: Long,
        neteaseLyricsCache: NeteaseLyricsCacheStore,
        loader: suspend (Long) -> String
    ): NeteaseLyricsCacheEntry {
        neteaseLyricsCache.get(songId)?.let { cached ->
            NPLogger.d("NERI-PlayerManager", "Using cached NetEase lyrics for songId=$songId")
            scheduleNeteaseLyricsRefresh(songId, cached, neteaseLyricsCache, loader)
            return cached
        }

        val loadLock = neteaseColdLoadLocks.computeIfAbsent(songId) { Mutex() }
        return try {
            loadLock.withLock {
                neteaseLyricsCache.get(songId)?.let { return@withLock it }

                val persistedGeneration = currentLyricsCacheGeneration()
                val persisted = readPersistedNeteaseLyricsEntry(songId)
                if (
                    persisted != null &&
                    currentLyricsCacheGeneration() == persistedGeneration &&
                    writeNeteaseLyricsEntryIfCurrent(
                        songId = songId,
                        cache = neteaseLyricsCache,
                        entry = persisted,
                        expectedGeneration = persistedGeneration
                    )
                ) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "Using persisted NetEase lyrics for songId=$songId"
                    )
                    scheduleNeteaseLyricsRefresh(songId, persisted, neteaseLyricsCache, loader)
                    return@withLock persisted
                }

                val generation = currentLyricsCacheGeneration()
                val entry = buildNeteaseLyricsCacheEntry(loader(songId))
                writeNeteaseLyricsEntryIfCurrent(
                    songId = songId,
                    cache = neteaseLyricsCache,
                    entry = entry,
                    expectedGeneration = generation
                )
                entry
            }
        } finally {
            if (!loadLock.isLocked) {
                neteaseColdLoadLocks.remove(songId, loadLock)
            }
        }
    }

    private fun scheduleNeteaseLyricsRefresh(
        songId: Long,
        cached: NeteaseLyricsCacheEntry,
        cache: NeteaseLyricsCacheStore,
        loader: suspend (Long) -> String
    ) {
        if (!AppContainer.isInitialized()) {
            return
        }
        if (!neteaseRefreshInFlight.add(songId)) {
            return
        }
        val generation = currentLyricsCacheGeneration()
        AppContainer.launchBackgroundIo {
            try {
                val refreshed = buildNeteaseLyricsCacheEntry(loader(songId))
                if (
                    !sameNeteaseLyrics(cached, refreshed)
                ) {
                    if (
                        writeNeteaseLyricsEntryIfCurrent(
                            songId = songId,
                            cache = cache,
                            entry = refreshed,
                            expectedGeneration = generation
                        )
                    ) {
                        NPLogger.d(
                            "NERI-PlayerManager",
                            "Updated NetEase lyric cache for songId=$songId"
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logNeteaseLyricLoadFailure("refreshNeteaseLyrics", error)
            } finally {
                neteaseRefreshInFlight.remove(songId)
            }
        }
    }

    private fun sameNeteaseLyrics(
        first: NeteaseLyricsCacheEntry,
        second: NeteaseLyricsCacheEntry
    ): Boolean {
        return first.preferredLyricText == second.preferredLyricText &&
            first.translatedLyricText == second.translatedLyricText &&
            first.romanizedLyricText == second.romanizedLyricText &&
            first.preferredLyricEntries == second.preferredLyricEntries &&
            first.translatedLyricEntries == second.translatedLyricEntries &&
            first.romanizedLyricEntries == second.romanizedLyricEntries
    }

    private fun persistedNeteaseLyricsFile(songId: Long): File {
        return File(lyricsCacheDirectory(AppContainer.applicationContext), "netease_$songId.json")
    }

    private fun writeNeteaseLyricsEntryIfCurrent(
        songId: Long,
        cache: NeteaseLyricsCacheStore,
        entry: NeteaseLyricsCacheEntry,
        expectedGeneration: Long
    ): Boolean {
        return withLyricsCacheWriteLock {
            if (lyricsCacheGeneration.get() != expectedGeneration) {
                false
            } else {
                cache.put(songId, entry)
                persistNeteaseLyricsEntry(songId, entry)
                true
            }
        }
    }

    private fun readPersistedNeteaseLyricsEntry(songId: Long): NeteaseLyricsCacheEntry? {
        if (!AppContainer.isInitialized()) {
            return null
        }
        return withLyricsCacheReadLock {
            val file = persistedNeteaseLyricsFile(songId)
            if (!file.isFile || file.length() <= 0L) {
                return@withLyricsCacheReadLock null
            }
            runCatching {
                buildNeteaseLyricsCacheEntry(file.readText(Charsets.UTF_8))
            }.onFailure {
                NPLogger.w("NERI-PlayerManager", "读取持久化歌词缓存失败: ${it.message}")
            }.getOrNull()
        }
    }

    private fun persistNeteaseLyricsEntry(
        songId: Long,
        entry: NeteaseLyricsCacheEntry
    ) {
        if (entry.rawResponse.isBlank() || !AppContainer.isInitialized()) {
            return
        }
        runCatching {
            val target = persistedNeteaseLyricsFile(songId)
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, ".${target.name}.tmp")
            temporary.writeText(entry.rawResponse, Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                target.writeText(entry.rawResponse, Charsets.UTF_8)
                temporary.delete()
            }
        }.onFailure {
            NPLogger.w("NERI-PlayerManager", "写入持久化歌词缓存失败: ${it.message}")
        }
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
        durationMs: Long,
        logPrefix: String
    ): List<LyricEntry>? {
        return when (resolveLocalLyricOverrideState(rawLyric)) {
            LocalLyricOverrideState.ABSENT -> null
            LocalLyricOverrideState.CLEARED -> emptyList()
            LocalLyricOverrideState.PRESENT -> parseSafeLyricEntries(
                rawLyric = rawLyric!!,
                durationMs = durationMs,
                logPrefix = logPrefix
            )
        }
    }

    private fun parseSafeLyricEntries(
        rawLyric: String,
        durationMs: Long,
        logPrefix: String
    ): List<LyricEntry>? {
        val recoveredPlainLyrics = extractPlainLyricsFromCollapsedTimedLyrics(rawLyric)
        if (recoveredPlainLyrics != null) {
            if (durationMs <= 0L) {
                NPLogger.w("NERI-PlayerManager", "$logPrefix: 已拒绝伪同步歌词")
                return null
            }
            NPLogger.w("NERI-PlayerManager", "$logPrefix: 已将伪同步歌词降级为普通歌词")
            return convertPlainLyricsToEntries(recoveredPlainLyrics, durationMs)
        }
        return try {
            parseBestLyricEntries(rawLyric).takeUnless(::hasCollapsedLyricEntryTimeline)
        } catch (error: Exception) {
            NPLogger.w("NERI-PlayerManager", "$logPrefix: ${error.message}")
            null
        }
    }

    private fun parseMatchedExternalLyricEntries(
        rawLyric: String,
        durationMs: Long,
        logPrefix: String
    ): List<LyricEntry> {
        parseSafeLyricEntries(
            rawLyric = rawLyric,
            durationMs = durationMs,
            logPrefix = logPrefix
        )?.takeIf { it.isNotEmpty() }?.let { return it }
        if (hasLrcTimestamp(rawLyric) || rawLyric.trimStart().startsWith("<")) {
            return emptyList()
        }
        return convertPlainLyricsToEntries(rawLyric, durationMs)
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
        editableLyricsMatcher: EditableLyricsMatcher,
        ytMusicLyricsCache: LruCache<String, YouTubeMusicLyricsCacheEntry>,
        biliSourceTag: String
    ): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            val isYouTubeMusicTrack = isYouTubeMusicSong(song)
            val isManagedLocalDownload = isManagedLocalDownloadForSong(application, song)
            val canReadManagedDownloadLyrics = shouldReadManagedDownloadLyrics(
                song = song,
                isManagedLocalDownload = isManagedLocalDownload
            )
            val managedLyrics = if (isManagedLocalDownload) {
                AudioDownloadManager.getLyricsBundleFast(
                    context = application,
                    song = song,
                    allowColdSafProbe = false
                )
            } else {
                null
            }
            val localLyrics = if (song.isLocalSong() && !isManagedLocalDownload) {
                LocalMediaSupport.inspectLyricsFast(
                    context = application,
                    song = song,
                    includeEmbeddedFallback = true
                )
            } else {
                null
            }
            val localTranslatedLyric = localLyrics?.translatedLyric
            val storedTranslatedLyric = if (song.isLocalSong()) {
                null
            } else {
                resolveStoredLyricText(
                    currentLyric = song.matchedTranslatedLyric,
                    legacyLyric = song.originalTranslatedLyric
                )
            }
            val downloadedTranslatedLyric = when {
                managedLyrics != null -> managedLyrics.translatedLyric
                canReadManagedDownloadLyrics -> {
                    AudioDownloadManager.getTranslatedLyricContent(application, song)
                }
                else -> null
            }
            val selectedTranslatedLyric = resolveLocalFirstLyricText(
                localLyric = localTranslatedLyric,
                storedLyric = storedTranslatedLyric,
                downloadedLyric = downloadedTranslatedLyric
            )
            val deferredStoredTranslation = if (isYouTubeMusicTrack) {
                storedTranslatedLyric?.let(::extractPlainLyricsFromCollapsedTimedLyrics)
            } else {
                null
            }
            if (localTranslatedLyric != null) {
                parseLocalLyricOverride(
                    rawLyric = localTranslatedLyric,
                    durationMs = song.durationMs,
                    logPrefix = "本地翻译歌词解析失败"
                )?.let { return@withContext it }
            } else if (deferredStoredTranslation == null) {
                parseLocalLyricOverride(
                    rawLyric = selectedTranslatedLyric,
                    durationMs = song.durationMs,
                    logPrefix = "本地翻译歌词解析失败"
                )?.let { return@withContext it }
            } else {
                NPLogger.w("NERI-PlayerManager", "已忽略 YouTube 全零翻译时间轴: ${song.name}")
            }
            val deferredDownloadedTranslation = if (isYouTubeMusicTrack) {
                downloadedTranslatedLyric?.let(::extractPlainLyricsFromCollapsedTimedLyrics)
            } else {
                null
            }
            if (deferredDownloadedTranslation == null) {
                parseLocalLyricOverride(
                    rawLyric = downloadedTranslatedLyric,
                    durationMs = song.durationMs,
                    logPrefix = "本地翻译歌词读取失败"
                )?.let { return@withContext it }
            } else {
                NPLogger.w("NERI-PlayerManager", "已忽略下载的 YouTube 全零翻译时间轴: ${song.name}")
            }

            if (!shouldLoadRemoteLyrics(song)) {
                return@withContext emptyList()
            }
            if (isYouTubeMusicTrack) {
                val storedLyric = resolveStoredLyricText(
                    currentLyric = song.matchedLyric,
                    legacyLyric = song.originalLyric
                )
                val downloadedLyric = if (canReadManagedDownloadLyrics) {
                    AudioDownloadManager.getLyricContent(application, song)
                } else {
                    null
                }
                if (
                    shouldBlockExternalYouTubeMusicTranslation(storedLyric) ||
                    shouldBlockExternalYouTubeMusicTranslation(downloadedLyric)
                ) {
                    return@withContext emptyList()
                }
                return@withContext getYouTubeMusicTranslatedLyrics(
                    song = song,
                    editableLyricsMatcher = editableLyricsMatcher,
                    ytMusicLyricsCache = ytMusicLyricsCache
                )
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
        application: Application,
        neteaseClient: NeteaseClient,
        neteaseLyricsCache: LruCache<Long, NeteaseLyricsCacheEntry>,
        biliSourceTag: String
    ): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            val isManagedLocalDownload = isManagedLocalDownloadForSong(application, song)
            val canReadManagedDownloadLyrics = shouldReadManagedDownloadLyrics(
                song = song,
                isManagedLocalDownload = isManagedLocalDownload
            )
            val managedLyrics = if (isManagedLocalDownload) {
                AudioDownloadManager.getLyricsBundleFast(
                    context = application,
                    song = song,
                    allowColdSafProbe = false
                )
            } else {
                null
            }
            val localRomanizedLyric = if (song.isLocalSong() && !isManagedLocalDownload) {
                LocalMediaSupport.inspectLyricsFast(
                    context = application,
                    song = song,
                    includeEmbeddedFallback = true
                ).romanizedLyric
            } else {
                null
            }
            localRomanizedLyric?.let { rawLyric ->
                parseLocalLyricOverride(
                    rawLyric = rawLyric,
                    durationMs = song.durationMs,
                    logPrefix = "本地音译歌词读取失败"
                )?.let { return@withContext it }
            }
            val downloadedRomanizedLyric = when {
                managedLyrics != null -> managedLyrics.romanizedLyric
                canReadManagedDownloadLyrics -> {
                    AudioDownloadManager.getRomanizedLyricContent(application, song)
                }
                else -> null
            }
            downloadedRomanizedLyric?.let { rawLyric ->
                parseLocalLyricOverride(
                    rawLyric = rawLyric,
                    durationMs = song.durationMs,
                    logPrefix = "本地音译歌词读取失败"
                )?.let { return@withContext it }
            }
            if (!shouldLoadRemoteLyrics(song)) {
                return@withContext emptyList()
            }
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
        editableLyricsMatcher: EditableLyricsMatcher,
        amllTtmlClient: AmllTtmlClient,
        amllLyricsEnabled: Boolean,
        ytMusicLyricsCache: LruCache<String, YouTubeMusicLyricsCacheEntry>,
        biliSourceTag: String
    ): List<LyricEntry> {
        return withContext(Dispatchers.IO) {
            val isYouTubeMusicTrack = isYouTubeMusicSong(song)
            val isManagedLocalDownload = isManagedLocalDownloadForSong(application, song)
            val canReadManagedDownloadLyrics = shouldReadManagedDownloadLyrics(
                song = song,
                isManagedLocalDownload = isManagedLocalDownload
            )
            val managedLyrics = if (isManagedLocalDownload) {
                AudioDownloadManager.getLyricsBundleFast(
                    context = application,
                    song = song,
                    allowColdSafProbe = false
                )
            } else {
                null
            }
            val localLyric = if (song.isLocalSong() && !isManagedLocalDownload) {
                LocalMediaSupport.inspectLyricsFast(
                    context = application,
                    song = song,
                    includeEmbeddedFallback = true
                ).lyric
            } else {
                null
            }
            val storedLyric = if (song.isLocalSong()) {
                null
            } else {
                resolveStoredLyricText(
                    currentLyric = song.matchedLyric,
                    legacyLyric = song.originalLyric
                )
            }
            val downloadedLyric = when {
                managedLyrics != null -> managedLyrics.lyric
                canReadManagedDownloadLyrics -> {
                    AudioDownloadManager.getLyricContent(application, song)
                }
                else -> null
            }
            val selectedLyric = resolveLocalFirstLyricText(
                localLyric = localLyric,
                storedLyric = storedLyric,
                downloadedLyric = downloadedLyric
            )
            var deferredCollapsedLyrics: String? = null
            when (resolveLocalLyricOverrideState(selectedLyric)) {
                LocalLyricOverrideState.CLEARED -> return@withContext emptyList()
                LocalLyricOverrideState.PRESENT -> {
                    val recoveredPlainLyrics = if (isYouTubeMusicTrack) {
                        extractPlainLyricsFromCollapsedTimedLyrics(selectedLyric!!)
                    } else {
                        null
                    }
                    if (recoveredPlainLyrics != null) {
                        deferredCollapsedLyrics = recoveredPlainLyrics
                        NPLogger.w("NERI-PlayerManager", "已暂缓 YouTube 全零歌词时间轴: ${song.name}")
                    } else {
                        parseLocalLyricOverride(
                            rawLyric = selectedLyric,
                            durationMs = song.durationMs,
                            logPrefix = "匹配歌词解析失败"
                        )?.let { entries ->
                            return@withContext entries
                        }
                    }
                }
                LocalLyricOverrideState.ABSENT -> Unit
            }
            val recoveredDownloadedPlainLyrics = if (isYouTubeMusicTrack) {
                downloadedLyric?.let(::extractPlainLyricsFromCollapsedTimedLyrics)
            } else {
                null
            }
            if (recoveredDownloadedPlainLyrics != null) {
                deferredCollapsedLyrics = deferredCollapsedLyrics ?: recoveredDownloadedPlainLyrics
                NPLogger.w("NERI-PlayerManager", "已暂缓下载的 YouTube 全零歌词时间轴: ${song.name}")
            } else {
                parseLocalLyricOverride(
                    rawLyric = downloadedLyric,
                    durationMs = song.durationMs,
                    logPrefix = "本地歌词读取失败"
                )?.let { entries ->
                    if (entries.isEmpty()) {
                        return@withContext emptyList()
                    }
                    return@withContext entries
                }
            }
            if (song.isLocalSong()) {
                return@withContext emptyList()
            }
            if (isYouTubeMusicTrack) {
                return@withContext getYouTubeMusicLyrics(
                    song = song,
                    youtubeMusicClient = youtubeMusicClient,
                    lrcLibClient = lrcLibClient,
                    editableLyricsMatcher = editableLyricsMatcher,
                    ytMusicLyricsCache = ytMusicLyricsCache,
                    fallbackPlainLyrics = deferredCollapsedLyrics
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
        editableLyricsMatcher: EditableLyricsMatcher,
        ytMusicLyricsCache: LruCache<String, YouTubeMusicLyricsCacheEntry>,
        fallbackPlainLyrics: String?
    ): List<LyricEntry> {
        val cacheKey = buildYouTubeMusicLyricsCacheKey(song)
        val externalMatchCacheKey = buildYouTubeMusicExternalLyricMatchCacheKey(song)
        getUsableYouTubeMusicLyricsCacheEntry(cacheKey, ytMusicLyricsCache)?.let { cached ->
            if (
                cached.externalMatchCacheKey != null &&
                cached.externalMatchCacheKey != externalMatchCacheKey
            ) {
                ytMusicLyricsCache.remove(cacheKey)
            } else {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "Using cached YT Music lyrics for '" + song.name + "'"
                )
                return cached.lyrics
            }
        }

        val videoId = extractYouTubeMusicVideoId(song.mediaUri)
        return withContext(Dispatchers.IO) {
            try {
                val externalLyrics = loadDurationMatchedExternalLyrics(
                    song = song,
                    editableLyricsMatcher = editableLyricsMatcher,
                    ytMusicLyricsCache = ytMusicLyricsCache,
                    cacheKey = cacheKey,
                    externalMatchCacheKey = externalMatchCacheKey
                )
                if (externalLyrics != null) {
                    ytMusicLyricsCache.put(
                        cacheKey,
                        YouTubeMusicLyricsCacheEntry(
                            lyrics = externalLyrics.lyrics,
                            translatedLyrics = externalLyrics.translatedLyrics,
                            translationLookupComplete = true,
                            externalMatchCacheKey = externalMatchCacheKey,
                            externalMatchSource = externalLyrics.source,
                            externalMatchDurationDeltaMs = externalLyrics.durationDeltaMs
                        )
                    )
                    return@withContext externalLyrics.lyrics
                }

                val lrcLibResult = try {
                    val durationSeconds = song.durationMs / 1_000L
                    lrcLibClient.getLyrics(
                        trackName = song.name,
                        artistName = song.artist,
                        durationSeconds = durationSeconds
                    ) ?: lrcLibClient.searchLyrics(
                        trackName = song.name,
                        artistName = song.artist,
                        durationSeconds = durationSeconds
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    NPLogger.d("NERI-PlayerManager", "LRCLIB lookup failed: ${error.message}")
                    null
                }

                val syncedLyrics = lrcLibResult?.syncedLyrics?.takeIf { it.isNotBlank() }
                if (syncedLyrics != null) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "Using LRCLIB synced lyrics for '" + song.name + "'"
                    )
                    val entries = parseSafeLyricEntries(
                        rawLyric = syncedLyrics,
                        durationMs = song.durationMs,
                        logPrefix = "LRCLIB 歌词解析失败"
                    ).orEmpty()
                    if (entries.isNotEmpty()) {
                        ytMusicLyricsCache.put(
                            cacheKey,
                            YouTubeMusicLyricsCacheEntry(
                                lyrics = entries,
                                translationLookupComplete = true,
                                externalMatchCacheKey = null
                            )
                        )
                        return@withContext entries
                    }
                }

                val plainLyrics = lrcLibResult?.plainLyrics?.takeIf { it.isNotBlank() }
                val recoveredPlainLyrics = plainLyrics?.takeIf {
                    lrcLibResult.plainLyricsRecoveredFromCollapsedTimeline
                }
                if (plainLyrics != null && recoveredPlainLyrics == null) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "Using LRCLIB plain lyrics for '" + song.name + "'"
                    )
                    val entries = convertPlainLyricsToEntries(
                        plainLyrics,
                        song.durationMs
                    )
                    if (entries.isNotEmpty()) {
                        ytMusicLyricsCache.put(
                            cacheKey,
                            YouTubeMusicLyricsCacheEntry(
                                lyrics = entries,
                                translationLookupComplete = true,
                                externalMatchCacheKey = null
                            )
                        )
                        return@withContext entries
                    }
                }

                if (!videoId.isNullOrBlank()) {
                    val youtubeLyrics = try {
                        youtubeMusicClient.getLyrics(videoId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        NPLogger.d(
                            "NERI-PlayerManager",
                            "YouTube Music lyric lookup failed: " + error.message
                        )
                        null
                    }
                    val lyricsText = youtubeLyrics?.lyrics.orEmpty()
                    if (lyricsText.isNotBlank()) {
                        NPLogger.d(
                            "NERI-PlayerManager",
                            "Using YouTube Music API lyrics for '" + song.name + "'"
                        )
                        val entries = parseMatchedExternalLyricEntries(
                            rawLyric = lyricsText,
                            durationMs = song.durationMs,
                            logPrefix = "YouTube Music 歌词解析失败"
                        )
                        if (entries.isNotEmpty()) {
                            ytMusicLyricsCache.put(
                                cacheKey,
                                YouTubeMusicLyricsCacheEntry(
                                    lyrics = entries,
                                    translationLookupComplete = true,
                                    externalMatchCacheKey = null
                                )
                            )
                            return@withContext entries
                        }
                    }
                }

                if (recoveredPlainLyrics != null) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "Using recovered LRCLIB plain lyrics for '" + song.name + "'"
                    )
                    val entries = convertPlainLyricsToEntries(
                        recoveredPlainLyrics,
                        song.durationMs
                    )
                    return@withContext entries
                }

                if (fallbackPlainLyrics != null) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "Using deferred YouTube plain lyrics for '" + song.name + "'"
                    )
                    return@withContext convertPlainLyricsToEntries(
                        fallbackPlainLyrics,
                        song.durationMs
                    )
                }
                emptyList()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.e("NERI-PlayerManager", "getYouTubeMusicLyrics failed: ${error.message}", error)
                emptyList()
            }
        }
    }

    private suspend fun getYouTubeMusicTranslatedLyrics(
        song: SongItem,
        editableLyricsMatcher: EditableLyricsMatcher,
        ytMusicLyricsCache: LruCache<String, YouTubeMusicLyricsCacheEntry>
    ): List<LyricEntry> {
        val cacheKey = buildYouTubeMusicLyricsCacheKey(song)
        val cached = getUsableYouTubeMusicLyricsCacheEntry(cacheKey, ytMusicLyricsCache)
        if (cached != null) {
            val externalMatchCacheKey = buildYouTubeMusicExternalLyricMatchCacheKey(song)
            if (cached.externalMatchCacheKey == externalMatchCacheKey) {
                return cached.translatedLyrics
            }
            if (cached.externalMatchCacheKey != null) {
                ytMusicLyricsCache.remove(cacheKey)
            } else {
                val resolvedCacheEntry = resolveYouTubeMusicTranslationCacheEntry(
                    cached = cached,
                    externalLyrics = null
                )!!
                if (resolvedCacheEntry != cached) {
                    ytMusicLyricsCache.put(cacheKey, resolvedCacheEntry)
                }
                return resolvedCacheEntry.translatedLyrics
            }
        }

        val externalMatchCacheKey = buildYouTubeMusicExternalLyricMatchCacheKey(song)
        val externalLyrics = loadDurationMatchedExternalLyrics(
            song = song,
            editableLyricsMatcher = editableLyricsMatcher,
            ytMusicLyricsCache = ytMusicLyricsCache,
            cacheKey = cacheKey,
            externalMatchCacheKey = externalMatchCacheKey
        )
        val resolvedCacheEntry = resolveYouTubeMusicTranslationCacheEntry(
            cached = null,
            externalLyrics = externalLyrics
        ) ?: return emptyList()
        ytMusicLyricsCache.put(
            cacheKey,
            if (externalLyrics == null) {
                resolvedCacheEntry
            } else {
                resolvedCacheEntry.copy(
                    externalMatchCacheKey = externalMatchCacheKey,
                    externalMatchSource = externalLyrics.source,
                    externalMatchDurationDeltaMs = externalLyrics.durationDeltaMs
                )
            }
        )
        return resolvedCacheEntry.translatedLyrics
    }

    private suspend fun loadDurationMatchedExternalLyrics(
        song: SongItem,
        editableLyricsMatcher: EditableLyricsMatcher,
        ytMusicLyricsCache: LruCache<String, YouTubeMusicLyricsCacheEntry>,
        cacheKey: String,
        externalMatchCacheKey: String
    ): DurationMatchedExternalLyrics? {
        if (song.durationMs <= 0L) {
            return null
        }
        getUsableYouTubeMusicLyricsCacheEntry(cacheKey, ytMusicLyricsCache)
            ?.let { cached ->
                val cachedMatch = resolveCachedYouTubeExternalLyricMatch(
                    cached = cached,
                    externalMatchCacheKey = externalMatchCacheKey
                ) ?: return@let
                NPLogger.d(
                    "NERI-PlayerManager",
                    "Using cached external lyrics match for '" + song.name + "'"
                )
                return cachedMatch
            }
        val request = EditableLyricMatchRequest(
            keyword = listOf(song.name, song.artist)
                .filter { it.isNotBlank() }
                .joinToString(" "),
            trackName = song.name,
            artistName = song.artist,
            albumName = song.album,
            durationMs = song.durationMs,
            sources = automaticYouTubeExternalLyricSources
        )
        val resolvedLyrics = loadFirstUsableAutomaticExternalLyrics(
            request = request,
            expectedDurationMs = song.durationMs,
            expectedTitle = song.name,
            expectedArtist = song.artist
        ) { source ->
            editableLyricsMatcher.matchHighConfidenceLyricsForSource(request, source)
        }
        resolvedLyrics?.let { matchedLyrics ->
            NPLogger.d(
                "NERI-PlayerManager",
                "Using " + matchedLyrics.source + " lyrics for '" + song.name +
                    "', durationDeltaMs=" + matchedLyrics.durationDeltaMs +
                    ", hasTranslation=" + matchedLyrics.translatedLyrics.isNotEmpty()
            )
        }
        return resolvedLyrics
    }

    internal suspend fun loadFirstUsableAutomaticExternalLyrics(
        request: EditableLyricMatchRequest,
        expectedDurationMs: Long,
        expectedTitle: String,
        expectedArtist: String,
        sourceLoader: suspend (EditableLyricMatchSource) -> List<RankedEditableLyricMatch>
    ): DurationMatchedExternalLyrics? {
        for (source in automaticYouTubeExternalLyricSourceOrder) {
            if (source !in request.sources) continue
            val selectedLyrics = selectFirstUsableAutomaticExternalLyrics(
                expectedDurationMs = expectedDurationMs,
                expectedTitle = expectedTitle,
                expectedArtist = expectedArtist,
                matches = sourceLoader(source)
            )
            if (selectedLyrics != null) {
                return selectedLyrics
            }
        }
        return null
    }

    internal fun resolveCachedYouTubeExternalLyricMatch(
        cached: YouTubeMusicLyricsCacheEntry,
        externalMatchCacheKey: String
    ): DurationMatchedExternalLyrics? {
        if (cached.externalMatchCacheKey != externalMatchCacheKey) {
            return null
        }
        val source = cached.externalMatchSource ?: return null
        return DurationMatchedExternalLyrics(
            lyrics = cached.lyrics,
            translatedLyrics = cached.translatedLyrics,
            source = source,
            durationDeltaMs = cached.externalMatchDurationDeltaMs
        )
    }

    internal fun selectDurationMatchedExternalLyrics(
        expectedDurationMs: Long,
        expectedTitle: String,
        expectedArtist: String,
        candidates: List<EditableLyricMatchCandidate>
    ): DurationMatchedExternalLyrics? {
        val matches = candidates.map { candidate ->
            RankedEditableLyricMatch(
                candidate = candidate,
                score = 0,
                durationDeltaMs = if (expectedDurationMs > 0L && candidate.durationMs > 0L) {
                    kotlin.math.abs(expectedDurationMs - candidate.durationMs)
                } else {
                    null
                },
                confidence = EditableLyricMatchConfidence.HIGH
            )
        }
        return selectRankedDurationMatchedExternalLyrics(
            expectedDurationMs = expectedDurationMs,
            expectedTitle = expectedTitle,
            expectedArtist = expectedArtist,
            matches = matches
        )
    }

    internal fun selectRankedDurationMatchedExternalLyrics(
        expectedDurationMs: Long,
        expectedTitle: String,
        expectedArtist: String,
        matches: List<RankedEditableLyricMatch>
    ): DurationMatchedExternalLyrics? {
        if (
            expectedDurationMs <= 0L ||
            expectedTitle.isBlank() ||
            expectedArtist.isBlank()
        ) {
            return null
        }
        return selectFirstUsableAutomaticExternalLyrics(
            expectedDurationMs = expectedDurationMs,
            expectedTitle = expectedTitle,
            expectedArtist = expectedArtist,
            matches = matches.sortedWith(automaticExternalLyricMatchComparator())
        )
    }

    internal fun selectFirstUsableAutomaticExternalLyrics(
        expectedDurationMs: Long,
        expectedTitle: String,
        expectedArtist: String,
        matches: List<RankedEditableLyricMatch>
    ): DurationMatchedExternalLyrics? {
        if (
            expectedDurationMs <= 0L ||
            expectedTitle.isBlank() ||
            expectedArtist.isBlank()
        ) {
            return null
        }
        for (match in matches) {
            if (match.confidence != EditableLyricMatchConfidence.HIGH) {
                continue
            }
            val candidate = match.candidate
            if (
                candidate.durationMs <= 0L ||
                !isExternalLyricDurationCompatible(expectedDurationMs, candidate.durationMs)
            ) {
                continue
            }
            if (!isReliableLyricMatchIdentity(
                    expectedTitle = expectedTitle,
                    expectedArtist = expectedArtist,
                    candidateTitle = candidate.title,
                    candidateArtist = candidate.artist
                )
            ) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "Rejected duration-compatible lyrics with mismatched identity: " +
                        "expected='$expectedTitle' by '$expectedArtist', " +
                        "candidate='${candidate.title}' by '${candidate.artist}'"
                )
                continue
            }
            val entries = parseMatchedExternalLyricEntries(
                rawLyric = candidate.lyrics,
                durationMs = expectedDurationMs,
                logPrefix = candidate.source.name + " 歌词解析失败"
            )
            if (entries.isEmpty()) {
                continue
            }
            val translatedEntries = candidate.translatedLyrics
                ?.takeIf { it.isNotBlank() }
                ?.let { translatedLyrics ->
                    parseMatchedExternalLyricEntries(
                        rawLyric = translatedLyrics,
                        durationMs = expectedDurationMs,
                        logPrefix = candidate.source.name + " 翻译歌词解析失败"
                    )
                }
                .orEmpty()
            return DurationMatchedExternalLyrics(
                lyrics = entries,
                translatedLyrics = translatedEntries,
                source = candidate.source,
                durationDeltaMs = kotlin.math.abs(expectedDurationMs - candidate.durationMs)
            )
        }
        return null
    }
}

private val automaticYouTubeExternalLyricSourceOrder = listOf(
    EditableLyricMatchSource.KUGOU,
    EditableLyricMatchSource.CLOUD_MUSIC,
    EditableLyricMatchSource.QQ_MUSIC,
    EditableLyricMatchSource.LRCLIB
)

private val automaticYouTubeExternalLyricSources = automaticYouTubeExternalLyricSourceOrder.toSet()

private fun automaticExternalLyricMatchComparator(): Comparator<RankedEditableLyricMatch> {
    return compareByDescending<RankedEditableLyricMatch> {
        editableLyricMatchSourcePriority(it.candidate.source)
    }
        .thenByDescending { it.confidence.rank }
        .thenBy { it.durationDeltaMs ?: Long.MAX_VALUE }
        .thenByDescending { it.score }
        .thenBy { it.candidate.title }
}
