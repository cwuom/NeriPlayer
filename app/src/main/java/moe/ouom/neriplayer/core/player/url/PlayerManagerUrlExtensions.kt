@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.url

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlayerEvent
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.core.player.model.mergeLocalPlaybackAudioInfoWithRemoteQuality
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.refresh.RefreshDeferredCompletion
import moe.ouom.neriplayer.core.player.policy.refresh.RefreshRequestSemantics
import moe.ouom.neriplayer.core.player.policy.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.core.player.policy.refresh.RefreshResultSideEffects
import moe.ouom.neriplayer.core.player.policy.refresh.RefreshResultKind
import moe.ouom.neriplayer.core.player.policy.refresh.RefreshSideEffectGate
import moe.ouom.neriplayer.core.player.policy.command.resolvePlaybackStartPlan
import moe.ouom.neriplayer.core.player.policy.refresh.resolveRefreshApplyAction
import moe.ouom.neriplayer.core.player.policy.refresh.shouldApplyRefreshResult
import moe.ouom.neriplayer.core.player.policy.refresh.YouTubePlaybackRecoveryStrategy
import moe.ouom.neriplayer.core.player.playback.advanceAfterPlaybackFailure
import moe.ouom.neriplayer.core.player.playback.preparePlayerForManagedStart
import moe.ouom.neriplayer.core.player.prefetch.consumeGenericUrlPrefetch
import moe.ouom.neriplayer.core.player.quality.effectiveBiliQuality
import moe.ouom.neriplayer.core.player.quality.effectiveNeteaseQuality
import moe.ouom.neriplayer.core.player.quality.effectiveYouTubeQuality
import moe.ouom.neriplayer.core.player.resolver.netease.NeteasePlaybackResponseParser
import moe.ouom.neriplayer.core.player.resolver.netease.tryResolveNeteaseAutoBiliSource
import moe.ouom.neriplayer.core.player.watchdog.configureActivePlaybackCandidates
import moe.ouom.neriplayer.core.player.watchdog.currentPlaybackCandidate
import moe.ouom.neriplayer.core.player.watchdog.resetPlaybackProgressAdvanceBaseline
import moe.ouom.neriplayer.core.player.watchdog.schedulePlaybackStartupWatchdog
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.File

private const val OFFLINE_CACHE_URL_PREFIX = "http://offline.cache/"
internal const val YOUTUBE_PLAYBACK_PREFER_M4A = false
private const val YOUTUBE_STABLE_RECOVERY_QUALITY = "high"

internal suspend fun PlayerManager.resolveSongUrl(
    song: SongItem,
    forceRefresh: Boolean = false,
    youtubeRecoveryStrategy: YouTubePlaybackRecoveryStrategy? = null,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects(),
    allowGenericPrefetchCache: Boolean = true
): SongUrlResult {
    NPLogger.d(
        "NERI-PlayerManager",
        "resolveSongUrl: song=${song.name}, source=${song.album}, forceRefresh=$forceRefresh, streamUrl=${song.streamUrl}, currentUrl=${_currentMediaUrl.value}, stack=[${debugStackHint()}]"
    )
    if (shouldWaitForListenTogetherAuthoritativeStream(song)) {
        return SongUrlResult.WaitingForAuthoritativeStream
    }
    if (!forceRefresh && isDirectStreamUrl(song.streamUrl)) {
        return SongUrlResult.Success(song.streamUrl.orEmpty())
    }
    if (isLocalSong(song)) {
        val localMediaUri = localMediaSource(song)
        if (localMediaUri != null && isReadableLocalMediaUri(localMediaUri)) {
            val playbackAudioInfo = localMediaUri.toLocalPlaybackUri()
                ?.let { buildLocalPlaybackAudioInfo(it, application) }
                ?: buildLocalPlaybackAudioInfo(song, application)
            return SongUrlResult.Success(
                url = toPlayableLocalUrl(localMediaUri) ?: localMediaUri,
                audioInfo = playbackAudioInfo
            )
        }
        sideEffects.emitError {
            postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
        }
        return SongUrlResult.Failure
    }

    val localResult = checkLocalCache(song, sideEffects)
    if (localResult != null) {
        NPLogger.d(
            "NERI-PlayerManager",
            "resolveSongUrl: hit local playback cache for song=${song.name}"
        )
        return localResult
    }
    val isYouTubeTrack = isYouTubeMusicTrack(song)
    val resolvedYouTubeQuality = youtubeRecoveryStrategy?.preferredQualityOverride
        ?: effectiveYouTubeQuality()
    val cacheKey = computeCacheKey(
        song = song,
        youtubeQualityOverride = youtubeRecoveryStrategy?.preferredQualityOverride,
        youtubePreferM4aOverride = youtubeRecoveryStrategy?.preferM4a
    )
    val bypassCompleteCache = forceRefresh && isYouTubeTrack
    val hasCachedData = if (bypassCompleteCache) {
        NPLogger.d(
            "NERI-PlayerManager",
            "resolveSongUrl: bypass complete YouTube cache for forced refresh: $cacheKey"
        )
        false
    } else {
        checkExoPlayerCache(cacheKey)
    }
    if (hasCachedData && isYouTubeTrack) {
        NPLogger.d(
            "NERI-PlayerManager",
            "命中完整 YouTube 缓存，直接走离线缓存地址: $cacheKey"
        )
        val cachedAudioInfo = _currentPlaybackAudioInfo.value
            ?.takeIf { _currentSongFlow.value?.sameIdentityAs(song) == true }
            ?.takeIf { youtubeRecoveryStrategy == null }
            ?: buildYouTubeOfflineCacheAudioInfo(resolvedYouTubeQuality) {
                getLocalizedString(it)
            }
        return SongUrlResult.Success(
            url = "$OFFLINE_CACHE_URL_PREFIX$cacheKey",
            durationMs = song.durationMs.takeIf { it > 0L },
            audioInfo = cachedAudioInfo,
            cacheKeyOverride = cacheKey
        )
    }
    if (!forceRefresh && allowGenericPrefetchCache && !isYouTubeTrack) {
        consumeGenericUrlPrefetch(cacheKey)?.let { return it }
    }
    val result = when {
        isYouTubeTrack -> getYouTubeMusicAudioUrl(
            song = song,
            suppressError = hasCachedData,
            forceRefresh = forceRefresh,
            youtubeRecoveryStrategy = youtubeRecoveryStrategy,
            sideEffects = sideEffects
        )
        isBiliTrack(song) -> getBiliAudioUrl(
            song = song,
            suppressError = hasCachedData,
            sideEffects = sideEffects
        )
        isKugouTrack(song) -> getKugouAudioUrl(
            song = song,
            forceRefresh = forceRefresh,
            sideEffects = sideEffects
        )
        else -> getNeteaseSongUrl(
            song = song,
            suppressError = hasCachedData,
            sideEffects = sideEffects
        )
    }

    return if (result is SongUrlResult.Failure && hasCachedData && !isYouTubeTrack) {
        NPLogger.d("NERI-PlayerManager", "远端解析失败但缓存完整，回退到离线缓存地址: $cacheKey")
        val fallbackAudioInfo = _currentPlaybackAudioInfo.value
        SongUrlResult.Success(
            url = "http://offline.cache/$cacheKey",
            audioInfo = fallbackAudioInfo
        )
    } else {
        result
    }
}

