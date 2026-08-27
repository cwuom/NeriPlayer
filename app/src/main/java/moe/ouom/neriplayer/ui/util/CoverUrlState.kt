package moe.ouom.neriplayer.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.SystemClock
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.isUsableCoverReference
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.local.playlist.model.LocalArtistSummary
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.storage.LocalAssetInvalidationBus
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import java.util.LinkedHashMap
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
private val coverProbeDispatcher = Dispatchers.IO.limitedParallelism(4)
@OptIn(ExperimentalCoroutinesApi::class)
private val embeddedCoverResolutionDispatcher = Dispatchers.IO.limitedParallelism(2)
private const val UI_COVER_MEMORY_CACHE_LIMIT = 2048
private const val PLAYLIST_COVER_FALLBACK_IDLE_DELAY_MS = 96L
private const val PLAYLIST_COVER_SIGNATURE_CANDIDATE_LIMIT = 32
private const val PLAYLIST_COVER_IMMEDIATE_CANDIDATE_LIMIT = 24
private const val PLAYLIST_COVER_FALLBACK_CANDIDATE_LIMIT = 12
private const val COVER_PERF_LOG_LIMIT = 48
private const val COVER_SLOW_LOG_THRESHOLD_MS = 120L
private const val FAST_COVER_PROBE_CACHE_LIMIT = 2048
private const val FAST_COVER_PROBE_TTL_MS = 900L
private val coverPerfLogCount = AtomicInteger()
private data class FastCoverProbeCacheEntry(
    val coverUrl: String?,
    val checkedAtMs: Long
)

private val fastCoverProbeCache = object : LinkedHashMap<String, FastCoverProbeCacheEntry>(
    FAST_COVER_PROBE_CACHE_LIMIT,
    0.75f,
    true
) {
    override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<String, FastCoverProbeCacheEntry>
    ): Boolean = size > FAST_COVER_PROBE_CACHE_LIMIT
}

private val resolvedCoverMemoryCache = object : LinkedHashMap<String, String>(
    UI_COVER_MEMORY_CACHE_LIMIT,
    0.75f,
    true
) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
        return size > UI_COVER_MEMORY_CACHE_LIMIT
    }
}

internal fun shouldResolveSlowLocalCoverFallback(
    resolveLocalFallback: Boolean
): Boolean = resolveLocalFallback

internal fun shouldResolveEmbeddedCoverFallback(
    resolveLocalFallback: Boolean,
    allowEmbeddedCoverFallback: Boolean
): Boolean = shouldResolveSlowLocalCoverFallback(resolveLocalFallback) &&
    allowEmbeddedCoverFallback

internal fun shouldResolvePlaylistCoverFallback(
    resolveLocalFallback: Boolean,
    hasImmediateCover: Boolean
): Boolean = resolveLocalFallback && !hasImmediateCover

internal fun shouldProbeFastLocalCoverCandidate(
    isLocalSong: Boolean,
    immediateCover: String?
): Boolean = isLocalSong || immediateCover.isNullOrBlank()

internal fun resolvePrevalidatedCoverCandidate(
    primaryCoverUrl: String?,
    fallbackCoverUrl: String?
): String? {
    fun remoteCandidate(reference: String?): String? {
        val candidate = reference?.trim()?.takeIf(String::isNotBlank) ?: return null
        return candidate.takeIf {
            it.startsWith("https://", ignoreCase = true) ||
                it.startsWith("http://", ignoreCase = true)
        }
    }

    val primary = primaryCoverUrl?.trim()?.takeIf(String::isNotBlank)
    return if (primary != null) {
        remoteCandidate(primary)
    } else {
        remoteCandidate(fallbackCoverUrl)
    }
}

internal fun retainCoverDuringResolution(
    currentCover: String?,
    resolvedCover: String?
): String? {
    return resolvedCover?.takeIf(String::isNotBlank) ?: currentCover
}

internal fun finishCoverResolution(
    currentCover: String?,
    resolvedCover: String?,
    resolutionComplete: Boolean
): String? {
    if (!resolutionComplete) {
        return retainCoverDuringResolution(currentCover, resolvedCover)
    }
    return resolvedCover?.takeIf(String::isNotBlank)
}

