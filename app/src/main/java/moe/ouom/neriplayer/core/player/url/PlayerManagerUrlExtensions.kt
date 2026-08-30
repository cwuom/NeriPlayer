@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.url

import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliSponsorBlockTarget
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.download.LocalPlaybackReferenceResolution
import moe.ouom.neriplayer.core.player.lifecycle.updateAudioOffloadPreferences
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlayerEvent
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
import moe.ouom.neriplayer.core.player.policy.refresh.resolveRefreshedMediaStartPosition
import moe.ouom.neriplayer.core.player.policy.refresh.shouldApplyRefreshResult
import moe.ouom.neriplayer.core.player.policy.refresh.YouTubePlaybackRecoveryStrategy
import moe.ouom.neriplayer.core.player.playback.advanceAfterPlaybackFailure
import moe.ouom.neriplayer.core.player.playback.BiliSponsorBlockPlaybackController
import moe.ouom.neriplayer.core.player.playback.BiliVideoSkipPlaybackController
import moe.ouom.neriplayer.core.player.playback.preparePlayerForManagedStart
import moe.ouom.neriplayer.core.player.prefetch.consumeGenericUrlPrefetch
import moe.ouom.neriplayer.core.player.quality.effectiveBiliQuality
import moe.ouom.neriplayer.core.player.quality.effectiveNeteaseQuality
import moe.ouom.neriplayer.core.player.quality.effectiveYouTubeQuality
import moe.ouom.neriplayer.core.player.resolver.netease.NeteasePlaybackResponseParser
import moe.ouom.neriplayer.core.player.resolver.netease.tryResolveNeteaseAutoBiliSource
import moe.ouom.neriplayer.core.player.resolver.netease.tryResolveNeteaseMatchedLocalSource
import moe.ouom.neriplayer.core.player.watchdog.configureActivePlaybackCandidates
import moe.ouom.neriplayer.core.player.watchdog.currentPlaybackCandidate
import moe.ouom.neriplayer.core.player.watchdog.resetPlaybackProgressAdvanceBaseline
import moe.ouom.neriplayer.core.player.watchdog.schedulePlaybackStartupWatchdog
import moe.ouom.neriplayer.data.model.recoverNeteaseRemoteSourceFromStaleLocalCopy
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipTarget
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.listentogether.mapping.MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES
import moe.ouom.neriplayer.listentogether.mapping.toListenTogetherTrackOrNull
import moe.ouom.neriplayer.listentogether.mapping.trustedListenTogetherStreamUrls
import moe.ouom.neriplayer.listentogether.playback.shouldPreferListenTogetherSourceBeforeNeteaseFallback
import moe.ouom.neriplayer.listentogether.playback.shouldSuppressListenTogetherResolverError
import java.io.File

internal const val OFFLINE_CACHE_URL_PREFIX = "http://offline.cache/"
internal const val YOUTUBE_PLAYBACK_PREFER_M4A = false
internal const val YOUTUBE_STABLE_RECOVERY_QUALITY = "high"

private const val LOCAL_PLAYBACK_RESOLUTION_RETRY_COUNT = 6
private const val LOCAL_PLAYBACK_RESOLUTION_RETRY_DELAY_MS = 50L
private const val LOCAL_PLAYBACK_RESOLUTION_RETRY_MAX_DELAY_MS = 250L

internal data class CachedResourceIntegrity(
    val isComplete: Boolean,
    val requiresRepair: Boolean,
    val coveredLength: Long
)

internal enum class CachePrefetchReadiness {
    COMPLETE,
    READY_FOR_PREFETCH,
    UNAVAILABLE
}

internal fun inspectCachedResourceSpans(
    spans: Collection<CacheSpan>,
    contentLength: Long
): CachedResourceIntegrity {
    if (contentLength <= 0L || spans.isEmpty()) {
        return CachedResourceIntegrity(
            isComplete = false,
            requiresRepair = false,
            coveredLength = 0L
        )
    }

    var coveredUntil = 0L
    var requiresRepair = false
    var hasGap = false
    for (span in spans.sortedBy { it.position }) {
        val file = span.file
        val hasValidFile = span.isCached &&
            span.position >= 0L &&
            span.length > 0L &&
            file?.isFile == true &&
            file.length() == span.length
        if (!hasValidFile) {
            requiresRepair = true
            continue
        }

        val endPosition = span.position + span.length
        if (endPosition < span.position) {
            requiresRepair = true
            continue
        }
        if (
            span.position >= contentLength ||
            endPosition > contentLength
        ) {
            requiresRepair = true
            continue
        }
        if (span.position > coveredUntil) {
            hasGap = true
            continue
        }
        if (span.position < coveredUntil) {
            requiresRepair = true
            continue
        }
        coveredUntil = maxOf(coveredUntil, endPosition)
    }

    return CachedResourceIntegrity(
        isComplete = coveredUntil >= contentLength && !requiresRepair && !hasGap,
        requiresRepair = requiresRepair,
        coveredLength = coveredUntil
    )
}

