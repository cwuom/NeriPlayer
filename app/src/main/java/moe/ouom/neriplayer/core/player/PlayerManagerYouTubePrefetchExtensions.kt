package moe.ouom.neriplayer.core.player

import androidx.core.net.toUri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.player.prefetch.YouTubePrefetchRunner
import moe.ouom.neriplayer.core.player.prefetch.YouTubePrefetchTask
import moe.ouom.neriplayer.core.player.policy.resolveYouTubeWarmupTargets
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger

private const val YOUTUBE_WARMUP_BUFFER_BYTES = 64 * 1024
private const val YOUTUBE_WARMUP_MIN_PREFETCH_BYTES = 256L * 1024L
private const val YOUTUBE_WARMUP_FIRST_TRACK_PREFETCH_BYTES = 1536L * 1024L
private const val YOUTUBE_WARMUP_SECOND_TRACK_PREFETCH_BYTES = 1024L * 1024L
private const val YOUTUBE_WARMUP_FOLLOWING_TRACK_PREFETCH_BYTES = 512L * 1024L
private const val YOUTUBE_PREFETCH_MAX_CONCURRENCY = 1

private data class YouTubePrefetchSpec(
    val videoId: String,
    val preferredQuality: String,
    val slot: Int,
    val windowSize: Int,
    val source: String
)

internal fun PlayerManager.prefetchYouTubeQueueWindowImpl(
    playlist: List<SongItem>,
    startIndex: Int,
    source: String
) {
    val targets = resolveYouTubeWarmupTargets(
        playlist = playlist,
        currentSongIndex = startIndex,
        preferredQuality = youtubePreferredQuality
    )
    if (!targets.hasWork) {
        return
    }
    youtubeMusicPlaybackRepository.warmBootstrapAsync()
    NPLogger.d(
        "NERI-PlayerManager",
        "prefetchYouTubeQueueWindow: source=$source, startIndex=$startIndex, ids=${targets.prefetchVideoIds.joinToString()}, preferredQuality=${targets.preferredQuality}"
    )
    val specs = targets.prefetchVideoIds.mapIndexed { slot, videoId ->
        YouTubePrefetchSpec(
            videoId = videoId,
            preferredQuality = targets.preferredQuality,
            slot = slot,
            windowSize = targets.prefetchVideoIds.size,
            source = source
        )
    }.associateBy { it.videoId }
    currentYouTubePrefetchJob?.cancel()
    currentYouTubePrefetchJob = YouTubePrefetchRunner(
        task = YouTubePrefetchTask { videoId ->
            val spec = specs[videoId] ?: return@YouTubePrefetchTask
            prefetchYouTubePlayableAudio(spec)
        },
        maxConcurrency = YOUTUBE_PREFETCH_MAX_CONCURRENCY
    ).launch(ioScope, targets.prefetchVideoIds)
}