internal suspend fun PlayerManager.resolveShareableListenTogetherStreamUrl(
    song: SongItem
): SongUrlResult {
    NPLogger.d(
        "NERI-PlayerManager",
        "resolveShareableListenTogetherStreamUrl: song=${song.name}, source=${song.album}, streamUrl=${song.streamUrl}, currentUrl=${_currentMediaUrl.value}, stack=[${debugStackHint()}]"
    )
    if (isDirectStreamUrl(song.streamUrl)) {
        return SongUrlResult.Success(song.streamUrl.orEmpty())
    }
    if (isLocalSong(song)) {
        NPLogger.w(
            "NERI-PlayerManager",
            "resolveShareableListenTogetherStreamUrl: skip local-only song=${song.name}"
        )
        return SongUrlResult.Failure
    }

    val sideEffects = RefreshResolverSideEffects(RefreshSideEffectGate { false })
    val result = when {
        isYouTubeMusicTrack(song) -> getYouTubeMusicAudioUrl(
            song = song,
            suppressError = true,
            forceRefresh = true,
            sideEffects = sideEffects
        )
        isBiliTrack(song) -> getBiliAudioUrl(
            song = song,
            suppressError = true,
            sideEffects = sideEffects
        )
        isKugouTrack(song) -> getKugouAudioUrl(
            song = song,
            forceRefresh = true,
            sideEffects = sideEffects
        )
        else -> getNeteaseSongUrl(
            song = song,
            suppressError = true,
            sideEffects = sideEffects
        )
    }
    if (result is SongUrlResult.Success && !isDirectStreamUrl(result.url)) {
        NPLogger.w(
            "NERI-PlayerManager",
            "resolveShareableListenTogetherStreamUrl: rejected non-http result song=${song.name}, url=${result.url}"
        )
        return SongUrlResult.Failure
    }
    return result
}

private fun String.toLocalPlaybackUri(): Uri? {
    return if (startsWith("/")) {
        Uri.fromFile(File(this))
    } else {
        runCatching { toUri() }.getOrNull()
    }
}

internal fun PlayerManager.shouldAttemptUrlRefresh(
    error: PlaybackException,
    song: SongItem?,
    isOfflineCache: Boolean
): Boolean {
    if (song == null) return false
    if (isYouTubeMusicTrack(song)) {
        return shouldAttemptYouTubePlaybackRecovery(error, isOfflineCache)
    }
    if (isLocalSong(song)) return false
    if (isOfflineCache) return false
    return error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
}

internal fun PlayerManager.youtubePlaybackRecoveryStrategyForError(
    error: PlaybackException,
    song: SongItem?,
    isOfflineCache: Boolean
): YouTubePlaybackRecoveryStrategy? {
    if (song == null || !isYouTubeMusicTrack(song)) return null
    return resolveYouTubePlaybackRecoveryStrategy(error, isOfflineCache)
}

internal fun shouldAttemptYouTubePlaybackRecovery(
    error: PlaybackException,
    isOfflineCache: Boolean
): Boolean {
    return if (isOfflineCache) {
        isRecoverableYouTubeOfflineCacheError()
    } else {
        isRecoverableYouTubeRemotePlaybackError(error)
    }
}