internal suspend fun PlayerManager.resolveSongUrl(
    song: SongItem,
    forceRefresh: Boolean = false,
    youtubeRecoveryStrategy: YouTubePlaybackRecoveryStrategy? = null,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects(),
    allowGenericPrefetchCache: Boolean = true,
    playbackRequestTokenOverride: Long? = null,
    shouldApplyCacheMutation: () -> Boolean = { true }
): SongUrlResult {
    NPLogger.d(
        "NERI-PlayerManager",
        "resolveSongUrl: song=${song.name}, source=${song.album}, forceRefresh=$forceRefresh, streamUrl=${song.streamUrl}, currentUrl=${_currentMediaUrl.value}, stack=[${debugStackHint()}]"
    )
    val initialListenTogetherFallback = listenTogetherFallbackResult(song)
    val suppressListenTogetherResolverErrors = shouldSuppressListenTogetherResolverError(
        listenerAudioLinkSharingActive = isListenTogetherAudioLinkFallbackEnabled(),
        controllerLinkConfirmedUnavailable =
            isListenTogetherAuthoritativeStreamConfirmedUnavailable(song)
    )
    val shouldDeferNeteaseAlternateSources =
        shouldPreferListenTogetherSourceBeforeNeteaseFallback(
            listenerAudioLinkSharingActive = isListenTogetherAudioLinkFallbackEnabled()
        )
    if (isLocalSong(song)) {
        val localResolution = resolvePermittedLocalPlaybackWithRetry(
            song = song,
            rawLocalReference = localMediaSource(song)
        )
        if (localResolution is LocalPlaybackReferenceResolution.Playable) {
            val localMediaUri = localResolution.reference
            val playbackAudioInfo = localMediaUri.toLocalPlaybackUri()
                ?.let { buildLocalPlaybackAudioInfo(it, application) }
                ?: buildLocalPlaybackAudioInfo(song, application)
            prepareBiliPlaybackSkipsForResolvedPlayback(song, playbackRequestTokenOverride)
            return SongUrlResult.Success(
                url = toPlayableLocalUrl(localMediaUri) ?: localMediaUri,
                audioInfo = playbackAudioInfo
            )
        }
        if (localResolution is LocalPlaybackReferenceResolution.Missing) {
            song.recoverNeteaseRemoteSourceFromStaleLocalCopy()?.let { recoveredSong ->
                NPLogger.w(
                    "NERI-PlayerManager",
                    "Downloaded reference is explicitly missing, retry as Netease: " +
                        "song=${song.name}, stale=${localMediaSource(song)}"
                )
                return resolveSongUrl(
                    song = recoveredSong,
                    forceRefresh = forceRefresh,
                    youtubeRecoveryStrategy = youtubeRecoveryStrategy,
                    sideEffects = sideEffects,
                    allowGenericPrefetchCache = allowGenericPrefetchCache,
                    playbackRequestTokenOverride = playbackRequestTokenOverride,
                    shouldApplyCacheMutation = shouldApplyCacheMutation
                )
            }
        } else {
            NPLogger.w(
                "NERI-PlayerManager",
                "Local reference is temporarily unavailable; remote fallback is blocked: " +
                    "song=${song.name}, resolution=$localResolution"
            )
        }
        sideEffects.emitError {
            postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
        }
        return SongUrlResult.Failure
    }

    val localResult = checkLocalCache(song, sideEffects)
    if (localResult != null) {
        prepareBiliPlaybackSkipsForResolvedPlayback(song, playbackRequestTokenOverride)
        NPLogger.d(
            "NERI-PlayerManager",
            "resolveSongUrl: hit local playback cache for song=${song.name}"
        )
        return mergeListenTogetherFallbackResult(
            localResult = localResult,
            listenTogetherFallback = initialListenTogetherFallback,
            preferredQualityKey = listenTogetherPreferredQualityKey(song)
        )
    }
    if (
        shouldUseDirectStreamShortcut(
            forceRefresh = forceRefresh,
            hasListenTogetherFallback = initialListenTogetherFallback != null
        ) && isDirectStreamUrl(song.streamUrl)
    ) {
        prepareBiliPlaybackSkipsForResolvedPlayback(song, playbackRequestTokenOverride)
        return SongUrlResult.Success(song.streamUrl.orEmpty())
    }
    val isYouTubeTrack = isYouTubeMusicTrack(song)
    val cacheKey = computeCacheKey(
        song = song,
        youtubeQualityOverride = youtubeRecoveryStrategy?.preferredQualityOverride,
        youtubePreferM4aOverride = youtubeRecoveryStrategy?.preferM4a
    )
    val cacheKeyIsUnsafe = loadPlaybackCacheKeySafety(cacheKey)
    val cacheIntegrity = if (forceRefresh) {
        NPLogger.d(
            "NERI-PlayerManager",
            "resolveSongUrl: bypass complete YouTube cache for forced refresh: $cacheKey"
        )
        CachedResourceIntegrity(false, false, 0L)
    } else {
        inspectExoPlayerCache(cacheKey)
    }
    if (cacheIntegrity.requiresRepair) {
        invalidateCachedResourceForPlaybackRecovery(
            cacheKey = cacheKey,
            reason = "resolve_integrity_check",
            shouldApplyMutation = shouldApplyCacheMutation
        )
    }
    var hasCachedData = !forceRefresh &&
        cacheIntegrity.isComplete &&
        !cacheKeyIsUnsafe
    if (hasCachedData) {
        val cachedDescriptor = cache?.readCachedPlaybackDescriptor(cacheKey)
        val cachedAudioInfo = cachedDescriptor?.toPlaybackAudioInfo {
            getLocalizedString(it)
        }
        val cachedContentLength = cache?.let {
            ContentMetadata.getContentLength(it.getContentMetadata(cacheKey))
        } ?: 0L
        if (
            cachedAudioInfo == null ||
            !cachedDescriptor.matchesCachedContentLength(cachedContentLength)
        ) {
            NPLogger.w(
                "NERI-PlayerManager",
                "完整缓存描述符缺失或长度不匹配，淘汰旧资源并重新解析: key=$cacheKey"
            )
            val removed = invalidateCachedResourceForPlaybackRecovery(
                cacheKey = cacheKey,
                reason = "missing_playback_descriptor",
                shouldApplyMutation = shouldApplyCacheMutation
            )
            hasCachedData = false
            if (!removed) {
                NPLogger.w(
                    "NERI-PlayerManager",
                    "无法移除缺少描述符的缓存，跳过离线复用: key=$cacheKey"
                )
            }
        }
    }
    if (hasCachedData) {
        NPLogger.d(
            "NERI-PlayerManager",
            "命中完整缓存，直接走离线缓存地址: $cacheKey"
        )
        val cachedDescriptor = cache?.readCachedPlaybackDescriptor(cacheKey)
        val cachedAudioInfo = cachedDescriptor?.toPlaybackAudioInfo {
            getLocalizedString(it)
        } ?: return SongUrlResult.Failure
        prepareBiliPlaybackSkipsForResolvedPlayback(song, playbackRequestTokenOverride)
        val cachedResult = SongUrlResult.Success(
            url = "$OFFLINE_CACHE_URL_PREFIX$cacheKey",
            durationMs = song.durationMs.takeIf { it > 0L },
            audioInfo = cachedAudioInfo,
            mimeType = cachedAudioInfo.mimeType,
            expectedContentLength = cachedDescriptor.expectedContentLength,
            representationIdentity = cachedDescriptor.representationIdentity,
            cacheKeyOverride = cacheKey
        )
        return mergeListenTogetherFallbackResult(
            localResult = cachedResult,
            listenTogetherFallback = initialListenTogetherFallback,
            preferredQualityKey = listenTogetherPreferredQualityKey(song)
        )
    }
    if (!forceRefresh && allowGenericPrefetchCache && !isYouTubeTrack) {
        consumeGenericUrlPrefetch(cacheKey, song)?.let { prefetchedResult ->
            prepareBiliPlaybackSkipsForResolvedPlayback(song)
            return mergeListenTogetherFallbackResult(
                localResult = prefetchedResult,
                listenTogetherFallback = initialListenTogetherFallback,
                preferredQualityKey = listenTogetherPreferredQualityKey(song)
            )
        }
    }
    val resolverSideEffects = if (
        initialListenTogetherFallback != null || suppressListenTogetherResolverErrors
    ) {
        RefreshResolverSideEffects(RefreshSideEffectGate { false })
    } else {
        sideEffects
    }
    val result = retrySongUrlResolution { retryAttempt ->
        val isFinalAttempt = retryAttempt == SONG_URL_RESOLUTION_RETRY_COUNT
        if (retryAttempt > 0) {
            NPLogger.w(
                "NERI-PlayerManager",
                "resolveSongUrl: retry=$retryAttempt/$SONG_URL_RESOLUTION_RETRY_COUNT, song=${song.name}, source=${song.album}"
            )
        }
        val suppressError = hasCachedData ||
            !isFinalAttempt ||
            initialListenTogetherFallback != null ||
            suppressListenTogetherResolverErrors
        when {
            isYouTubeTrack -> getYouTubeMusicAudioUrl(
                song = song,
                suppressError = suppressError,
                forceRefresh = forceRefresh,
                youtubeRecoveryStrategy = youtubeRecoveryStrategy,
                sideEffects = resolverSideEffects
            )
            isBiliTrack(song) -> getBiliAudioUrl(
                song = song,
                suppressError = suppressError,
                sideEffects = resolverSideEffects,
                playbackRequestTokenOverride = playbackRequestTokenOverride
            )
            else -> getNeteaseSongUrl(
                song = song,
                suppressError = suppressError,
                sideEffects = resolverSideEffects,
                allowLocalFallback = !shouldDeferNeteaseAlternateSources,
                allowAutoBiliFallback = !shouldDeferNeteaseAlternateSources
            )
        }
    }

    val listenTogetherFallback = listenTogetherFallbackResult(song)
    val resolvedResult = mergeListenTogetherFallbackResult(
        localResult = result,
        listenTogetherFallback = listenTogetherFallback,
        preferredQualityKey = listenTogetherPreferredQualityKey(song)
    )
    if (listenTogetherFallback != null) {
        val localCandidateCount = (result as? SongUrlResult.Success)
            ?.playbackCandidates()
            ?.size
            ?: 0
        val fallbackCandidateCount = listenTogetherFallback.playbackCandidates().size
        val action = if (result is SongUrlResult.Success) "append" else "use"
        NPLogger.w(
            "NERI-PlayerManager",
            "resolveSongUrl: $action isolated listen-together fallback candidates: " +
                "song=${song.name}, localCandidates=$localCandidateCount, " +
                "fallbackCandidates=$fallbackCandidateCount"
        )
    }

    return if (resolvedResult is SongUrlResult.Failure && hasCachedData) {
        NPLogger.d("NERI-PlayerManager", "远端解析失败但缓存完整，回退到离线缓存地址: $cacheKey")
        val fallbackDescriptor = cache?.readCachedPlaybackDescriptor(cacheKey)
        val fallbackAudioInfo = fallbackDescriptor?.toPlaybackAudioInfo {
            getLocalizedString(it)
        }
        SongUrlResult.Success(
            url = "$OFFLINE_CACHE_URL_PREFIX$cacheKey",
            audioInfo = fallbackAudioInfo,
            mimeType = fallbackAudioInfo?.mimeType,
            expectedContentLength = fallbackDescriptor?.expectedContentLength,
            representationIdentity = fallbackDescriptor?.representationIdentity,
            cacheKeyOverride = cacheKey,
            durationMs = song.durationMs.takeIf { it > 0L }
        )
    } else {
        resolvedResult
    }
}