@Composable
fun rememberSongDisplayCoverUrl(
    song: SongItem?,
    resolveLocalFallback: Boolean = true
): String? {
    val songRevisionKey = song?.stableKey().orEmpty()
    val songRevision by LocalAssetInvalidationBus
        .revisionFlow(songRevisionKey)
        .collectAsStateWithLifecycle()
    val rootGeneration by LocalAssetInvalidationBus.rootGenerationFlow
        .collectAsStateWithLifecycle()
    return rememberSongDisplayCoverUrl(
        song = song,
        resolveLocalFallback = resolveLocalFallback,
        downloadPresenceVersion = 0,
        localAssetGeneration = "$rootGeneration:$songRevision"
    )
}

@Composable
internal fun rememberSongDisplayCoverUrl(
    song: SongItem?,
    resolveLocalFallback: Boolean,
    downloadPresenceVersion: Int,
    allowEmbeddedCoverFallback: Boolean = true,
    localAssetGeneration: String? = null
): String? {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val songDisplayKey = remember(song) {
        song?.coverDisplayCacheKey()
    }
    val songKey = remember(song) {
        song?.coverResolutionKey()
    }
    val effectiveGeneration = localAssetGeneration ?: "global=$downloadPresenceVersion"
    val probeGeneration = effectiveGeneration.hashCode()
    val resolvedCacheKey = versionedCoverCacheKey(songDisplayKey, effectiveGeneration)
    var coverUrl by remember(songDisplayKey, resolvedCacheKey) {
        val cachedCover = cachedResolvedCover(resolvedCacheKey)
        val explicitSongCover = song?.displayCoverUrl()
            ?.trim()
            ?.takeIf(String::isNotBlank)
        mutableStateOf(
            if (explicitSongCover != null) {
                resolvePrevalidatedCoverCandidate(
                    primaryCoverUrl = explicitSongCover,
                    fallbackCoverUrl = null
                )
            } else {
                resolvePrevalidatedCoverCandidate(
                    primaryCoverUrl = cachedCover,
                    fallbackCoverUrl = song?.originalCoverUrl
                )
            }
        )
    }

    LaunchedEffect(
        songDisplayKey,
        songKey,
        appContext,
        effectiveGeneration,
        resolveLocalFallback,
        allowEmbeddedCoverFallback
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        var resolutionStage = "none"
        if (song == null) {
            coverUrl = null
            return@LaunchedEffect
        }

        val rawImmediateCover = song.displayCoverUrl()
        val prevalidatedCover = resolvePrevalidatedCoverCandidate(
            primaryCoverUrl = rawImmediateCover,
            fallbackCoverUrl = song.originalCoverUrl
        )
        if (!prevalidatedCover.isNullOrBlank()) {
            resolutionStage = "song-pending"
            rememberResolvedCover(resolvedCacheKey, prevalidatedCover)
            coverUrl = prevalidatedCover
        }
        val immediateCover = withContext(coverProbeDispatcher) {
            rawImmediateCover?.takeIf { isUsableCoverReference(appContext, it) }
        }
        val memoryCover = cachedResolvedCover(resolvedCacheKey)
        val prevalidatedMemoryCover = immediateCover
            ?: resolvePrevalidatedCoverCandidate(
                primaryCoverUrl = rawImmediateCover,
                fallbackCoverUrl = memoryCover
            )
        if (!prevalidatedMemoryCover.isNullOrBlank()) {
            coverUrl = prevalidatedMemoryCover
        }
        val cachedCover = withContext(coverProbeDispatcher) {
            if (
                shouldProbeFastLocalCoverCandidate(
                    isLocalSong = song.isLocalSong(),
                    immediateCover = immediateCover
                )
            ) {
                resolveCachedSongDisplayCoverUrl(
                    context = appContext,
                    song = song,
                    probeGeneration = probeGeneration
                )
            } else {
                memoryCover?.takeIf { isUsableCoverReference(appContext, it) }
                    ?: immediateCover
            }
        }
        if (!cachedCover.isNullOrBlank()) {
            resolutionStage = when {
                cachedCover == memoryCover -> "memory"
                cachedCover == immediateCover -> "song"
                else -> "local-cache"
            }
            rememberResolvedCover(resolvedCacheKey, cachedCover)
            coverUrl = retainCoverDuringResolution(coverUrl, cachedCover)
        }
        if (!shouldResolveEmbeddedCoverFallback(
                resolveLocalFallback = resolveLocalFallback,
                allowEmbeddedCoverFallback = allowEmbeddedCoverFallback
            ) || !cachedCover.isNullOrBlank()
        ) {
            if (cachedCover.isNullOrBlank() && immediateCover.isNullOrBlank()) {
                coverUrl = finishCoverResolution(coverUrl, null, resolutionComplete = true)
            }
            return@LaunchedEffect
        }

        val resolvedCover = withContext(embeddedCoverResolutionDispatcher) {
            song.displayCoverUrl(appContext, resolveLocalFallback)
                ?.takeIf { isUsableCoverReference(appContext, it) }
        }
        if (!resolvedCover.isNullOrBlank()) {
            resolutionStage = "fallback"
            rememberResolvedCover(resolvedCacheKey, resolvedCover)
            coverUrl = retainCoverDuringResolution(coverUrl, resolvedCover)
        } else {
            coverUrl = finishCoverResolution(coverUrl, null, resolutionComplete = true)
        }
        logCoverResolution(
            song = song,
            stage = resolutionStage,
            startedAt = startedAt,
            immediateCover = immediateCover,
            resolvedCover = resolvedCover
        )
    }

    return coverUrl
}