internal fun resolveYouTubePlaybackRecoveryStrategy(
    error: PlaybackException,
    isOfflineCache: Boolean
): YouTubePlaybackRecoveryStrategy? {
    if (!shouldAttemptYouTubePlaybackRecovery(error, isOfflineCache)) return null
    return YouTubePlaybackRecoveryStrategy(
        preferredQualityOverride = YOUTUBE_STABLE_RECOVERY_QUALITY,
        requireDirect = true,
        preferM4a = true
    )
}

internal fun offlineCacheKeyFromUrl(url: String?): String? {
    return url
        ?.takeIf { it.startsWith(OFFLINE_CACHE_URL_PREFIX) }
        ?.removePrefix(OFFLINE_CACHE_URL_PREFIX)
        ?.takeIf { it.isNotBlank() }
}

private fun isRecoverableYouTubeOfflineCacheError(): Boolean = true

private fun isRecoverableYouTubeRemotePlaybackError(error: PlaybackException): Boolean {
    return error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT ||
        isRecoverableYouTubeFormatError(error)
}

private fun isRecoverableYouTubeFormatError(error: PlaybackException): Boolean {
    return error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
}

private fun PlayerManager.resumePlaybackFallback(
    seekPositionMs: Long?,
    resumePlaybackAfterRefresh: Boolean
) {
    mainScope.launch {
        val resolvedSeekPositionMs = seekPositionMs?.coerceAtLeast(0L)
        if (resolvedSeekPositionMs != null) {
            player.seekTo(resolvedSeekPositionMs)
            _playbackPositionMs.value = resolvedSeekPositionMs
        }
        player.playWhenReady = resumePlaybackAfterRefresh
        if (resumePlaybackAfterRefresh) {
            applyAudioFocusPolicyOnMainThread()
            player.play()
            schedulePlaybackStartupWatchdog(reason = "refresh_fallback")
        } else {
            player.pause()
        }
    }
}

internal fun PlayerManager.cancelUrlRefreshIfNotReusableForPendingLoad(
    song: SongItem,
    resumePositionMs: Long,
    requestGeneration: Long,
    commandSource: PlaybackCommandSource
) {
    val semantics = buildRefreshRequestSemantics(
        songKey = computeCacheKey(song),
        requestGeneration = requestGeneration,
        resumePositionMs = resumePositionMs,
        allowFallback = false,
        reason = "playAtIndex_pending_load",
        fallbackSeekPositionMs = null,
        resumePlaybackAfterRefresh = true,
        resumedPlaybackCommandSource = commandSource
    )
    if (urlRefreshController.cancelIfNotReusable(semantics)) {
        urlRefreshInProgress = false
    }
}

internal fun PlayerManager.refreshCurrentSongUrlImpl(
    resumePositionMs: Long,
    allowFallback: Boolean,
    reason: String,
    bypassCooldown: Boolean = false,
    fallbackSeekPositionMs: Long? = null,
    resumePlaybackAfterRefresh: Boolean = true,
    resumedPlaybackCommandSource: PlaybackCommandSource? = null,
    youtubeRecoveryStrategy: YouTubePlaybackRecoveryStrategy? = null,
    cacheKeyToInvalidateBeforeResolve: String? = null
) {
    val song = _currentSongFlow.value ?: return
    if (isLocalSong(song)) return
    NPLogger.d(
        "NERI-PlayerManager",
        "refreshCurrentSongUrl: song=${song.name}, resumePositionMs=$resumePositionMs, allowFallback=$allowFallback, reason=$reason, bypassCooldown=$bypassCooldown, resumePlaybackAfterRefresh=$resumePlaybackAfterRefresh, commandSource=$resumedPlaybackCommandSource, stack=[${debugStackHint()}]"
    )
    val cacheKey = computeCacheKey(
        song = song,
        youtubeQualityOverride = youtubeRecoveryStrategy?.preferredQualityOverride,
        youtubePreferM4aOverride = youtubeRecoveryStrategy?.preferM4a
    )
    val semantics = buildRefreshRequestSemantics(
        songKey = cacheKey,
        requestGeneration = playbackRequestToken,
        resumePositionMs = resumePositionMs,
        allowFallback = allowFallback,
        reason = reason,
        fallbackSeekPositionMs = fallbackSeekPositionMs,
        resumePlaybackAfterRefresh = resumePlaybackAfterRefresh,
        resumedPlaybackCommandSource = resumedPlaybackCommandSource,
        youtubeRecoveryStrategy = youtubeRecoveryStrategy,
        cacheKeyToInvalidateBeforeResolve = cacheKeyToInvalidateBeforeResolve
    )
    val now = SystemClock.elapsedRealtime()
    if (urlRefreshController.currentSemantics() == null &&
        !bypassCooldown &&
        lastUrlRefreshKey == cacheKey &&
        now - lastUrlRefreshAtMs < URL_REFRESH_COOLDOWN_MS
    ) {
        NPLogger.w(
            "NERI-PlayerManager",
            "refreshCurrentSongUrl throttled by cooldown: key=$cacheKey, reason=$reason, delta=${now - lastUrlRefreshAtMs}ms"
        )
        if (allowFallback) {
            resumePlaybackFallback(
                seekPositionMs = fallbackSeekPositionMs,
                resumePlaybackAfterRefresh = resumePlaybackAfterRefresh
            )
        } else {
            clearPendingSeekPosition()
            consecutivePlayFailures++
            postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.player_playback_network_error)))
            if (consecutivePlayFailures >= MAX_CONSECUTIVE_FAILURES) {
                mainScope.launch { stopPlaybackPreservingQueue(clearMediaUrl = true) }
            } else {
                mainScope.launch {
                    advanceAfterPlaybackFailure(source = "refresh_cooldown")
                }
            }
        }
        return
    }

    lastUrlRefreshKey = cacheKey
    lastUrlRefreshAtMs = now

    var refreshJob: kotlinx.coroutines.Job? = null
    var refreshDeferred: CompletableDeferred<SongUrlResult>? = null
    val start = urlRefreshController.startOrReuse(
        semantics = semantics,
        start = {
            val deferred = CompletableDeferred<SongUrlResult>()
            refreshDeferred = deferred
            val job = ioScope.launch(start = CoroutineStart.LAZY) {
                runRefreshOperation(
                    semantics = semantics,
                    song = song,
                    deferred = deferred
                )
            }
            refreshJob = job
            PlayerManager.UrlRefreshOperation(
                semantics = semantics,
                deferred = deferred,
                job = job
            )
        },
        cancel = {
            refreshJob?.cancel()
            refreshDeferred?.let { RefreshDeferredCompletion(it).cancel() }
        },
        fallback = {}
    )
    if (!start.startedNew) {
        ioScope.launch {
            runCatching { start.operation.deferred.await() }
        }
        return
    }
    if (!urlRefreshController.isCurrent(semantics)) {
        start.operation.job.cancel()
        RefreshDeferredCompletion(start.operation.deferred).cancel()
        return
    }
    urlRefreshInProgress = true
    start.operation.job.start()
}

