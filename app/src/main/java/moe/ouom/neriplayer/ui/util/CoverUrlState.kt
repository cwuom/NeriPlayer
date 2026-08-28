package moe.ouom.neriplayer.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.SystemClock
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.media.CustomSongCoverStorage
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.isUsableCoverReference
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.local.playlist.model.LocalArtistSummary
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.storage.LocalAssetInvalidationBus
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playbackVisualKey
import moe.ouom.neriplayer.data.model.playbackVisualKeyAliases
import moe.ouom.neriplayer.data.model.stableKey
import java.util.LinkedHashMap
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
private val coverProbeDispatcher = Dispatchers.IO.limitedParallelism(4)
@OptIn(ExperimentalCoroutinesApi::class)
private val embeddedCoverResolutionDispatcher = Dispatchers.IO.limitedParallelism(2)
private const val UI_COVER_MEMORY_CACHE_LIMIT = 2048
private const val STABLE_COVER_MEMORY_CACHE_LIMIT = 2048
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

private data class StableResolvedCoverEntry(
    val coverUrl: String,
    val validationKey: String
)

private val stableResolvedCoverMemoryCache = object :
    LinkedHashMap<String, StableResolvedCoverEntry>(
    STABLE_COVER_MEMORY_CACHE_LIMIT,
    0.75f,
    true
) {
    override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<String, StableResolvedCoverEntry>
    ): Boolean {
        return size > STABLE_COVER_MEMORY_CACHE_LIMIT
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

internal fun shouldAllowRemoteCoverFallback(
    isLocalSong: Boolean,
    hasExplicitCustomCover: Boolean = false
): Boolean = !isLocalSong || hasExplicitCustomCover

private fun isAllowedCoverCandidate(
    reference: String?,
    allowRemoteCoverFallback: Boolean = true
): Boolean {
    val normalized = reference?.trim()?.takeIf(String::isNotBlank) ?: return false
    return allowRemoteCoverFallback || !CustomSongCoverStorage.isRemoteReference(normalized)
}

private fun SongItem.allowsRemoteCoverFallback(): Boolean {
    val customReference = customCoverUrl
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val hasExplicitCustomCover = customReference?.let { reference ->
        CustomSongCoverStorage.isRemoteReference(reference)
    } == true
    return shouldAllowRemoteCoverFallback(
        isLocalSong = isLocalSong(),
        hasExplicitCustomCover = hasExplicitCustomCover
    )
}

internal fun resolvePrevalidatedCoverCandidate(
    primaryCoverUrl: String?,
    fallbackCoverUrl: String?,
    allowRemoteCoverFallback: Boolean = true
): String? {
    fun remoteCandidate(reference: String?): String? {
        if (!allowRemoteCoverFallback) return null
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

private fun isPotentialCoverReference(reference: String): Boolean {
    val normalized = reference.trim()
    if (normalized.isEmpty()) return false
    if (
        normalized.startsWith("https://", ignoreCase = true) ||
        normalized.startsWith("http://", ignoreCase = true) ||
        normalized.startsWith("content://", ignoreCase = true) ||
        normalized.startsWith("file://", ignoreCase = true)
    ) {
        return true
    }
    return normalized.startsWith("/")
}

internal fun resolveImmediateCoverCandidate(
    primaryCoverUrl: String?,
    fallbackCoverUrl: String?,
    allowRemoteCoverFallback: Boolean = true
): String? {
    val primary = primaryCoverUrl
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (
        primary != null &&
            isPotentialCoverReference(primary) &&
            isAllowedCoverCandidate(primary, allowRemoteCoverFallback)
    ) {
        return primary
    }
    return fallbackCoverUrl
        ?.trim()
        ?.takeIf {
            it.isNotEmpty() &&
                isPotentialCoverReference(it) &&
                isAllowedCoverCandidate(it, allowRemoteCoverFallback)
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
    resolutionComplete: Boolean,
    currentCoverUsable: Boolean? = null
): String? {
    if (!resolutionComplete) {
        return retainCoverDuringResolution(currentCover, resolvedCover)
    }
    return resolvedCover?.takeIf(String::isNotBlank)
        ?: when (currentCoverUsable) {
            false -> null
            else -> currentCover
        }
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
    val songVisualAliases = remember(song) {
        song?.playbackVisualKeyAliases().orEmpty()
    }
    val songStateKey = remember(song) {
        song?.coverDisplayStateKey()
    }
    val songKey = remember(song) {
        song?.coverResolutionKey()
    }
    val localReferenceRevisionKey = remember(song) {
        listOf(
            song?.mediaUri.orEmpty(),
            song?.localFilePath.orEmpty(),
            song?.localFileName.orEmpty()
        ).joinToString("|")
    }
    val allowRemoteCoverFallback = song?.allowsRemoteCoverFallback() ?: true
    val effectiveGeneration = localAssetGeneration ?: "global=$downloadPresenceVersion"
    val probeGeneration = effectiveGeneration.hashCode()
    val resolvedCacheKey = versionedCoverCacheKey(songDisplayKey, effectiveGeneration)
    val stableCoverValidationKey = "$effectiveGeneration|$localReferenceRevisionKey"
    val stableCachedCover = cachedStableResolvedCover(
        aliases = songVisualAliases,
        validationKey = stableCoverValidationKey,
        allowRemoteCoverFallback = allowRemoteCoverFallback
    )
    val latestSongDisplayKey by rememberUpdatedState(songDisplayKey)
    val latestSongKey by rememberUpdatedState(songKey)
    val latestReferenceRevisionKey by rememberUpdatedState(localReferenceRevisionKey)
    var coverUrl by remember(songStateKey) {
        val cachedCover = cachedResolvedCover(resolvedCacheKey)
            ?.takeIf { isAllowedCoverCandidate(it, allowRemoteCoverFallback) }
        val explicitSongCover = song?.displayCoverUrl()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.takeIf { isAllowedCoverCandidate(it, allowRemoteCoverFallback) }
        mutableStateOf(
            resolveImmediateCoverCandidate(
                primaryCoverUrl = cachedCover ?: explicitSongCover ?: stableCachedCover,
                fallbackCoverUrl = song?.originalCoverUrl,
                allowRemoteCoverFallback = allowRemoteCoverFallback
            )
        )
    }

    fun isCurrentResolution(): Boolean {
        return latestSongDisplayKey == songDisplayKey &&
            latestSongKey == songKey &&
            latestReferenceRevisionKey == localReferenceRevisionKey
    }

    fun rememberStableCover(cover: String?) {
        if (
            cover.isNullOrBlank() ||
                !isAllowedCoverCandidate(cover, allowRemoteCoverFallback) ||
                !isCurrentResolution()
        ) {
            return
        }
        rememberStableResolvedCover(
            aliases = songVisualAliases,
            coverUrl = cover,
            validationKey = stableCoverValidationKey
        )
    }

    LaunchedEffect(
        songDisplayKey,
        songKey,
        localReferenceRevisionKey,
        appContext,
        effectiveGeneration,
        resolveLocalFallback,
        allowEmbeddedCoverFallback,
        allowRemoteCoverFallback
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        var resolutionStage = "none"
        if (song == null) {
            if (isCurrentResolution()) {
                coverUrl = null
            }
            return@LaunchedEffect
        }

        val rawImmediateCover = song.displayCoverUrl()
        var currentCoverValidated = false
        val prevalidatedCover = resolvePrevalidatedCoverCandidate(
            primaryCoverUrl = rawImmediateCover,
            fallbackCoverUrl = song.originalCoverUrl,
            allowRemoteCoverFallback = allowRemoteCoverFallback
        )
        if (!prevalidatedCover.isNullOrBlank()) {
            resolutionStage = "song-pending"
            rememberResolvedCover(resolvedCacheKey, prevalidatedCover)
            rememberStableCover(prevalidatedCover)
            if (isCurrentResolution()) {
                coverUrl = prevalidatedCover
            }
            currentCoverValidated = true
        }
        val immediateCover = withContext(coverProbeDispatcher) {
            rawImmediateCover
                ?.takeIf { isAllowedCoverCandidate(it, allowRemoteCoverFallback) }
                ?.takeIf { isUsableCoverReference(appContext, it) }
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrentResolution()) return@LaunchedEffect
        val immediateCandidate = resolveImmediateCoverCandidate(
            primaryCoverUrl = rawImmediateCover,
            fallbackCoverUrl = null,
            allowRemoteCoverFallback = allowRemoteCoverFallback
        )
        if (!immediateCandidate.isNullOrBlank() && immediateCover != null &&
            (coverUrl.isNullOrBlank() || prevalidatedCover == immediateCandidate)
        ) {
            resolutionStage = resolutionStage.takeUnless { it == "none" } ?: "song"
            coverUrl = immediateCandidate
            rememberStableCover(immediateCandidate)
            currentCoverValidated = true
        }
        val memoryCover = cachedResolvedCover(resolvedCacheKey)
        val prevalidatedMemoryCover = immediateCover
            ?: resolvePrevalidatedCoverCandidate(
                primaryCoverUrl = rawImmediateCover,
                fallbackCoverUrl = memoryCover,
                allowRemoteCoverFallback = allowRemoteCoverFallback
            )
        if (!prevalidatedMemoryCover.isNullOrBlank()) {
            coverUrl = prevalidatedMemoryCover
            rememberStableCover(prevalidatedMemoryCover)
            currentCoverValidated = true
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
                    probeGeneration = probeGeneration,
                    allowRemoteCoverFallback = allowRemoteCoverFallback
                )
            } else {
                memoryCover
                    ?.takeIf { isAllowedCoverCandidate(it, allowRemoteCoverFallback) }
                    ?.takeIf { isUsableCoverReference(appContext, it) }
                    ?: immediateCover
            }
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrentResolution()) return@LaunchedEffect
        if (!cachedCover.isNullOrBlank()) {
            resolutionStage = when {
                cachedCover == memoryCover -> "memory"
                cachedCover == immediateCover -> "song"
                else -> "local-cache"
            }
            rememberResolvedCover(resolvedCacheKey, cachedCover)
            coverUrl = retainCoverDuringResolution(coverUrl, cachedCover)
            rememberStableCover(cachedCover)
            currentCoverValidated = true
        }
        if (!shouldResolveEmbeddedCoverFallback(
                resolveLocalFallback = resolveLocalFallback,
                allowEmbeddedCoverFallback = allowEmbeddedCoverFallback
            ) || !cachedCover.isNullOrBlank()
        ) {
            if (cachedCover.isNullOrBlank() && immediateCover.isNullOrBlank()) {
                coverUrl = finishCoverResolution(
                    currentCover = coverUrl,
                    resolvedCover = null,
                    resolutionComplete = true,
                    currentCoverUsable = currentCoverValidated
                )
            }
            return@LaunchedEffect
        }

        val resolvedCover = withContext(embeddedCoverResolutionDispatcher) {
            song.displayCoverUrl(appContext, resolveLocalFallback)
                ?.takeIf { isAllowedCoverCandidate(it, allowRemoteCoverFallback) }
                ?.takeIf { isUsableCoverReference(appContext, it) }
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrentResolution()) return@LaunchedEffect
        if (!resolvedCover.isNullOrBlank()) {
            resolutionStage = "fallback"
            rememberResolvedCover(resolvedCacheKey, resolvedCover)
            coverUrl = retainCoverDuringResolution(coverUrl, resolvedCover)
            rememberStableCover(resolvedCover)
        } else {
            coverUrl = finishCoverResolution(
                currentCover = coverUrl,
                resolvedCover = null,
                resolutionComplete = true,
                currentCoverUsable = currentCoverValidated
            )
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
    probeGeneration: Int = 0,
    allowRemoteCoverFallback: Boolean = true
): String? {
    val cacheKey = fastCoverProbeCacheKey(
        song = song,
        probeGeneration = probeGeneration,
        allowRemoteCoverFallback = allowRemoteCoverFallback
    )
    val now = SystemClock.elapsedRealtime()
    synchronized(fastCoverProbeCache) {
        fastCoverProbeCache[cacheKey]?.let { cached ->
            if (now - cached.checkedAtMs <= FAST_COVER_PROBE_TTL_MS) {
                val cachedCover = cached.coverUrl
                if (
                    cachedCover.isNullOrBlank() ||
                        (
                            isAllowedCoverCandidate(cachedCover, allowRemoteCoverFallback) &&
                                isUsableCoverReference(context, cachedCover)
                            )
                ) {
                    return cachedCover
                }
            }
            fastCoverProbeCache.remove(cacheKey)
        }
    }

    val resolved = resolveCachedSongDisplayCoverUrlUncached(
        context = context,
        song = song,
        allowRemoteCoverFallback = allowRemoteCoverFallback
    )
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
    song: SongItem,
    allowRemoteCoverFallback: Boolean = true
): String? {
    fun usable(reference: String?): String? {
        return reference
            ?.takeIf {
                it.isNotBlank() &&
                    isAllowedCoverCandidate(it, allowRemoteCoverFallback) &&
                    isFastCoverReference(it)
            }
            ?.takeIf { isUsableCoverReference(context, it) }
    }

    usable(song.customCoverUrl)?.let { return it }
    usable(AudioDownloadManager.peekLocalCoverUri(song))?.let { return it }
    if (song.isLocalSong()) {
        // sidecars are authoritative for local files and do not depend on MediaStore grants
        val nearbyCover = try {
            LocalMediaSupport.resolveNearbyCoverUri(context, song)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is SecurityException) {
                LocalMediaSupport.invalidateSafReadCaches()
                NPLogger.w(
                    "LocalCover",
                    "SAF cover probe out of scope: song=${song.stableKey()}, " +
                        "message=${error.message}"
                )
            }
            null
        }
        usable(nearbyCover)?.let { return it }
        usable(LocalMediaSupport.peekMediaStoreAlbumArtUri(context, song))?.let { return it }
        usable(LocalMediaSupport.peekCachedEmbeddedCoverUri(context, song))?.let { return it }
    }
    usable(song.displayCoverUrl())?.let { return it }
    return usable(song.originalCoverUrl)
}

private fun fastCoverProbeCacheKey(
    song: SongItem,
    probeGeneration: Int,
    allowRemoteCoverFallback: Boolean
): String {
    val localFile = song.localFilePath
        ?.takeUnless { it.startsWith("content://", ignoreCase = true) }
        ?.let(::File)
    val fileState = localFile?.let {
        "${it.length()}:${it.lastModified()}:${it.parentFile?.lastModified()}"
    }.orEmpty()
    val referenceRevision = listOf(
        song.mediaUri.orEmpty(),
        song.localFilePath.orEmpty(),
        song.localFileName.orEmpty()
    ).joinToString("|").hashCode()
    return "${song.coverResolutionKey()}|$fileState|reference=$referenceRevision" +
        "|generation=$probeGeneration|allowRemote=$allowRemoteCoverFallback"
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
                probeGeneration = probeGeneration,
                allowRemoteCoverFallback = song.allowsRemoteCoverFallback()
            )
            if (!fastCover.isNullOrBlank()) return fastCover
            val resolvedCover = withContext(embeddedCoverResolutionDispatcher) {
                song.displayCoverUrl(
                    context = context,
                    resolveLocalMetadataFallback = true
                )
                    ?.takeIf { isAllowedCoverCandidate(it, song.allowsRemoteCoverFallback()) }
                    ?.takeIf { isUsableCoverReference(context, it) }
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
                probeGeneration = probeGeneration,
                allowRemoteCoverFallback = song.allowsRemoteCoverFallback()
            )
                ?: song.displayCoverUrl()
                    ?.takeIf { isAllowedCoverCandidate(it, song.allowsRemoteCoverFallback()) }
                    ?.takeIf {
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
    val allowRemoteCoverFallback = artist?.songs?.any {
        it.allowsRemoteCoverFallback()
    } == true
    var coverUrl by remember(artistKey) {
        val cachedCover = cachedResolvedCover(resolvedCacheKey)
            ?.takeIf { isAllowedCoverCandidate(it, allowRemoteCoverFallback) }
        val immediateCover = artist?.songs?.asSequence()
            ?.mapNotNull { song ->
                song.displayCoverUrl()
                    ?.takeIf {
                        isAllowedCoverCandidate(it, song.allowsRemoteCoverFallback())
                    }
            }
            ?.firstOrNull()
        mutableStateOf(cachedCover ?: immediateCover)
    }

    LaunchedEffect(
        artistKey,
        appContext,
        downloadPresenceVersion,
        resolveLocalFallback,
        allowRemoteCoverFallback
    ) {
        if (artist == null) {
            coverUrl = null
            return@LaunchedEffect
        }

        val immediateCover = artist.songs.asSequence()
            .mapNotNull { song ->
                song.displayCoverUrl()
                    ?.takeIf {
                        isAllowedCoverCandidate(it, song.allowsRemoteCoverFallback()) &&
                            isFastCoverReference(it) &&
                            isUsableCoverReference(appContext, it)
                    }
            }
            .firstOrNull()
        val cachedCover = cachedResolvedCover(resolvedCacheKey)
            ?.takeIf { isAllowedCoverCandidate(it, allowRemoteCoverFallback) }
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
            artist.songs.asSequence()
                .mapNotNull { song ->
                    song.displayCoverUrl(appContext, resolveLocalFallback)
                        ?.takeIf {
                            isAllowedCoverCandidate(it, song.allowsRemoteCoverFallback())
                        }
                        ?.takeIf { isUsableCoverReference(appContext, it) }
                }
                .firstOrNull()
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

private fun cachedStableResolvedCover(
    aliases: List<String>,
    validationKey: String,
    allowRemoteCoverFallback: Boolean = true
): String? {
    if (aliases.isEmpty()) return null
    return synchronized(stableResolvedCoverMemoryCache) {
        aliases.firstNotNullOfOrNull { alias ->
            stableResolvedCoverMemoryCache[alias]
                ?.takeIf { it.validationKey == validationKey }
                ?.takeIf {
                    isAllowedCoverCandidate(it.coverUrl, allowRemoteCoverFallback)
                }
                ?.coverUrl
        }
    }
}

private fun rememberStableResolvedCover(
    aliases: List<String>,
    coverUrl: String,
    validationKey: String
) {
    if (aliases.isEmpty() || coverUrl.isBlank()) return
    synchronized(stableResolvedCoverMemoryCache) {
        aliases.forEach { alias ->
            if (alias.isNotBlank()) {
                stableResolvedCoverMemoryCache[alias] = StableResolvedCoverEntry(
                    coverUrl = coverUrl,
                    validationKey = validationKey
                )
            }
        }
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
        playbackVisualKey(),
        customCoverUrl.orEmpty(),
        coverUrl.orEmpty(),
        originalCoverUrl.orEmpty(),
        localFileName.orEmpty()
    ).joinToString("|")
}

internal fun SongItem.coverDisplayCacheKey(): String {
    return listOf(
        "song",
        playbackVisualKey(),
        customCoverUrl.orEmpty(),
        coverUrl.orEmpty(),
        originalCoverUrl.orEmpty()
    ).joinToString(":")
}

internal fun SongItem.coverDisplayStateKey(): String {
    return "song-state:${playbackVisualKey()}"
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