private suspend fun PlayerManager.prefetchYouTubePlayableAudio(spec: YouTubePrefetchSpec) {
    val cacheKey = computeYouTubeCacheKey(spec.videoId, spec.preferredQuality)
    if (checkExoPlayerCache(cacheKey)) {
        return
    }
    val existingJob = youtubeStreamWarmupJobs[cacheKey]
    if (existingJob?.isActive == true) {
        return
    }
    val createdJob = currentCoroutineContext()[Job]
    if (createdJob != null) {
        youtubeStreamWarmupJobs[cacheKey] = createdJob
    }
    val startedAtMs = System.currentTimeMillis()
    try {
        val playableAudio = youtubeMusicPlaybackRepository.getBestPlayableAudio(
            videoId = spec.videoId,
            preferredQualityOverride = spec.preferredQuality,
            forceRefresh = false,
            requireDirect = false,
            preferM4a = false
        ) ?: return
        invalidateMismatchedCachedResource(
            cacheKey = cacheKey,
            expectedContentLength = playableAudio.contentLength
        )
        if (checkExoPlayerCache(cacheKey)) {
            return
        }
        if (playableAudio.streamType != YouTubePlayableStreamType.DIRECT) {
            NPLogger.d(
                "NERI-PlayerManager",
                "skip media prefetch for non-direct YouTube stream: videoId=${spec.videoId}, type=${playableAudio.streamType}, source=${spec.source}"
            )
            return
        }
        val targetBytes = resolveYouTubeWarmupPrefetchBytes(
            slot = spec.slot,
            windowSize = spec.windowSize,
            contentLength = playableAudio.contentLength
        )
        if (targetBytes <= 0L) {
            return
        }
        val prefetchedBytes = prefetchIntoPlayerCache(
            url = playableAudio.url,
            cacheKey = cacheKey,
            targetBytes = targetBytes
        )
        NPLogger.d(
            "NERI-PlayerManager",
            "YouTube media prefetch finished: videoId=${spec.videoId}, cacheKey=$cacheKey, slot=${spec.slot}, source=${spec.source}, prefetchedBytes=$prefetchedBytes, targetBytes=$targetBytes, contentLength=${playableAudio.contentLength}, elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
    } catch (error: Exception) {
        if (error is CancellationException) {
            throw error
        }
        NPLogger.w(
            "NERI-PlayerManager",
            "YouTube media prefetch failed: videoId=${spec.videoId}, cacheKey=$cacheKey, slot=${spec.slot}, source=${spec.source}, error=${error.message}"
        )
    } finally {
        if (createdJob != null) {
            youtubeStreamWarmupJobs.remove(cacheKey, createdJob)
        }
    }
}

private fun resolveYouTubeWarmupPrefetchBytes(
    slot: Int,
    windowSize: Int,
    contentLength: Long?
): Long {
    val baseBytes = when (slot) {
        0 -> YOUTUBE_WARMUP_FIRST_TRACK_PREFETCH_BYTES
        1 -> YOUTUBE_WARMUP_SECOND_TRACK_PREFETCH_BYTES
        else -> YOUTUBE_WARMUP_FOLLOWING_TRACK_PREFETCH_BYTES
    }
    val boostedBytes = when {
        windowSize <= 2 -> (baseBytes * 3L) / 2L
        windowSize == 3 && slot == 0 -> {
            baseBytes + YOUTUBE_WARMUP_FOLLOWING_TRACK_PREFETCH_BYTES
        }
        else -> baseBytes
    }.coerceAtLeast(YOUTUBE_WARMUP_MIN_PREFETCH_BYTES)
    return contentLength
        ?.takeIf { it > 0L }
        ?.coerceAtMost(boostedBytes)
        ?: boostedBytes
}

@OptIn(UnstableApi::class)
private suspend fun PlayerManager.prefetchIntoPlayerCache(
    url: String,
    cacheKey: String,
    targetBytes: Long
): Long = withContext(Dispatchers.IO) {
    if (!isCacheInitialized()) {
        return@withContext 0L
    }
    val upstreamFactory = conditionalHttpFactory ?: return@withContext 0L
    val requestedBytes = targetBytes.coerceAtLeast(YOUTUBE_WARMUP_MIN_PREFETCH_BYTES)
    val cacheDataSource = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
        .createDataSource()
    val dataSpec = DataSpec.Builder()
        .setUri(url.toUri())
        .setKey(cacheKey)
        .setPosition(0L)
        .setLength(requestedBytes)
        .build()
    val buffer = ByteArray(YOUTUBE_WARMUP_BUFFER_BYTES)
    var totalRead = 0L
    try {
        cacheDataSource.open(dataSpec)
        while (totalRead < requestedBytes) {
            val bytesToRead = minOf(
                buffer.size.toLong(),
                requestedBytes - totalRead
            ).toInt()
            val read = cacheDataSource.read(buffer, 0, bytesToRead)
            if (read == C.RESULT_END_OF_INPUT || read < 0) {
                break
            }
            totalRead += read.toLong()
        }
    } finally {
        runCatching { cacheDataSource.close() }
    }
    totalRead
}