private suspend fun PlayerManager.runRefreshOperation(
    semantics: RefreshRequestSemantics,
    song: SongItem,
    deferred: CompletableDeferred<SongUrlResult>
) {
    try {
        NPLogger.d("NERI-PlayerManager", "Refreshing stream url (${semantics.reason}): ${semantics.songKey}")
        semantics.cacheKeyToInvalidateBeforeResolve?.let { staleCacheKey ->
            invalidateCachedResourceBeforeResolve(
                cacheKey = staleCacheKey,
                reason = semantics.reason
            )
        }
        val result = resolveSongUrl(
            song = song,
            forceRefresh = isYouTubeMusicTrack(song),
            youtubeRecoveryStrategy = semantics.youtubeRecoveryStrategy,
            sideEffects = RefreshResolverSideEffects(refreshSideEffectGate(semantics, song))
        )
        deferred.complete(result)
        handleRefreshResult(semantics, song, result)
    } catch (error: CancellationException) {
        RefreshDeferredCompletion(deferred).cancel(error)
    } catch (error: Exception) {
        RefreshDeferredCompletion(deferred).completeExceptionally(error)
        NPLogger.e("NERI-PlayerManager", "refresh stream url failed (${semantics.reason})", error)
        handleRefreshResult(semantics, song, SongUrlResult.Failure)
    } finally {
        if (urlRefreshController.isCurrent(semantics)) {
            urlRefreshController.clear(semantics)
            urlRefreshInProgress = false
        } else {
            urlRefreshController.clear(semantics)
        }
    }
}

private suspend fun PlayerManager.handleRefreshResult(
    semantics: RefreshRequestSemantics,
    song: SongItem,
    result: SongUrlResult
) {
    val accepted = canApplyRefreshResult(semantics, song)
    when {
        result is SongUrlResult.Success -> {
            val action = resolveRefreshApplyAction(
                accepted = accepted,
                resultKind = RefreshResultKind.SUCCESS
            )
            val gate = refreshSideEffectGate(semantics, song)
            if (!action.updateDuration ||
                !RefreshResultSideEffects(gate).updateDuration {
                    maybeUpdateSongDuration(song, result.durationMs ?: 0L)
                }
            ) return
            withContext(Dispatchers.Main) {
                val applied = applyResolvedMediaItem(
                    gate = gate,
                    semantics = semantics,
                    song = _currentSongFlow.value ?: song,
                    result = result,
                    mimeType = result.mimeType,
                    expectedContentLength = result.expectedContentLength,
                    audioInfo = result.audioInfo,
                    cacheKeyOverride = result.cacheKeyOverride,
                    resumePositionMs = semantics.resumePositionMs,
                    resumePlaybackAfterRefresh = semantics.resumePlaybackAfterRefresh
                )
                if (!applied) return@withContext
                if (!gate.runMutation { consecutivePlayFailures = 0 }) return@withContext
                if (
                    semantics.resumePlaybackAfterRefresh &&
                    semantics.resumedPlaybackCommandSource == PlaybackCommandSource.LOCAL
                ) {
                    gate.runMutation {
                        emitPlaybackCommand(
                            type = "PLAY",
                            source = semantics.resumedPlaybackCommandSource,
                            positionMs = semantics.resumePositionMs.coerceAtLeast(0L),
                            currentIndex = currentIndex
                        )
                    }
                }
            }
        }
        semantics.allowFallback -> {
            val action = resolveRefreshApplyAction(
                accepted = accepted,
                resultKind = RefreshResultKind.FALLBACK
            )
            if (action.fallbackPlayPause) {
                withContext(Dispatchers.Main) {
                    val gate = refreshSideEffectGate(semantics, song)
                    val resolvedSeekPositionMs = semantics.fallbackSeekPositionMs?.coerceAtLeast(0L)
                    if (resolvedSeekPositionMs != null) {
                        if (!gate.runMutation {
                                player.seekTo(resolvedSeekPositionMs)
                                _playbackPositionMs.value = resolvedSeekPositionMs
                            }
                        ) return@withContext
                    }
                    gate.runMutation {
                        player.playWhenReady = semantics.resumePlaybackAfterRefresh
                        if (semantics.resumePlaybackAfterRefresh) {
                            applyAudioFocusPolicyOnMainThread()
                            player.play()
                        } else {
                            player.pause()
                        }
                    }
                }
            }
        }
        else -> {
            val action = resolveRefreshApplyAction(
                accepted = accepted,
                resultKind = RefreshResultKind.FAILURE
            )
            if (!action.emitFailureError) return
            val gate = refreshSideEffectGate(semantics, song)
            if (!gate.runMutation { clearPendingSeekPosition() }) return
            if (!gate.runMutation {
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.player_playback_network_error)))
                }
            ) return
            withContext(Dispatchers.Main) {
                refreshSideEffectGate(semantics, song).runMutation {
                    pause(commandSource = PlaybackCommandSource.REMOTE_SYNC)
                }
            }
        }
    }
}

