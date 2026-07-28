package moe.ouom.neriplayer.core.player.prefetch

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.core.player.policy.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.core.player.policy.refresh.RefreshSideEffectGate
import moe.ouom.neriplayer.core.player.url.OFFLINE_CACHE_URL_PREFIX
import moe.ouom.neriplayer.core.player.url.resolveSongUrl
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem

internal fun resolveGenericUrlPrefetchTtlMs(
    currentTrackDurationMs: Long,
    defaultTtlMs: Long = GENERIC_URL_PREFETCH_TTL_MS,
    maxTtlMs: Long = GENERIC_URL_PREFETCH_MAX_TTL_MS
): Long {
    val durationBasedTtl = currentTrackDurationMs
        .takeIf { it > 0L }
        ?.plus(30_000L)
    return (durationBasedTtl ?: defaultTtlMs).coerceIn(1L, maxTtlMs)
}

internal fun PlayerManager.prefetchNextGenericTrackUrl() {
    if (!isApplicationInitialized()) return

    if (player.shuffleModeEnabled || repeatModeSetting == Player.REPEAT_MODE_ONE) {
        cancelGenericUrlPrefetch(reason = "non_sequential_playback_mode")
        return
    }

    val nextIndex = when {
        currentIndex + 1 in currentPlaylist.indices -> currentIndex + 1
        repeatModeSetting == Player.REPEAT_MODE_ALL && currentPlaylist.size > 1 -> 0
        else -> -1
    }
    val nextSong = currentPlaylist.getOrNull(nextIndex)
    if (nextSong == null ||
        isLocalSong(nextSong) ||
        isYouTubeMusicTrack(nextSong) ||
        isDirectStreamUrl(nextSong.streamUrl)
    ) {
        cancelGenericUrlPrefetch(reason = "no_supported_next_track")
        return
    }

    val cacheKey = computeCacheKey(nextSong)
    assert(cacheKey.isNotBlank()) { "generic URL prefetch cache key must not be blank" }
    if (genericUrlPrefetchCache.containsFresh(cacheKey, SystemClock.elapsedRealtime())) return
    if (currentGenericUrlPrefetchJob?.isActive == true && currentGenericUrlPrefetchKey == cacheKey) {
        return
    }

    cancelGenericUrlPrefetch(reason = "replace_target")
    currentGenericUrlPrefetchKey = cacheKey
    val launchedJob = ioScope.launch {
        try {
            val result = resolveSongUrl(
                song = nextSong,
                allowGenericPrefetchCache = false,
                sideEffects = RefreshResolverSideEffects(RefreshSideEffectGate { false })
            )
            // 本地兜底命中的受限歌曲同样值得预取, 否则消费方会白等一个不落盘的任务
            if (result is SongUrlResult.Success &&
                !result.url.startsWith(OFFLINE_CACHE_URL_PREFIX) &&
                (isDirectStreamUrl(result.url) || LocalSongSupport.isLocalMediaUri(result.url))
            ) {
                genericUrlPrefetchCache.put(
                    key = cacheKey,
                    result = result,
                    nowMs = SystemClock.elapsedRealtime(),
                    ttlMsOverride = resolveGenericUrlPrefetchTtlMs(
                        currentTrackDurationMs = maxOf(
                            playbackDurationFlow.value,
                            currentSongFlow.value?.durationMs ?: 0L
                        )
                    )
                )
                NPLogger.d(
                    "NERI-PlayerManager",
                    "generic URL prefetch completed: song=${nextSong.name}, key=$cacheKey"
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(
                "NERI-PlayerManager",
                "generic URL prefetch failed: song=${nextSong.name}, key=$cacheKey",
                error
            )
        }
    }
    currentGenericUrlPrefetchJob = launchedJob
    launchedJob.invokeOnCompletion {
        if (currentGenericUrlPrefetchJob === launchedJob) {
            currentGenericUrlPrefetchJob = null
            currentGenericUrlPrefetchKey = null
        }
    }
}

internal fun PlayerManager.cancelGenericUrlPrefetch(reason: String) {
    val activeJob = currentGenericUrlPrefetchJob
    if (activeJob?.isActive == true) {
        NPLogger.d(
            "NERI-PlayerManager",
            "cancel generic URL prefetch: reason=$reason, key=$currentGenericUrlPrefetchKey"
        )
    }
    activeJob?.cancel()
    currentGenericUrlPrefetchJob = null
    currentGenericUrlPrefetchKey = null
}

internal fun PlayerManager.cancelGenericUrlPrefetchUnlessReusableForSong(
    song: SongItem,
    reason: String
) {
    val activeJob = currentGenericUrlPrefetchJob?.takeIf { it.isActive } ?: return
    val reusableKey = song
        .takeUnless { isLocalSong(it) || isYouTubeMusicTrack(it) || isDirectStreamUrl(it.streamUrl) }
        ?.let(::computeCacheKey)
    if (reusableKey != null && reusableKey == currentGenericUrlPrefetchKey) {
        NPLogger.d(
            "NERI-PlayerManager",
            "keep reusable generic URL prefetch: reason=$reason, key=$reusableKey"
        )
        return
    }
    activeJob.cancel()
    currentGenericUrlPrefetchJob = null
    currentGenericUrlPrefetchKey = null
}

internal suspend fun PlayerManager.consumeGenericUrlPrefetch(
    cacheKey: String
): SongUrlResult.Success? {
    consumeValidGenericUrlPrefetch(cacheKey)?.let { return it }
    val activeJob = currentGenericUrlPrefetchJob
        ?.takeIf { it.isActive && currentGenericUrlPrefetchKey == cacheKey }
        ?: return null
    activeJob.join()
    return consumeValidGenericUrlPrefetch(cacheKey)
}

private fun PlayerManager.consumeValidGenericUrlPrefetch(cacheKey: String): SongUrlResult.Success? {
    val result = genericUrlPrefetchCache.consume(cacheKey, SystemClock.elapsedRealtime()) ?: return null
    // 预取到消费之间开关可能被关掉或文件失效, 复验不过就丢弃走全新解析
    if (result.isNeteaseLocalFallback &&
        LocalSongSupport.isLocalMediaUri(result.url) &&
        (!neteaseLocalSourceFallbackEnabled || !isReadableLocalMediaUri(result.url))
    ) {
        NPLogger.d(
            "NERI-PlayerManager",
            "drop stale local prefetch result: key=$cacheKey"
        )
        return null
    }
    NPLogger.d("NERI-PlayerManager", "generic URL prefetch cache hit: key=$cacheKey")
    return result
}