private fun logCoverResolution(
    song: SongItem,
    stage: String,
    startedAt: Long,
    immediateCover: String?,
    resolvedCover: String?
) {
    val elapsedMs = SystemClock.elapsedRealtime() - startedAt
    if (elapsedMs < COVER_SLOW_LOG_THRESHOLD_MS ||
        coverPerfLogCount.getAndIncrement() >= COVER_PERF_LOG_LIMIT
    ) {
        return
    }
    val sourceKind = when {
        song.mediaUri?.startsWith("content://", ignoreCase = true) == true -> "content"
        !song.localFilePath.isNullOrBlank() -> "file"
        else -> "metadata"
    }
    NPLogger.d(
        "LocalCoverPerf",
        "songKeyHash=${Integer.toHexString(song.stableKey().hashCode())}, " +
            "stage=$stage, elapsed=${elapsedMs}ms, " +
            "hasImmediate=${!immediateCover.isNullOrBlank()}, " +
            "hasResolved=${!resolvedCover.isNullOrBlank()}, " +
            "sourceKind=$sourceKind"
    )
}

private fun resolveCachedSongDisplayCoverUrl(
    context: android.content.Context,
    song: SongItem,
    probeGeneration: Int = 0
): String? {
    val cacheKey = fastCoverProbeCacheKey(song, probeGeneration)
    val now = SystemClock.elapsedRealtime()
    synchronized(fastCoverProbeCache) {
        fastCoverProbeCache[cacheKey]?.let { cached ->
            if (now - cached.checkedAtMs <= FAST_COVER_PROBE_TTL_MS) {
                val cachedCover = cached.coverUrl
                if (cachedCover.isNullOrBlank() || isUsableCoverReference(context, cachedCover)) {
                    return cachedCover
                }
            }
            fastCoverProbeCache.remove(cacheKey)
        }
    }

    val resolved = resolveCachedSongDisplayCoverUrlUncached(context, song)
    synchronized(fastCoverProbeCache) {
        fastCoverProbeCache[cacheKey] = FastCoverProbeCacheEntry(
            coverUrl = resolved,
            checkedAtMs = now
        )
    }
    return resolved
}

private fun resolveCachedSongDisplayCoverUrlUncached(
    context: android.content.Context,
    song: SongItem
): String? {
    fun usable(reference: String?): String? {
        return reference
            ?.takeIf { it.isNotBlank() && isFastCoverReference(it) }
            ?.takeIf { isUsableCoverReference(context, it) }
    }

    usable(song.customCoverUrl)?.let { return it }
    usable(AudioDownloadManager.peekLocalCoverUri(song))?.let { return it }
    if (song.isLocalSong()) {
        // sidecars are authoritative for local files and do not depend on MediaStore grants
        val nearbyCover = runCatching {
            LocalMediaSupport.resolveNearbyCoverUri(context, song)
        }.onFailure { error ->
            if (error is SecurityException) {
                LocalMediaSupport.invalidateSafReadCaches()
                NPLogger.w(
                    "LocalCover",
                    "SAF cover probe out of scope: song=${song.stableKey()}, " +
                        "message=${error.message}"
                )
            }
        }.getOrNull()
        usable(nearbyCover)?.let { return it }
        usable(LocalMediaSupport.peekMediaStoreAlbumArtUri(context, song))?.let { return it }
        usable(LocalMediaSupport.peekCachedEmbeddedCoverUri(context, song))?.let { return it }
    }
    usable(song.displayCoverUrl())?.let { return it }
    return usable(song.originalCoverUrl)
}