private fun PlayerManager.refreshSideEffectGate(
    semantics: RefreshRequestSemantics,
    song: SongItem
) = RefreshSideEffectGate { canApplyRefreshResult(semantics, song) }

private fun PlayerManager.canApplyRefreshResult(
    semantics: RefreshRequestSemantics,
    song: SongItem
): Boolean {
    return _currentSongFlow.value?.sameIdentityAs(song) == true &&
        shouldApplyRefreshResult(
            owner = semantics,
            current = semantics.copy(requestGeneration = playbackRequestToken),
            currentRequestGeneration = playbackRequestToken,
            ownerActive = urlRefreshController.isCurrent(semantics)
        )
}

private fun buildRefreshRequestSemantics(
    songKey: String,
    requestGeneration: Long,
    resumePositionMs: Long,
    allowFallback: Boolean,
    reason: String,
    fallbackSeekPositionMs: Long?,
    resumePlaybackAfterRefresh: Boolean,
    resumedPlaybackCommandSource: PlaybackCommandSource?,
    youtubeRecoveryStrategy: YouTubePlaybackRecoveryStrategy? = null,
    cacheKeyToInvalidateBeforeResolve: String? = null
) = RefreshRequestSemantics(
    songKey = songKey,
    requestGeneration = requestGeneration,
    resumePositionMs = resumePositionMs.coerceAtLeast(0L),
    fallbackSeekPositionMs = fallbackSeekPositionMs?.coerceAtLeast(0L),
    resumePlaybackAfterRefresh = resumePlaybackAfterRefresh,
    allowFallback = allowFallback,
    reason = reason,
    resumedPlaybackCommandSource = resumedPlaybackCommandSource,
    youtubeRecoveryStrategy = youtubeRecoveryStrategy,
    cacheKeyToInvalidateBeforeResolve = cacheKeyToInvalidateBeforeResolve
)

private suspend fun PlayerManager.applyResolvedMediaItem(
    gate: RefreshSideEffectGate,
    semantics: RefreshRequestSemantics,
    song: SongItem,
    result: SongUrlResult.Success,
    mimeType: String?,
    expectedContentLength: Long?,
    audioInfo: PlaybackAudioInfo?,
    cacheKeyOverride: String?,
    resumePositionMs: Long,
    resumePlaybackAfterRefresh: Boolean
): Boolean {
    if (!gate.runMutation {}) return false

    val cacheKey = cacheKeyOverride ?: computeCacheKey(song)
    configureActivePlaybackCandidates(
        result = result,
        resumePositionMs = resumePositionMs,
        commandSource = semantics.resumedPlaybackCommandSource ?: PlaybackCommandSource.LOCAL,
        resetRecoveryAttempts = !semantics.reason.startsWith("startup_stall_")
    )
    val selectedCandidate = currentPlaybackCandidate()
    val selectedUrl = selectedCandidate?.url ?: result.url
    invalidateMismatchedCachedResource(
        cacheKey = cacheKey,
        expectedContentLength = expectedContentLength,
        shouldApplyMutation = { gate.runMutation {} }
    )
    val mediaItem = buildMediaItem(song, selectedUrl, cacheKey, mimeType)

    if (!gate.runMutation { _currentMediaUrl.value = selectedUrl }) return false
    if (!gate.runMutation { _currentPlaybackAudioInfo.value = audioInfo }) return false
    if (!gate.runMutation { currentMediaUrlResolvedAtMs = SystemClock.elapsedRealtime() }) return false
    if (!gate.runSuspendingMutation { persistState() }) return false

    var applied = false
    withContext(Dispatchers.Main) {
        if (!gate.runMutation {
                preparePlayerForManagedStart(
                    resolvePlaybackStartPlan(shouldFadeIn = false, fadeDurationMs = 0L)
                )
            }
        ) return@withContext
        if (!gate.runMutation { resetTrackEndDeduplicationState() }) return@withContext
        if (!gate.runMutation { applyWakeModeForPlaybackUrl(selectedUrl) }) return@withContext
        if (!gate.runMutation { player.setMediaItem(mediaItem) }) return@withContext
        if (!gate.runMutation { loadedMediaRequestToken = semantics.requestGeneration }) return@withContext
        if (!gate.runMutation { pendingMediaLoadActive = false }) return@withContext
        if (!gate.runMutation { syncExoRepeatMode() }) return@withContext
        val startPositionMs = pendingSeekPositionOrNull()
            ?: resumePositionMs.coerceAtLeast(0L)
        if (startPositionMs > 0) {
            if (!gate.runMutation {
                    player.seekTo(startPositionMs)
                    _playbackPositionMs.value = startPositionMs
                }
            ) return@withContext
        }
        if (!gate.runMutation { resetPlaybackProgressAdvanceBaseline(startPositionMs) }) return@withContext
        if (!gate.runMutation { clearPendingSeekPosition() }) return@withContext
        if (!gate.runMutation { player.prepare() }) return@withContext
        if (!gate.runMutation { player.playWhenReady = resumePlaybackAfterRefresh }) return@withContext
        if (!gate.runMutation {
                if (resumePlaybackAfterRefresh) {
                    applyAudioFocusPolicyOnMainThread()
                    player.play()
                    schedulePlaybackStartupWatchdog(reason = "refresh_applied")
                } else {
                    player.pause()
                }
            }
        ) return@withContext
        applied = true
    }
    return applied
}

