@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.prefetch

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.lifecycle.shouldUsePlaybackMediaCache
import moe.ouom.neriplayer.core.player.runtime.prefetch.canPrefetchServerMedia
import moe.ouom.neriplayer.core.player.runtime.prefetch.serverMediaPrefetchBytes
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.core.player.url.synchronizeCachedPlaybackDescriptor
import moe.ouom.neriplayer.core.player.url.allowsCustomCacheKey
import moe.ouom.neriplayer.core.player.url.prepareExoPlayerCacheForPrefetch
import moe.ouom.neriplayer.core.player.url.CachePrefetchReadiness
import moe.ouom.neriplayer.common.logging.NPLogger

internal fun PlayerManager.prefetchNextServerTrack(song: SongItem) {
    val cacheKey = computeCacheKey(song)
    if (currentGenericUrlPrefetchJob?.isActive == true && currentGenericUrlPrefetchKey == cacheKey) return
    cancelGenericUrlPrefetch("replace_server_target")
    if (cache == null || !shouldUsePlaybackMediaCache(currentCacheSize)) return
    val repository = PlayerDependencies.repositories.subsonicRepository ?: return
    val ref = ServerSongRef.from(song) ?: return
    val revision = repository.accounts.profile(ref.profileId)?.revision ?: return
    currentGenericUrlPrefetchKey = cacheKey
    val launched = ioScope.launch {
        try {
            withTimeout(30_000L) {
                // READY can arrive before the forward buffer reaches the safe prefetch threshold.
                while (!canStartServerPrefetch(ref.cacheKey)) {
                    if (repository.accounts.profile(ref.profileId)?.revision != revision) return@withTimeout
                    delay(250L)
                }
                val result = repository.playback(song) as? SongUrlResult.Success ?: return@withTimeout
                val bytes = serverMediaPrefetchBytes(result.expectedContentLength, currentCacheSize)
                if (bytes <= 0L) return@withTimeout
                val context = currentCoroutineContext()
                val mutationAllowed = {
                    context.isActive && repository.accounts.profile(ref.profileId)?.revision == revision &&
                        !playbackDemandArbiter.shouldYieldPrefetch(ref.cacheKey)
                }
                ensureActive()
                if (!canStartServerPrefetch(ref.cacheKey) || !mutationAllowed()) return@withTimeout
                val descriptor = synchronizeCachedPlaybackDescriptor(ref.cacheKey, result.audioInfo,
                    result.expectedContentLength, result.representationIdentity, mutationAllowed)
                if (!descriptor.allowsCustomCacheKey()) return@withTimeout
                if (cache?.isCached(ref.cacheKey, 0L, bytes) == true) return@withTimeout
                if (prepareExoPlayerCacheForPrefetch(ref.cacheKey, mutationAllowed) != CachePrefetchReadiness.READY_FOR_PREFETCH) return@withTimeout
                val read = prefetchServerIntoPlayerCache(result.url, ref.cacheKey, bytes) {
                    repository.accounts.profile(ref.profileId)?.revision == revision
                }
                NPLogger.d("NERI-PlayerManager", "server media prefetch finished: key=${ref.cacheKey}, bytes=$read, target=$bytes")
            }
        } catch (_: TimeoutCancellationException) {
            NPLogger.d("NERI-PlayerManager", "server prefetch reached its time budget: key=${ref.cacheKey}")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { NPLogger.d("NERI-PlayerManager", "server media prefetch unavailable: key=${ref.cacheKey}") }
    }
    currentGenericUrlPrefetchJob = launched
    launched.invokeOnCompletion {
        if (currentGenericUrlPrefetchJob === launched) {
            currentGenericUrlPrefetchJob = null
            currentGenericUrlPrefetchKey = null
        }
    }
}

internal suspend fun PlayerManager.canStartServerPrefetch(cacheKey: String): Boolean = withContext(Dispatchers.Main.immediate) {
    if (!isPlayerInitialized() || playbackDemandArbiter.shouldYieldPrefetch(cacheKey)) return@withContext false
    if (player.shuffleModeEnabled || repeatModeSetting == Player.REPEAT_MODE_ONE) return@withContext false
    val nextIndex = when {
        currentIndex + 1 in currentPlaylist.indices -> currentIndex + 1
        repeatModeSetting == Player.REPEAT_MODE_ALL && currentPlaylist.size > 1 -> 0
        else -> -1
    }
    val nextKey = currentPlaylist.getOrNull(nextIndex)?.let(ServerSongRef::from)?.cacheKey
    if (nextKey != cacheKey) return@withContext false
    val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val unmetered = runCatching { connectivity?.activeNetwork != null && !connectivity.isActiveNetworkMetered }.getOrDefault(false)
    canPrefetchServerMedia(cache != null && shouldUsePlaybackMediaCache(currentCacheSize), unmetered,
        player.isPlaying, player.playbackState == Player.STATE_READY, player.totalBufferedDuration,
        if (player.duration > 0L) (player.duration - player.currentPosition).coerceAtLeast(0L) else -1L)
}

/** Uses the playback cache and closes the worker on cancellation, yielding, or a bounded deadline. */
internal suspend fun PlayerManager.prefetchServerIntoPlayerCache(url: String, cacheKey: String, targetBytes: Long,
                                                               configurationValid: () -> Boolean): Long {
    if (targetBytes <= 0L || !canStartServerPrefetch(cacheKey)) return 0L
    if (application.cacheDir.usableSpace < targetBytes + 1024 * 1024L) return 0L
    val mediaCache = cache ?: return 0L
    val calls = PrefetchCallFactory(PlayerDependencies.repositories.sharedOkHttpClient)
    val upstream = conditionalHttpFactory?.forServerPrefetch(calls) ?: return 0L
    val source = CacheDataSource.Factory().setCache(mediaCache)
        .setUpstreamDataSourceFactory(upstream)
        // Do not wait on cache locks held by playback or a different writer.
        .setFlags(0)
        .setEventListener(object : CacheDataSource.EventListener {
            override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                PlayerDependencies.repositories.trafficStatsRepo.recordCacheHitBytes(cachedBytesRead)
            }
            override fun onCacheIgnored(reason: Int) = Unit
        }).createDataSource()
    val spec = DataSpec.Builder().setUri(url.toUri()).setKey(cacheKey).setPosition(0L).setLength(targetBytes).build()
    try {
        return coroutineScope {
            val owner = currentCoroutineContext().job
            val monitor = launch {
                while (isActive) {
                    delay(100L)
                    if (!configurationValid() || !canStartServerPrefetch(cacheKey)) {
                        owner.cancel(CancellationException("Server prefetch yielded to playback or network policy"))
                    }
                }
            }
            try {
                withTimeout(10_000L) {
                    readServerPrefetch(source, spec, targetBytes, calls) {
                        !playbackDemandArbiter.shouldYieldPrefetch(cacheKey)
                    }
                }
            } finally { monitor.cancel() }
        }
    } finally {
        calls.cancel()
        upstream.close()
    }
}

/** The blocking worker owns DataSource.close; cancellation reaches the HTTP call immediately. */
internal suspend fun readServerPrefetch(
    source: androidx.media3.datasource.DataSource,
    spec: DataSpec,
    targetBytes: Long,
    calls: PrefetchCallFactory,
    shouldContinue: () -> Boolean = { true }
): Long = coroutineScope {
    suspendCancellableCoroutine { continuation ->
        val worker = launch(Dispatchers.IO) {
            try {
                var bytes = 0L
                ensureActive()
                if (shouldContinue()) {
                    source.open(spec)
                    val buffer = ByteArray(64 * 1024)
                    while (bytes < targetBytes && shouldContinue()) {
                        ensureActive()
                        val read = source.read(buffer, 0, minOf(buffer.size.toLong(), targetBytes - bytes).toInt())
                        if (read == C.RESULT_END_OF_INPUT || read < 0) break
                        bytes += read
                    }
                }
                continuation.resume(bytes)
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            } finally { runCatching { source.close() } }
        }
        continuation.invokeOnCancellation {
            calls.cancel()
            worker.cancel()
        }
    }
}