private fun fastCoverProbeCacheKey(song: SongItem, probeGeneration: Int): String {
    val localFile = song.localFilePath
        ?.takeUnless { it.startsWith("content://", ignoreCase = true) }
        ?.let(::File)
    val fileState = localFile?.let {
        "${it.length()}:${it.lastModified()}:${it.parentFile?.lastModified()}"
    }.orEmpty()
    return "${song.coverResolutionKey()}|$fileState|generation=$probeGeneration"
}

internal fun isFastCoverReference(reference: String): Boolean {
    val normalized = reference.trim()
    if (normalized.isEmpty()) return false
    if (normalized.startsWith("http://", ignoreCase = true) ||
        normalized.startsWith("https://", ignoreCase = true) ||
        normalized.startsWith("content://", ignoreCase = true)
    ) {
        return true
    }
    val uri = runCatching { normalized.toUri() }.getOrNull() ?: return false
    return when (uri.scheme?.lowercase()) {
        "file" -> uri.path?.let(::File)?.isFile == true
        null, "" -> normalized.startsWith("/") && File(normalized).isFile
        else -> true
    }
}

@Composable
fun rememberPlaylistDisplayCoverUrl(
    playlist: LocalPlaylist?,
    resolveLocalFallback: Boolean = true,
    additionalCoverCandidates: List<SongItem> = emptyList(),
    allowEmbeddedCoverFallback: Boolean = true
): String? {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsStateWithLifecycle()
    val effectivePlaylist = playlist?.takeUnless {
        it.songs.isEmpty() && LocalFilesPlaylist.isSystemPlaylist(it, appContext)
    }
    val effectiveCoverCandidates = if (effectivePlaylist == null) {
        emptyList()
    } else {
        additionalCoverCandidates
    }
    val playlistKey = effectivePlaylist?.let { playlist ->
        playlistCoverResolutionCacheKey(playlist, effectiveCoverCandidates)
    }
    val resolvedCacheKey = versionedCoverCacheKey(playlistKey, downloadPresenceVersion)
    var coverUrl by remember(playlistKey) {
        mutableStateOf(
            cachedResolvedCover(resolvedCacheKey)
                ?: effectivePlaylist?.customCoverUrl?.takeIf { it.isNotBlank() }
        )
    }

    LaunchedEffect(
        playlistKey,
        appContext,
        downloadPresenceVersion,
        resolveLocalFallback,
        allowEmbeddedCoverFallback
    ) {
        if (effectivePlaylist == null) {
            coverUrl = null
            return@LaunchedEffect
        }

        val cachedCover = cachedResolvedCover(resolvedCacheKey)
        if (!cachedCover.isNullOrBlank()) {
            val cacheIsUsable = withContext(coverProbeDispatcher) {
                isUsableCoverReference(appContext, cachedCover)
            }
            if (cacheIsUsable) {
                coverUrl = cachedCover
                return@LaunchedEffect
            }
            forgetResolvedCover(resolvedCacheKey)
        }
        val immediateCover = withContext(coverProbeDispatcher) {
            resolveImmediatePlaylistCover(
                context = appContext,
                playlist = effectivePlaylist,
                additionalCoverCandidates = effectiveCoverCandidates,
                probeGeneration = downloadPresenceVersion
            )
        }
        if (!immediateCover.isNullOrBlank()) {
            rememberResolvedCover(resolvedCacheKey, immediateCover)
            coverUrl = retainCoverDuringResolution(coverUrl, immediateCover)
        }
        if (!resolveLocalFallback || !allowEmbeddedCoverFallback) {
            if (immediateCover.isNullOrBlank()) {
                coverUrl = finishCoverResolution(coverUrl, null, resolutionComplete = true)
            }
            return@LaunchedEffect
        }
        if (
            !shouldResolvePlaylistCoverFallback(
                resolveLocalFallback = resolveLocalFallback,
                hasImmediateCover = !immediateCover.isNullOrBlank()
            )
        ) {
            return@LaunchedEffect
        }

        val resolvedCover = resolvePlaylistCoverFallbackGradually(
            context = appContext,
            playlist = effectivePlaylist,
            additionalCoverCandidates = effectiveCoverCandidates,
            probeGeneration = downloadPresenceVersion
        )
        if (!resolvedCover.isNullOrBlank()) {
            rememberResolvedCover(resolvedCacheKey, resolvedCover)
            coverUrl = resolvedCover
        } else if (immediateCover.isNullOrBlank()) {
            coverUrl = finishCoverResolution(coverUrl, null, resolutionComplete = true)
        }
    }

    return coverUrl
}