private fun PlayerManager.prepareBiliPlaybackSkipsForResolvedPlayback(
    song: SongItem,
    playbackRequestTokenOverride: Long? = null
) {
    if (
        !isBiliTrack(song) ||
        isListenTogetherActive() ||
        _currentSongFlow.value?.sameIdentityAs(song) != true
    ) {
        return
    }
    val requestToken = playbackRequestTokenOverride ?: playbackRequestToken
    BiliSponsorBlockPlaybackController.prepareActiveBiliTrackTarget(
        song = song,
        requestToken = requestToken,
        scope = ioScope
    )
    BiliVideoSkipPlaybackController.prepareActiveBiliTrackTarget(
        song = song,
        requestToken = requestToken,
        scope = ioScope
    )
}

internal data class ShareableListenTogetherStreamResolution(
    val streamUrls: List<String>,
    val isPreviewOnly: Boolean
)

internal suspend fun PlayerManager.resolveShareableListenTogetherStreamUrls(
    song: SongItem
): ShareableListenTogetherStreamResolution {
    val track = song.toListenTogetherTrackOrNull()
        ?: return ShareableListenTogetherStreamResolution(emptyList(), isPreviewOnly = false)
    val resolution = when {
        isYouTubeMusicTrack(song) -> resolveYouTubeListenTogetherShareableStreams(song)
        isBiliTrack(song) -> resolveBiliListenTogetherShareableStreams(song)
        else -> resolveNeteaseListenTogetherShareableStreams(song)
    }
    val trustedUrls = trustedListenTogetherStreamUrls(
        channelId = track.channelId,
        streamUrls = resolution.streamUrls,
        maxCount = MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES
    )
    return ShareableListenTogetherStreamResolution(
        streamUrls = trustedUrls,
        isPreviewOnly = resolution.isPreviewOnly && trustedUrls.isEmpty()
    )
}