private fun PlayerManager.checkLocalCache(
    song: SongItem,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects()
): SongUrlResult? {
    val context = application
    if (!AudioDownloadManager.mayHaveIndexedLocalDownload(context, song)) {
        return null
    }
    val localReference = AudioDownloadManager.getLocalPlaybackUri(context, song) ?: return null
    if (!isReadableLocalMediaUri(localReference)) {
        NPLogger.w(
            "NERI-PlayerManager",
            "checkLocalCache: 命中不可读本地引用，回退远端解析 song=${song.name}, reference=$localReference"
        )
        sideEffects.scanLocalFiles {
            GlobalDownloadManager.scanLocalFiles(context, forceRefresh = true)
        }
        return null
    }
    val durationMs = if (song.durationMs <= 0L) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            val localUri = localReference.toUri()
            when (localUri.scheme?.lowercase()) {
                "content", "android.resource" -> retriever.setDataSource(context, localUri)
                "file" -> retriever.setDataSource(localUri.path)
                null, "" -> retriever.setDataSource(localReference)
                else -> retriever.setDataSource(context, localUri)
            }
            retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    } else {
        null
    }
    val localAudioInfo = buildLocalPlaybackAudioInfo(localReference.toUri(), application)
    return SongUrlResult.Success(
        url = localReference,
        durationMs = durationMs,
        audioInfo = mergeLocalPlaybackAudioInfoWithRemoteQuality(
            localAudioInfo = localAudioInfo,
            previousAudioInfo = _currentPlaybackAudioInfo.value
                ?.takeIf { _currentSongFlow.value?.sameIdentityAs(song) == true }
        )
    )
}

internal fun PlayerManager.checkExoPlayerCache(cacheKey: String): Boolean {
    return try {
        if (!isCacheInitialized()) return false

        val cachedSpans = cache.getCachedSpans(cacheKey)
        if (cachedSpans.isEmpty()) return false

        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        if (contentLength <= 0L) {
            NPLogger.d("NERI-PlayerManager", "缓存命中但缺少内容长度，视为未完成缓存: $cacheKey")
            return false
        }

        val orderedSpans = cachedSpans.sortedBy { it.position }
        var coveredUntil = 0L
        for (span in orderedSpans) {
            if (span.position > coveredUntil) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "缓存存在空洞，视为未完成缓存: $cacheKey @ ${span.position}"
                )
                return false
            }
            coveredUntil = maxOf(coveredUntil, span.position + span.length)
            if (coveredUntil >= contentLength) break
        }

        val isComplete = coveredUntil >= contentLength
        if (isComplete) {
            NPLogger.d(
                "NERI-PlayerManager",
                "缓存完整可用: $cacheKey, length=$contentLength, spans=${cachedSpans.size}"
            )
        } else {
            NPLogger.d(
                "NERI-PlayerManager",
                "缓存未完整覆盖: $cacheKey, covered=$coveredUntil/$contentLength"
            )
        }

        isComplete
    } catch (e: Exception) {
        NPLogger.w("NERI-PlayerManager", "检查缓存完整性失败: ${e.message}")
        false
    }
}

internal suspend fun PlayerManager.invalidateMismatchedCachedResource(
    cacheKey: String,
    expectedContentLength: Long?,
    shouldApplyMutation: () -> Boolean = { true }
) = withContext(Dispatchers.IO) {
    val expectedLength = expectedContentLength?.takeIf { it > 0L } ?: return@withContext
    if (!isCacheInitialized()) return@withContext

    try {
        val cachedSpans = cache.getCachedSpans(cacheKey)
        if (cachedSpans.isEmpty()) return@withContext

        val cachedContentLength = ContentMetadata.getContentLength(
            cache.getContentMetadata(cacheKey)
        )
        if (!shouldReplaceCachedPreviewResource(cachedContentLength, expectedLength)) {
            return@withContext
        }

        NPLogger.w(
            "NERI-PlayerManager",
            "缓存疑似预览片段，移除旧缓存以便重新拉取完整资源: key=$cacheKey, cached=$cachedContentLength, expected=$expectedLength"
        )
        if (!shouldApplyMutation()) return@withContext
        cache.removeResource(cacheKey)
    } catch (e: Exception) {
        NPLogger.w(
            "NERI-PlayerManager",
            "移除不匹配缓存失败: key=$cacheKey, error=${e.message}"
        )
    }
}