private suspend fun resolvePlaylistCoverFallbackGradually(
    context: android.content.Context,
    playlist: LocalPlaylist,
    additionalCoverCandidates: List<SongItem>,
    probeGeneration: Int
): String? {
    suspend fun resolveCandidates(candidates: Iterable<SongItem>): String? {
        for (song in candidates.asSequence().take(PLAYLIST_COVER_FALLBACK_CANDIDATE_LIMIT)) {
            currentCoroutineContext().ensureActive()
            if (!song.isLocalSong()) continue

            val fastCover = resolveCachedSongDisplayCoverUrl(
                context = context,
                song = song,
                probeGeneration = probeGeneration
            )
            if (!fastCover.isNullOrBlank()) return fastCover
            val resolvedCover = withContext(embeddedCoverResolutionDispatcher) {
                song.displayCoverUrl(
                    context = context,
                    resolveLocalMetadataFallback = true
                )?.takeIf { isUsableCoverReference(context, it) }
            }
            currentCoroutineContext().ensureActive()
            if (!resolvedCover.isNullOrBlank()) return resolvedCover

            // 每个候选之间留出时间，避免无封面的大歌单持续占用 CPU 和内存
            delay(PLAYLIST_COVER_FALLBACK_IDLE_DELAY_MS)
        }
        return null
    }

    return resolveCandidates(playlist.songs)
        ?: resolveCandidates(additionalCoverCandidates)
}

private fun resolveImmediatePlaylistCover(
    context: android.content.Context,
    playlist: LocalPlaylist,
    additionalCoverCandidates: List<SongItem>,
    probeGeneration: Int
): String? {
    playlist.customCoverUrl
        ?.takeIf {
            it.isNotBlank() &&
                isFastCoverReference(it) &&
                isUsableCoverReference(context, it)
        }
        ?.let { return it }
    return (playlist.songs.asSequence() + additionalCoverCandidates.asSequence())
        .take(PLAYLIST_COVER_IMMEDIATE_CANDIDATE_LIMIT)
        .firstNotNullOfOrNull { song ->
            resolveCachedSongDisplayCoverUrl(
                context = context,
                song = song,
                probeGeneration = probeGeneration
            )
                ?: song.displayCoverUrl()?.takeIf {
                    isFastCoverReference(it) && isUsableCoverReference(context, it)
                }
        }
}

@Composable
fun rememberLocalArtistDisplayCoverUrl(
    artist: LocalArtistSummary?,
    resolveLocalFallback: Boolean = true
): String? {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsStateWithLifecycle()
    val artistKey = remember(artist) {
        artist?.coverResolutionKey()
    }
    val resolvedCacheKey = versionedCoverCacheKey(artistKey, downloadPresenceVersion)
    var coverUrl by remember(artistKey) {
        mutableStateOf(cachedResolvedCover(resolvedCacheKey) ?: artist?.displayCoverUrl())
    }

    LaunchedEffect(artistKey, appContext, downloadPresenceVersion, resolveLocalFallback) {
        if (artist == null) {
            coverUrl = null
            return@LaunchedEffect
        }

        val immediateCover = artist.displayCoverUrl()
            ?.takeIf { isFastCoverReference(it) && isUsableCoverReference(appContext, it) }
        val cachedCover = cachedResolvedCover(resolvedCacheKey)
        if (!cachedCover.isNullOrBlank()) {
            val cacheIsUsable = withContext(coverProbeDispatcher) {
                isUsableCoverReference(appContext, cachedCover)
            }
            if (cacheIsUsable) {
                coverUrl = cachedCover
            } else {
                forgetResolvedCover(resolvedCacheKey)
            }
        }
        if (!immediateCover.isNullOrBlank()) {
            rememberResolvedCover(resolvedCacheKey, immediateCover)
            coverUrl = immediateCover
        }
        if (!resolveLocalFallback) {
            if (immediateCover.isNullOrBlank()) {
                coverUrl = finishCoverResolution(coverUrl, null, resolutionComplete = true)
            }
            return@LaunchedEffect
        }

        val resolvedCover = withContext(embeddedCoverResolutionDispatcher) {
            artist.displayCoverUrl(appContext, resolveLocalFallback)
                ?.takeIf { isUsableCoverReference(appContext, it) }
        }
        if (!resolvedCover.isNullOrBlank()) {
            rememberResolvedCover(resolvedCacheKey, resolvedCover)
            coverUrl = resolvedCover
        } else if (immediateCover.isNullOrBlank()) {
            coverUrl = finishCoverResolution(coverUrl, null, resolutionComplete = true)
        }
    }

    return coverUrl
}