private suspend fun PlayerManager.resolveNeteaseListenTogetherShareableStreams(
    song: SongItem
): ShareableListenTogetherStreamResolution = withContext(Dispatchers.IO) {
    if (isLocalSong(song)) {
        return@withContext ShareableListenTogetherStreamResolution(
            streamUrls = emptyList(),
            isPreviewOnly = false
        )
    }
    val streamUrls = linkedSetOf<String>()
    val resolvedQualityKeys = linkedSetOf<String>()
    var previewOnly = false
    val qualityGroups = buildListenTogetherNeteaseQualityGroups(effectiveNeteaseQuality())
    for (qualityGroup in qualityGroups) {
        if (streamUrls.size >= MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES) break
        for (requestedQuality in qualityGroup) {
            val response = runCatching {
                neteaseClient.getSongDownloadUrl(song.id, level = requestedQuality)
            }.getOrNull() ?: continue
            when (val parsed = NeteasePlaybackResponseParser.parsePlayback(response, song.durationMs)) {
                is NeteasePlaybackResponseParser.PlaybackResult.Success -> {
                    if (parsed.notice == NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                        previewOnly = true
                        continue
                    }
                    val resolved = buildNeteaseSuccessResult(
                        parsed = parsed,
                        resolvedQualityKey = requestedQuality,
                        fallbackDurationMs = song.durationMs,
                        getLocalizedString = { getLocalizedString(it) }
                    )
                    if (isDirectStreamUrl(resolved.url)) {
                        val actualQualityKey = resolved.audioInfo?.qualityKey
                            ?.trim()
                            ?.lowercase()
                            ?.ifBlank { requestedQuality }
                            ?: requestedQuality
                        if (tryRegisterNeteaseListenTogetherQualityCandidate(
                                resolvedQualityKeys = resolvedQualityKeys,
                                actualQualityKey = actualQualityKey
                            )
                        ) {
                            streamUrls += decorateListenTogetherStreamUrl(
                                streamUrl = resolved.url,
                                source = PlaybackAudioSource.NETEASE,
                                qualityKey = actualQualityKey
                            )
                            break
                        }
                    }
                }
                NeteasePlaybackResponseParser.PlaybackResult.RequiresLogin,
                is NeteasePlaybackResponseParser.PlaybackResult.Failure -> Unit
            }
        }
    }
    ShareableListenTogetherStreamResolution(
        streamUrls = streamUrls.toList(),
        isPreviewOnly = previewOnly
    )
}

private suspend fun PlayerManager.resolveBiliListenTogetherShareableStreams(
    song: SongItem
): ShareableListenTogetherStreamResolution = withContext(Dispatchers.IO) {
    val resolvedSong = resolveBiliSong(song, biliClient)
        ?: return@withContext ShareableListenTogetherStreamResolution(emptyList(), false)
    if (resolvedSong.cid == 0L) {
        return@withContext ShareableListenTogetherStreamResolution(emptyList(), false)
    }
    val availableStreams = runCatching {
        biliRepo.getAudioWithDecision(
            bvid = resolvedSong.videoInfo.bvid,
            cid = resolvedSong.cid,
            preferredKeyOverride = effectiveBiliQuality()
        ).first
    }.getOrElse { emptyList() }
    val streamUrls = buildBiliListenTogetherStreamUrls(
        selectedStreams = selectBiliListenTogetherShareableStreams(
            availableStreams = availableStreams,
            preferredQualityKey = effectiveBiliQuality()
        )
    )
    ShareableListenTogetherStreamResolution(
        streamUrls = streamUrls,
        isPreviewOnly = false
    )
}

internal fun selectBiliListenTogetherShareableStreams(
    availableStreams: List<BiliAudioStreamInfo>,
    preferredQualityKey: String = "high"
): List<BiliAudioStreamInfo> {
    val streamsByQuality = availableStreams
        .filter { it.url.isNotBlank() }
        .groupBy(::inferBiliQualityKey)
        .mapValues { (_, streams) -> streams.sortedByDescending { it.bitrateKbps } }
    val selected = buildListenTogetherBiliQualityOrder(
        preferredQualityKey = preferredQualityKey,
        availableQualityKeys = streamsByQuality.keys
    ).mapNotNull { quality -> streamsByQuality[quality]?.firstOrNull() }
    return selected
        .distinctBy { it.url }
        .take(MAX_LISTEN_TOGETHER_BILI_STREAM_URL_CANDIDATES)
}

internal fun buildBiliListenTogetherStreamUrls(
    selectedStreams: List<BiliAudioStreamInfo>,
    maxCount: Int = MAX_LISTEN_TOGETHER_BILI_STREAM_URL_CANDIDATES
): List<String> {
    if (maxCount <= 0) return emptyList()
    return buildList {
        selectedStreams.forEach { stream ->
            val qualityKey = inferBiliQualityKey(stream)
            val streamUrl = stream.url.trim()
            if (
                streamUrl.startsWith("https://", ignoreCase = true) ||
                streamUrl.startsWith("http://", ignoreCase = true)
            ) {
                add(
                    decorateListenTogetherStreamUrl(
                        streamUrl = streamUrl,
                        source = PlaybackAudioSource.BILIBILI,
                        qualityKey = qualityKey
                    )
                )
            }
        }
    }.distinct().take(maxCount)
}