private suspend fun PlayerManager.invalidateCachedResourceBeforeResolve(
    cacheKey: String,
    reason: String
) = withContext(Dispatchers.IO) {
    if (!isCacheInitialized()) return@withContext
    try {
        cache.removeResource(cacheKey)
        NPLogger.w(
            "NERI-PlayerManager",
            "已移除异常播放缓存: key=$cacheKey, reason=$reason"
        )
    } catch (e: Exception) {
        NPLogger.w(
            "NERI-PlayerManager",
            "移除异常播放缓存失败: key=$cacheKey, reason=$reason, error=${e.message}"
        )
    }
}

private suspend fun PlayerManager.getNeteaseSongUrl(
    song: SongItem,
    suppressError: Boolean = false,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects()
): SongUrlResult = withContext(Dispatchers.IO) {
    try {
        val effectiveQuality = effectiveNeteaseQuality()
        val qualityCandidates = buildNeteaseQualityCandidates(effectiveQuality)
        var previewFallback: SongUrlResult.Success? = null
        var lastFailureReason: NeteasePlaybackResponseParser.FailureReason? = null

        for ((index, quality) in qualityCandidates.withIndex()) {
            val resp = neteaseClient.getSongDownloadUrl(
                song.id,
                level = quality
            )
            NPLogger.d("NERI-PlayerManager", "id=${song.id}, level=$quality, resp=$resp")

            when (val parsed = NeteasePlaybackResponseParser.parsePlayback(resp, song.durationMs)) {
                is NeteasePlaybackResponseParser.PlaybackResult.RequiresLogin -> {
                    return@withContext SongUrlResult.RequiresLogin
                }

                is NeteasePlaybackResponseParser.PlaybackResult.Success -> {
                    val success = buildNeteaseSuccessResult(
                        parsed = parsed,
                        resolvedQualityKey = quality,
                        fallbackDurationMs = song.durationMs,
                        getLocalizedString = { getLocalizedString(it) }
                    )
                    if (parsed.notice != NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                        if (quality != effectiveQuality) {
                            NPLogger.w(
                                "NERI-PlayerManager",
                                "当前音质不可用，已自动降级: id=${song.id}, preferred=$effectiveQuality, resolved=$quality"
                            )
                        }
                        return@withContext success
                    }

                    previewFallback = success
                    if (index < qualityCandidates.lastIndex) {
                        NPLogger.w(
                            "NERI-PlayerManager",
                            "当前音质仅返回试听片段，继续尝试更低音质: id=${song.id}, level=$quality"
                        )
                        continue
                    }
                }

                is NeteasePlaybackResponseParser.PlaybackResult.Failure -> {
                    lastFailureReason = parsed.reason
                    if (index < qualityCandidates.lastIndex &&
                        shouldRetryNeteaseWithLowerQuality(parsed.reason)
                    ) {
                        NPLogger.w(
                            "NERI-PlayerManager",
                            "当前音质不可播放，继续尝试更低音质: id=${song.id}, level=$quality, reason=${parsed.reason}"
                        )
                        continue
                    }
                    break
                }
            }
        }

        if (previewFallback != null ||
            lastFailureReason == NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION
        ) {
            tryResolveNeteaseAutoBiliSource(song, sideEffects)?.let {
                return@withContext it
            }
        }

        previewFallback?.let { return@withContext it }

        if (!suppressError) {
            val messageRes = when (lastFailureReason) {
                NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION ->
                    R.string.player_netease_no_permission_switch_platform
                NeteasePlaybackResponseParser.FailureReason.NO_PLAY_URL,
                NeteasePlaybackResponseParser.FailureReason.UNKNOWN,
                null -> R.string.error_no_play_url
            }
            sideEffects.emitError {
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(messageRes)))
            }
        }
        SongUrlResult.Failure
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        NPLogger.e("NERI-PlayerManager", "Failed to get url", e)
        if (!suppressError) {
            sideEffects.emitError {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(
                            R.string.player_playback_url_error_detail,
                            e.message.orEmpty()
                        )
                    )
                )
            }
        }
        SongUrlResult.Failure
    }
}

private suspend fun PlayerManager.getBiliAudioUrl(
    song: SongItem,
    suppressError: Boolean = false,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects()
): SongUrlResult = withContext(Dispatchers.IO) {
    try {
        val resolved = resolveBiliSong(song, biliClient)
        if (resolved == null || resolved.cid == 0L) {
            if (!suppressError) {
                sideEffects.emitError {
                    postPlayerEvent(
                        PlayerEvent.ShowError(
                            getLocalizedString(R.string.player_playback_video_info_unavailable)
                        )
                    )
                }
            }
            return@withContext SongUrlResult.Failure
        }

        val (availableStreams, audioStream) = biliRepo.getAudioWithDecision(
            resolved.videoInfo.bvid,
            resolved.cid,
            preferredKeyOverride = effectiveBiliQuality()
        )

        if (audioStream?.url != null) {
            NPLogger.d("NERI-PlayerManager-BiliAudioUrl", audioStream.url)
            SongUrlResult.Success(
                url = audioStream.url,
                candidateUrls = audioStream.candidateUrls,
                mimeType = audioStream.mimeType,
                audioInfo = buildBiliPlaybackAudioInfo(audioStream, availableStreams) {
                    getLocalizedString(it)
                }
            )
        } else {
            if (!suppressError) {
                sideEffects.emitError {
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
                }
            }
            SongUrlResult.Failure
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        NPLogger.e("NERI-PlayerManager", "Failed to get Bili play url", e)
        if (!suppressError) {
            sideEffects.emitError {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(
                            R.string.player_playback_url_error_detail,
                            e.message.orEmpty()
                        )
                    )
                )
            }
        }
        SongUrlResult.Failure
    }
}