private fun cachedResolvedCover(key: String?): String? {
    if (key.isNullOrBlank()) return null
    return synchronized(resolvedCoverMemoryCache) {
        resolvedCoverMemoryCache[key]
    }
}

internal fun versionedCoverCacheKey(baseKey: String?, generation: Int): String? {
    return versionedCoverCacheKey(baseKey, generation.toString())
}

internal fun versionedCoverCacheKey(baseKey: String?, generation: String): String? {
    if (baseKey.isNullOrBlank()) return null
    return "$baseKey|generation=$generation"
}

private fun forgetResolvedCover(key: String?) {
    if (key.isNullOrBlank()) return
    synchronized(resolvedCoverMemoryCache) {
        resolvedCoverMemoryCache.remove(key)
    }
}

private fun rememberResolvedCover(key: String?, coverUrl: String?) {
    if (key.isNullOrBlank() || coverUrl.isNullOrBlank()) return
    synchronized(resolvedCoverMemoryCache) {
        resolvedCoverMemoryCache[key] = coverUrl
    }
}

private fun SongItem.coverResolutionKey(): String {
    return listOf(
        stableKey(),
        customCoverUrl.orEmpty(),
        coverUrl.orEmpty(),
        localFilePath.orEmpty(),
        mediaUri.orEmpty()
    ).joinToString("|")
}

internal fun SongItem.coverDisplayCacheKey(): String {
    return listOf(
        "song",
        stableKey(),
        customCoverUrl.orEmpty(),
        coverUrl.orEmpty(),
        originalCoverUrl.orEmpty()
    ).joinToString(":")
}

internal fun playlistCoverResolutionCacheKey(
    playlist: LocalPlaylist,
    additionalCoverCandidates: List<SongItem> = emptyList()
): String {
    return playlist.coverResolutionKey(additionalCoverCandidates)
}

private fun LocalPlaylist.coverResolutionKey(
    additionalCoverCandidates: List<SongItem>
): String {
    return listOf(
        id.toString(),
        modifiedAt.toString(),
        customCoverUrl.orEmpty(),
        songs.size.toString(),
        playlistCoverResolutionSignature(songs).toString(),
        additionalCoverCandidates.size.toString(),
        playlistCoverResolutionSignature(additionalCoverCandidates).toString()
    ).joinToString("|")
}

private fun LocalArtistSummary.coverResolutionKey(): String {
    return listOf(
        stableKey,
        name,
        songs.size.toString(),
        playlistCoverResolutionSignature(songs).toString()
    ).joinToString("|")
}

internal fun playlistCoverResolutionSignature(songs: List<SongItem>): Long {
    var signature = 1_125_899_906_842_597L
    songs.asSequence()
        .take(PLAYLIST_COVER_SIGNATURE_CANDIDATE_LIMIT)
        .forEach { song ->
            signature = 31L * signature + song.id
            signature = 31L * signature + song.album.hashCode()
            signature = 31L * signature + song.albumId
            signature = 31L * signature + song.customCoverUrl.orEmpty().hashCode()
            signature = 31L * signature + song.coverUrl.orEmpty().hashCode()
            signature = 31L * signature + song.originalCoverUrl.orEmpty().hashCode()
            signature = 31L * signature + song.localFilePath.orEmpty().hashCode()
            signature = 31L * signature + song.mediaUri.orEmpty().hashCode()
            signature = 31L * signature + song.channelId.orEmpty().hashCode()
            signature = 31L * signature + song.audioId.orEmpty().hashCode()
            signature = 31L * signature + song.subAudioId.orEmpty().hashCode()
            signature = 31L * signature + song.sourceStableKey.orEmpty().hashCode()
        }
    return signature
}