private suspend fun PlayerManager.resolveYouTubeListenTogetherShareableStreams(
    song: SongItem
): ShareableListenTogetherStreamResolution {
    val streamUrls = linkedSetOf<String>()
    val resolvedQualityKeys = linkedSetOf<String>()
    val preferredQualityKey = effectiveYouTubeQuality()
    for (requestedQuality in buildListenTogetherYouTubeQualityOrder(preferredQualityKey)) {
        if (streamUrls.size >= MAX_LISTEN_TOGETHER_YOUTUBE_STREAM_URL_CANDIDATES) break
        val result = getYouTubeMusicAudioUrl(
            song = song,
            suppressError = true,
            forceRefresh = false,
            youtubeRecoveryStrategy = YouTubePlaybackRecoveryStrategy(
                preferredQualityOverride = requestedQuality,
                requireDirect = false,
                preferM4a = false
            ),
            sideEffects = RefreshResolverSideEffects(RefreshSideEffectGate { false })
        ) as? SongUrlResult.Success ?: continue
        val streamUrl = result.url.takeIf(::isDirectStreamUrl) ?: continue
        val actualQualityKey = result.audioInfo?.qualityKey
            ?.trim()
            ?.lowercase()
            ?.ifBlank { requestedQuality }
            ?: requestedQuality
        if (!resolvedQualityKeys.add(actualQualityKey)) continue
        streamUrls += decorateListenTogetherStreamUrl(
            streamUrl = streamUrl,
            source = PlaybackAudioSource.YOUTUBE_MUSIC,
            qualityKey = actualQualityKey
        )
    }
    if (streamUrls.isEmpty()) {
        return ShareableListenTogetherStreamResolution(emptyList(), false)
    }
    return ShareableListenTogetherStreamResolution(
        streamUrls = streamUrls.toList(),
        isPreviewOnly = false
    )
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
    return shouldAttemptCachedPlaybackRepair(
        error = error,
        isOfflineCache = isOfflineCache,
        isYouTubeTrack = isYouTubeMusicTrack(song),
        isLocalSong = isLocalSong(song)
    )
}

internal fun shouldAttemptCachedPlaybackRepair(
    error: PlaybackException,
    isOfflineCache: Boolean,
    isYouTubeTrack: Boolean,
    isLocalSong: Boolean
): Boolean {
    if (isOfflineCache) return true
    if (isLocalSong) {
        // 迁移切根或源文件清理与 Media3 打开文件之间存在窄竞态。
        // 仅对明确的文件不存在重绑本地引用，不能把网络或解码错误当成迁移问题
        return error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
    }
    if (isYouTubeTrack) {
        return shouldAttemptYouTubePlaybackRecovery(error, isOfflineCache)
    }
    return isRecoverableRemotePlaybackCacheError(error)
}

internal fun shouldInvalidateCachedResourceForPlaybackRecovery(
    error: PlaybackException
): Boolean = isRecoverableRemotePlaybackCacheError(error)

internal fun shouldInvalidateCacheForPlaybackRecovery(
    error: PlaybackException,
    isOfflineCache: Boolean
): Boolean {
    return shouldInvalidateCachedResourceForPlaybackRecovery(error) ||
        (isOfflineCache && isRecoverableCachedMediaFormatError(error))
}

internal fun shouldInvalidateCacheAfterPlaybackFailure(
    shouldInvalidateCache: Boolean,
    isOfflineCache: Boolean
): Boolean = shouldInvalidateCache && !isOfflineCache

internal fun isRecoverableRemotePlaybackCacheError(error: PlaybackException): Boolean {
    return error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        (
            error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT &&
                !shouldTreatPlaybackFailureAsTrackEnd(error)
            )
}

internal fun shouldTreatPlaybackFailureAsTrackEnd(error: PlaybackException): Boolean {
    return error.stuckPlayerExceptionOrNull()?.stuckType ==
        StuckPlayerException.STUCK_PLAYING_NOT_ENDING
}

internal fun shouldAdvanceAfterStuckTrackEnd(
    error: PlaybackException,
    playbackRequested: Boolean
): Boolean = playbackRequested && shouldTreatPlaybackFailureAsTrackEnd(error)

private fun PlaybackException.stuckPlayerExceptionOrNull(): StuckPlayerException? {
    return generateSequence(cause) { it.cause }
        .filterIsInstance<StuckPlayerException>()
        .firstOrNull()
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
        requireDirect = shouldRequireDirectOnYouTubeRecovery(error, isOfflineCache),
        preferM4a = true,
        allowUnverifiedDirectFallback =
            error.errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
    )
}

internal fun youtubePlaybackRecoveryStrategyForSeek(): YouTubePlaybackRecoveryStrategy {
    return YouTubePlaybackRecoveryStrategy(
        preferredQualityOverride = YOUTUBE_STABLE_RECOVERY_QUALITY,
        requireDirect = false,
        preferM4a = true,
        allowUnverifiedDirectFallback = false
    )
}

/**
 * googlevideo 拒了直链时不能再强制直链, 否则恢复必然拿回同一条 403
 *
 * 机房和被风控的出口上直链常年不可用, 只有放开这个约束才能落到 HLS
 */
internal fun shouldRequireDirectOnYouTubeRecovery(
    error: PlaybackException,
    isOfflineCache: Boolean
): Boolean {
    if (isOfflineCache) return true
    return error.errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
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
        (
            error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT &&
                !shouldTreatPlaybackFailureAsTrackEnd(error)
            ) ||
        isRecoverableCachedMediaFormatError(error)
}