private suspend fun PlayerManager.getKugouAudioUrl(
    song: SongItem,
    forceRefresh: Boolean,
    sideEffects: RefreshResolverSideEffects
): SongUrlResult {
    return withContext(Dispatchers.IO) {
        try {
            val hash = song.audioId ?: return@withContext SongUrlResult.Failure

            val response = AppContainer.kugouClient.song.getSongUrl(
                hash = hash,
                quality = "128"
            )

            Log.d("NERI-PlayerManager","hash $hash res:${response.body}")

            if (response.status != 200) {
                return@withContext SongUrlResult.Failure
            }

            val data = response.body
            val url = data["url"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                ?: data["backupUrl"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content

            if (url.isNullOrBlank()) {
                return@withContext SongUrlResult.Failure
            }

            SongUrlResult.Success(
                url = url,
                cacheKeyOverride = "kugou_$hash",
                mimeType = "audio/mpeg"
            )
        } catch (e: Exception) {
            SongUrlResult.Failure
        }
    }
}

private suspend fun PlayerManager.getYouTubeMusicAudioUrl(
    song: SongItem,
    suppressError: Boolean = false,
    forceRefresh: Boolean = false,
    youtubeRecoveryStrategy: YouTubePlaybackRecoveryStrategy? = null,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects()
): SongUrlResult = withContext(Dispatchers.IO) {
    val videoId = extractYouTubeMusicVideoId(song.mediaUri)
    if (videoId.isNullOrBlank()) {
        if (!suppressError) {
            sideEffects.emitError {
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
            }
        }
        return@withContext SongUrlResult.Failure
    }

    val resolveStartedAtMs = System.currentTimeMillis()
    try {
        val preferredQuality = youtubeRecoveryStrategy?.preferredQualityOverride
            ?: effectiveYouTubeQuality()
        val requireDirect = youtubeRecoveryStrategy?.requireDirect ?: false
        // 首播保留用户选择；出错恢复时才切到更稳的 m4a 直链
        val preferM4a = youtubeRecoveryStrategy?.preferM4a ?: false
        val resolvedPlayableAudio = youtubeMusicPlaybackRepository.getBestPlayableAudio(
            videoId = videoId,
            preferredQualityOverride = preferredQuality,
            forceRefresh = forceRefresh,
            requireDirect = requireDirect,
            preferM4a = preferM4a,
            shareInFlight = youtubeRecoveryStrategy == null
        )?.takeIf { it.url.isNotBlank() }
        if (resolvedPlayableAudio != null) {
            sideEffects.updateDuration {
                maybeUpdateSongDuration(song, resolvedPlayableAudio.durationMs)
            }
            NPLogger.d(
                "NERI-PlayerManager",
                "Resolved YouTube Music stream: videoId=$videoId, quality=$preferredQuality, recovery=${youtubeRecoveryStrategy != null}, preferM4a=$preferM4a, type=${resolvedPlayableAudio.streamType}, mime=${resolvedPlayableAudio.mimeType}, contentLength=${resolvedPlayableAudio.contentLength}, elapsedMs=${System.currentTimeMillis() - resolveStartedAtMs}"
            )
            SongUrlResult.Success(
                url = resolvedPlayableAudio.url,
                durationMs = resolvedPlayableAudio.durationMs.takeIf { it > 0L },
                mimeType = resolvedPlayableAudio.mimeType,
                expectedContentLength = resolvedPlayableAudio.contentLength,
                audioInfo = buildYouTubePlaybackAudioInfo(resolvedPlayableAudio) {
                    getLocalizedString(it)
                },
                cacheKeyOverride = youtubeRecoveryStrategy?.let { strategy ->
                    computeYouTubeCacheKey(
                        videoId = videoId,
                        preferredQuality = strategy.preferredQualityOverride,
                        preferM4a = strategy.preferM4a
                    )
                }
            )
        } else {
            NPLogger.w(
                "NERI-PlayerManager",
                "Resolve YouTube Music stream returned empty: videoId=$videoId, elapsedMs=${System.currentTimeMillis() - resolveStartedAtMs}"
            )
            if (!suppressError) {
                sideEffects.emitError {
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
                }
            }
            SongUrlResult.Failure
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        NPLogger.e(
            "NERI-PlayerManager",
            "Failed to get YouTube Music play url: videoId=$videoId, elapsedMs=${System.currentTimeMillis() - resolveStartedAtMs}",
            e
        )
        if (!suppressError) {
            sideEffects.emitError {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(
                            R.string.player_playback_url_error_detail,
                            e.message.orEmpty()
                        )
                    )
                )
            }
        }
        SongUrlResult.Failure
    }
}