private fun isRecoverableCachedMediaFormatError(error: PlaybackException): Boolean {
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
        positionGeneration = playbackPositionGeneration,
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
    cacheKeyToInvalidateBeforeResolve: String? = null,
    allowLocalSongRecovery: Boolean = false
) {
    val song = _currentSongFlow.value ?: return
    if (isLocalSong(song) && !allowLocalSongRecovery) return
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
        positionGeneration = playbackPositionGeneration,
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
                reason = semantics.reason,
                shouldApplyMutation = { canApplyRefreshResult(semantics, song) }
            )
        }
        val result = resolveSongUrl(
            song = song,
            forceRefresh = isYouTubeMusicTrack(song),
            youtubeRecoveryStrategy = semantics.youtubeRecoveryStrategy,
            sideEffects = RefreshResolverSideEffects(refreshSideEffectGate(semantics, song)),
            playbackRequestTokenOverride = semantics.requestGeneration,
            shouldApplyCacheMutation = { canApplyRefreshResult(semantics, song) }
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
    positionGeneration: Long,
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
    positionGeneration = positionGeneration,
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
    val selectedAudioInfo = resolvePlaybackAudioInfoForListenTogetherStreamCandidate(
        candidate = selectedCandidate,
        resolvedAudioInfo = audioInfo,
        existingAudioInfo = _currentPlaybackAudioInfo.value
    )
    val selectedMimeType = selectedCandidate?.mimeType ?: mimeType
    val selectedExpectedContentLength =
        selectedCandidate?.expectedContentLength ?: expectedContentLength
    val selectedRepresentationIdentity =
        selectedCandidate?.representationIdentity ?: result.representationIdentity
    val cacheSynchronization = synchronizeCachedPlaybackDescriptor(
        cacheKey = cacheKey,
        audioInfo = selectedAudioInfo,
        expectedContentLength = selectedExpectedContentLength,
        representationIdentity = selectedRepresentationIdentity,
        shouldApplyMutation = { gate.runMutation {} }
    )
    if (!gate.runMutation {}) return false
    val mediaItem = buildMediaItem(
        song = song,
        url = selectedUrl,
        cacheKey = cacheKey,
        mimeType = selectedMimeType,
        allowCustomCacheKey = cacheSynchronization.allowsCustomCacheKey()
    )

    if (!gate.runMutation { _currentMediaUrl.value = selectedUrl }) return false
    if (!gate.runMutation { _currentPlaybackAudioInfo.value = selectedAudioInfo }) return false
    if (!gate.runMutation { currentMediaUrlResolvedAtMs = SystemClock.elapsedRealtime() }) return false
    if (!gate.runSuspendingMutation { persistState() }) return false

    var applied = false
    withContext(Dispatchers.Main) {
        val observedPositionBelongsToRequestedMedia =
            loadedMediaRequestToken == semantics.requestGeneration
        if (!gate.runMutation {
                updateAudioOffloadPreferences("refreshed_stream_source")
            }
        ) return@withContext
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
        val startPositionMs = resolveRefreshedMediaStartPosition(
            pendingSeekPositionMs = pendingSeekPositionOrNull(),
            requestedResumePositionMs = resumePositionMs,
            observedPlaybackPositionMs = _playbackPositionMs.value,
            requestedPositionGeneration = semantics.positionGeneration,
            currentPositionGeneration = playbackPositionGeneration,
            observedPositionBelongsToRequestedMedia = observedPositionBelongsToRequestedMedia
        )
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

private suspend fun PlayerManager.resolvePermittedLocalPlaybackWithRetry(
    song: SongItem,
    rawLocalReference: String?
): LocalPlaybackReferenceResolution {
    var resolution = AudioDownloadManager.resolvePermittedLocalPlayback(
        context = application,
        song = song,
        rawLocalReference = rawLocalReference
    )
    repeat(LOCAL_PLAYBACK_RESOLUTION_RETRY_COUNT) { retryIndex ->
        if (!shouldRetryLocalPlaybackResolution(song, resolution)) {
            return resolution
        }
        val retryNumber = retryIndex + 1
        val delayMs = (LOCAL_PLAYBACK_RESOLUTION_RETRY_DELAY_MS * retryNumber)
            .coerceAtMost(LOCAL_PLAYBACK_RESOLUTION_RETRY_MAX_DELAY_MS)
        NPLogger.d(
            "NERI-PlayerManager",
            "本地播放引用暂未稳定，等待重试: song=${song.name}, " +
                "retry=$retryNumber/$LOCAL_PLAYBACK_RESOLUTION_RETRY_COUNT, delayMs=$delayMs, " +
                "resolution=$resolution"
        )
        delay(delayMs)
        resolution = AudioDownloadManager.resolvePermittedLocalPlayback(
            context = application,
            song = song,
            rawLocalReference = rawLocalReference
        )
    }
    return resolution
}

private suspend fun PlayerManager.checkLocalCache(
    song: SongItem,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects()
): SongUrlResult? {
    val context = application
    val localResolution = resolveIndexedLocalPlaybackWithRetry(context, song)
    val localReference = when (localResolution) {
        is LocalPlaybackReferenceResolution.Playable -> localResolution.reference
        LocalPlaybackReferenceResolution.NotIndexed -> return null
        LocalPlaybackReferenceResolution.Missing -> {
            NPLogger.w(
                "NERI-PlayerManager",
                "checkLocalCache: indexed download is explicitly missing; allow remote fallback: " +
                    "song=${song.name}"
            )
            sideEffects.scanLocalFiles {
                GlobalDownloadManager.scanLocalFiles(context, forceRefresh = true)
            }
            return null
        }
        is LocalPlaybackReferenceResolution.TemporarilyUnavailable -> {
            NPLogger.w(
                "NERI-PlayerManager",
                "checkLocalCache: indexed download is temporarily unavailable; " +
                    "remote fallback is blocked: song=${song.name}, " +
                    "evidence=${localResolution.evidence}"
            )
            sideEffects.emitError {
                postPlayerEvent(
                    PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url))
                )
            }
            return SongUrlResult.Failure
        }
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

private suspend fun resolveIndexedLocalPlaybackWithRetry(
    context: android.content.Context,
    song: SongItem
): LocalPlaybackReferenceResolution {
    var resolution = AudioDownloadManager.resolveIndexedLocalPlaybackReference(context, song)
    repeat(LOCAL_PLAYBACK_RESOLUTION_RETRY_COUNT) { retryIndex ->
        if (!shouldRetryLocalPlaybackResolution(song, resolution)) {
            return resolution
        }
        val retryNumber = retryIndex + 1
        val delayMs = (LOCAL_PLAYBACK_RESOLUTION_RETRY_DELAY_MS * retryNumber)
            .coerceAtMost(LOCAL_PLAYBACK_RESOLUTION_RETRY_MAX_DELAY_MS)
        NPLogger.d(
            "NERI-PlayerManager",
            "索引本地播放引用暂未稳定，等待重试: song=${song.name}, " +
                "retry=$retryNumber/$LOCAL_PLAYBACK_RESOLUTION_RETRY_COUNT, delayMs=$delayMs, " +
                "resolution=$resolution"
        )
        delay(delayMs)
        resolution = AudioDownloadManager.resolveIndexedLocalPlaybackReference(context, song)
    }
    return resolution
}

internal fun shouldRetryLocalPlaybackResolution(
    song: SongItem,
    resolution: LocalPlaybackReferenceResolution
): Boolean {
    return when (resolution) {
        is LocalPlaybackReferenceResolution.TemporarilyUnavailable -> true
        LocalPlaybackReferenceResolution.Missing ->
            GlobalDownloadManager.hasDownloadedSongCached(song) ||
                AudioDownloadManager.peekCompletedAudioReference(song) != null ||
                AudioDownloadManager.isSongDownloadActive(song.stableKey())
        LocalPlaybackReferenceResolution.NotIndexed,
        is LocalPlaybackReferenceResolution.Playable -> false
    }
}

internal fun PlayerManager.inspectExoPlayerCache(
    cacheKey: String
): CachedResourceIntegrity {
    val mediaCache = cache ?: return CachedResourceIntegrity(false, false, 0L)
    return try {
        val cachedSpans = mediaCache.getCachedSpans(cacheKey)
        if (cachedSpans.isEmpty()) {
            return CachedResourceIntegrity(false, false, 0L)
        }

        val contentLength = ContentMetadata.getContentLength(
            mediaCache.getContentMetadata(cacheKey)
        )
        if (contentLength <= 0L) {
            NPLogger.d("NERI-PlayerManager", "缓存命中但缺少内容长度，视为未完成缓存: $cacheKey")
            return CachedResourceIntegrity(false, false, 0L)
        }

        val integrity = inspectCachedResourceSpans(cachedSpans, contentLength)
        when {
            integrity.requiresRepair -> NPLogger.w(
                "NERI-PlayerManager",
                "缓存 span 文件缺失或长度异常，标记为损坏: key=$cacheKey, " +
                    "covered=${integrity.coveredLength}/$contentLength"
            )

            integrity.isComplete -> NPLogger.d(
                "NERI-PlayerManager",
                "缓存完整可用: $cacheKey, length=$contentLength, spans=${cachedSpans.size}"
            )

            else -> NPLogger.d(
                "NERI-PlayerManager",
                "缓存未完整覆盖: $cacheKey, " +
                    "covered=${integrity.coveredLength}/$contentLength"
            )
        }
        integrity
    } catch (e: Exception) {
        NPLogger.w("NERI-PlayerManager", "检查缓存完整性失败: ${e.message}")
        CachedResourceIntegrity(false, true, 0L)
    }
}

internal fun PlayerManager.hasCompleteExoPlayerCache(cacheKey: String): Boolean {
    return inspectExoPlayerCache(cacheKey).isComplete
}

internal suspend fun PlayerManager.prepareExoPlayerCacheForPrefetch(
    cacheKey: String,
    shouldApplyMutation: () -> Boolean = { true }
): CachePrefetchReadiness {
    val currentCache = cache ?: return CachePrefetchReadiness.UNAVAILABLE

    if (loadPlaybackCacheKeySafety(cacheKey)) {
        if (cache !== currentCache) return CachePrefetchReadiness.UNAVAILABLE
        return if (
            invalidateCachedResourceForPlaybackRecovery(
                cacheKey = cacheKey,
                reason = "prefetch_unsafe_cache_key",
                shouldApplyMutation = shouldApplyMutation
            )
        ) {
            CachePrefetchReadiness.READY_FOR_PREFETCH
        } else {
            CachePrefetchReadiness.UNAVAILABLE
        }
    }

    val integrity = inspectExoPlayerCache(cacheKey)
    if (cache !== currentCache) return CachePrefetchReadiness.UNAVAILABLE
    if (integrity.isComplete) return CachePrefetchReadiness.COMPLETE
    if (!integrity.requiresRepair) return CachePrefetchReadiness.READY_FOR_PREFETCH

    return if (
        invalidateCachedResourceForPlaybackRecovery(
            cacheKey = cacheKey,
            reason = "prefetch_integrity_check",
            shouldApplyMutation = shouldApplyMutation
        )
    ) {
        CachePrefetchReadiness.READY_FOR_PREFETCH
    } else {
        CachePrefetchReadiness.UNAVAILABLE
    }
}

internal suspend fun PlayerManager.invalidateMismatchedCachedResource(
    cacheKey: String,
    expectedContentLength: Long?,
    shouldApplyMutation: () -> Boolean = { true }
) = withContext(Dispatchers.IO) {
    val expectedLength = expectedContentLength?.takeIf { it > 0L } ?: return@withContext
    val mediaCache = cache ?: return@withContext

    try {
        val cachedSpans = mediaCache.getCachedSpans(cacheKey)
        if (cachedSpans.isEmpty()) return@withContext

        val cachedContentLength = ContentMetadata.getContentLength(
            mediaCache.getContentMetadata(cacheKey)
        )
        if (!shouldReplaceCachedPreviewResource(cachedContentLength, expectedLength)) {
            return@withContext
        }

        NPLogger.w(
            "NERI-PlayerManager",
            "缓存疑似预览片段，移除旧缓存以便重新拉取完整资源: key=$cacheKey, cached=$cachedContentLength, expected=$expectedLength"
        )
        if (!shouldApplyMutation()) return@withContext
        mediaCache.removeResource(cacheKey)
    } catch (e: Exception) {
        NPLogger.w(
            "NERI-PlayerManager",
            "移除不匹配缓存失败: key=$cacheKey, error=${e.message}"
        )
    }
}

internal fun PlayerManager.currentPlaybackCacheKeyForRecovery(): String? {
    offlineCacheKeyFromUrl(_currentMediaUrl.value)?.let { return it }

    if (isPlayerInitialized()) {
        player.currentMediaItem?.localConfiguration?.customCacheKey
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }

    val song = _currentSongFlow.value ?: return null
    if (isLocalSong(song)) return null
    return currentPlaybackCandidate()?.cacheKeyOverride
        ?.takeIf { it.isNotBlank() }
        ?: computeCacheKey(song).takeIf { it.isNotBlank() }
}

internal suspend fun PlayerManager.invalidateCachedResourceForPlaybackRecovery(
    cacheKey: String,
    reason: String,
    shouldApplyMutation: () -> Boolean = { true }
): Boolean = withContext(Dispatchers.IO) {
    if (cacheKey.isBlank()) return@withContext false
    val mediaCache = cache ?: return@withContext false
    if (cache !== mediaCache || !shouldApplyMutation()) return@withContext false
    try {
        if (cache !== mediaCache || !shouldApplyMutation()) return@withContext false
        mediaCache.removeResource(cacheKey)
        if (cache !== mediaCache) return@withContext false
        if (mediaCache.getCachedSpans(cacheKey).isNotEmpty()) {
            markPlaybackCacheKeyUnsafe(mediaCache, cacheKey)
            NPLogger.w(
                "NERI-PlayerManager",
                "异常播放缓存未完全移除: key=$cacheKey, reason=$reason"
            )
            return@withContext false
        }
        if (cache !== mediaCache || !shouldApplyMutation()) return@withContext false
        if (!clearPlaybackCacheKeyUnsafe(mediaCache, cacheKey)) {
            NPLogger.w(
                "NERI-PlayerManager",
                "异常播放缓存标记未清除，跳过缓存复用: key=$cacheKey, reason=$reason"
            )
            return@withContext false
        }
        NPLogger.w(
            "NERI-PlayerManager",
            "已移除异常播放缓存: key=$cacheKey, reason=$reason"
        )
        true
    } catch (e: Exception) {
        if (cache === mediaCache) {
            markPlaybackCacheKeyUnsafe(mediaCache, cacheKey)
        }
        NPLogger.w(
            "NERI-PlayerManager",
            "移除异常播放缓存失败: key=$cacheKey, reason=$reason, error=${e.message}"
        )
        false
    }
}

private suspend fun PlayerManager.invalidateCachedResourceBeforeResolve(
    cacheKey: String,
    reason: String,
    shouldApplyMutation: () -> Boolean
) = invalidateCachedResourceForPlaybackRecovery(cacheKey, reason, shouldApplyMutation)

private suspend fun PlayerManager.getNeteaseSongUrl(
    song: SongItem,
    suppressError: Boolean = false,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects(),
    allowLocalFallback: Boolean = true,
    allowPreviewFallback: Boolean = true,
    allowAutoBiliFallback: Boolean = true
): SongUrlResult = withContext(Dispatchers.IO) {
    try {
        val effectiveQuality = effectiveNeteaseQuality()
        val qualityCandidates = buildNeteaseQualityCandidates(effectiveQuality)
        var previewFallback: SongUrlResult.Success? = null
        var lastFailureReason: NeteasePlaybackResponseParser.FailureReason? = null
        var requiresLogin = false

        for ((index, quality) in qualityCandidates.withIndex()) {
            val resp = neteaseClient.getSongDownloadUrl(
                song.id,
                level = quality
            )
            NPLogger.d("NERI-PlayerManager", "id=${song.id}, level=$quality, resp=$resp")

            when (val parsed = NeteasePlaybackResponseParser.parsePlayback(resp, song.durationMs)) {
                is NeteasePlaybackResponseParser.PlaybackResult.RequiresLogin -> {
                    requiresLogin = true
                    if (shouldRetryNeteaseWithLowerQualityAfterLogin(
                            qualityIndex = index,
                            lastQualityIndex = qualityCandidates.lastIndex
                        )
                    ) {
                        NPLogger.w(
                            "NERI-PlayerManager",
                            "当前音质需要登录，继续尝试更低音质: id=${song.id}, level=$quality"
                        )
                        continue
                    }
                    break
                }

                is NeteasePlaybackResponseParser.PlaybackResult.Success -> {
                    val success = buildNeteaseSuccessResult(
                        parsed = parsed,
                        resolvedQualityKey = quality,
                        fallbackDurationMs = song.durationMs,
                        getLocalizedString = { getLocalizedString(it) }
                    ).let { result ->
                        if (parsed.notice == NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                            result.copy(
                                cacheKeyOverride = buildNeteasePreviewCacheKey(
                                    songId = song.id,
                                    preferredQuality = quality
                                )
                            )
                        } else {
                            result
                        }
                    }
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
            if (allowLocalFallback) {
                tryResolveNeteaseMatchedLocalSource(song)?.let {
                    return@withContext it
                }
            }
            if (allowAutoBiliFallback) {
                tryResolveNeteaseAutoBiliSource(song, sideEffects)?.let {
                    return@withContext it
                }
            }
        }

        if (allowPreviewFallback) {
            previewFallback?.let { return@withContext it }
        }

        if (requiresLogin) {
            return@withContext SongUrlResult.RequiresLogin
        }

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
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects(),
    playbackRequestTokenOverride: Long? = null
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

        if (!isListenTogetherActive() && _currentSongFlow.value?.sameIdentityAs(song) == true) {
            val requestToken = playbackRequestTokenOverride ?: playbackRequestToken
            BiliSponsorBlockPlaybackController.onBiliTrackResolved(
                song = song,
                target = BiliSponsorBlockTarget(
                    bvid = resolved.videoInfo.bvid,
                    cid = resolved.cid,
                    durationMs = resolved.pageInfo
                        ?.durationSec
                        ?.toLong()
                        ?.times(1_000L)
                        ?.takeIf { it > 0L }
                        ?: song.durationMs
                ),
                requestToken = requestToken,
                scope = ioScope
            )
            BiliVideoSkipPlaybackController.onBiliTrackResolved(
                song = song,
                target = BiliVideoSkipTarget(
                    bvid = resolved.videoInfo.bvid,
                    cid = resolved.cid
                ),
                requestToken = requestToken
            )
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
                expectedContentLength = null,
                audioInfo = buildBiliPlaybackAudioInfo(audioStream, availableStreams) {
                    getLocalizedString(it)
                },
                representationIdentity = buildBiliRepresentationIdentity(audioStream)
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
        // 首播保留用户选择; 出错恢复时才切到更稳的 m4a 直链
        val preferM4a = youtubeRecoveryStrategy?.preferM4a ?: false
        val resolvedPlayableAudio = youtubeMusicPlaybackRepository.getBestPlayableAudio(
            videoId = videoId,
            preferredQualityOverride = preferredQuality,
            forceRefresh = forceRefresh,
            requireDirect = requireDirect,
            preferM4a = preferM4a,
            shareInFlight = youtubeRecoveryStrategy == null,
            allowUnverifiedDirectFallback =
                youtubeRecoveryStrategy?.allowUnverifiedDirectFallback ?: true
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
                representationIdentity = buildYouTubeRepresentationIdentity(resolvedPlayableAudio),
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
