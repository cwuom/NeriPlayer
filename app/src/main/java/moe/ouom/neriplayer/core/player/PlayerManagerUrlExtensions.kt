@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player

import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlayerEvent
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.core.player.model.mergeLocalPlaybackAudioInfoWithRemoteQuality
import moe.ouom.neriplayer.core.player.policy.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.resolvePlaybackStartPlan
import moe.ouom.neriplayer.core.player.url.buildBiliPlaybackAudioInfo
import moe.ouom.neriplayer.core.player.url.buildLocalPlaybackAudioInfo
import moe.ouom.neriplayer.core.player.url.buildNeteaseQualityCandidates
import moe.ouom.neriplayer.core.player.url.buildNeteaseSuccessResult
import moe.ouom.neriplayer.core.player.url.buildYouTubePlaybackAudioInfo
import moe.ouom.neriplayer.core.player.url.shouldReplaceCachedPreviewResource
import moe.ouom.neriplayer.core.player.url.shouldRetryNeteaseWithLowerQuality
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger

internal suspend fun PlayerManager.resolveSongUrl(
    song: SongItem,
    forceRefresh: Boolean = false
): SongUrlResult {
    NPLogger.d(
        "NERI-PlayerManager",
        "resolveSongUrl: song=${song.name}, source=${song.album}, forceRefresh=$forceRefresh, streamUrl=${song.streamUrl}, currentUrl=${_currentMediaUrl.value}, stack=[${debugStackHint()}]"
    )
    if (shouldWaitForListenTogetherAuthoritativeStream(song)) {
        return SongUrlResult.WaitingForAuthoritativeStream
    }
    if (isDirectStreamUrl(song.streamUrl)) {
        return SongUrlResult.Success(song.streamUrl.orEmpty())
    }
    if (isLocalSong(song)) {
        val localMediaUri = localMediaSource(song)
        if (localMediaUri != null && isReadableLocalMediaUri(localMediaUri)) {
            return SongUrlResult.Success(
                url = toPlayableLocalUrl(localMediaUri) ?: localMediaUri,
                audioInfo = buildLocalPlaybackAudioInfo(song, application)
            )
        }
        postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
        return SongUrlResult.Failure
    }

    val localResult = checkLocalCache(song)
    if (localResult != null) {
        NPLogger.d(
            "NERI-PlayerManager",
            "resolveSongUrl: hit local playback cache for song=${song.name}"
        )
        return localResult
    }
    val cacheKey = computeCacheKey(song)
    val hasCachedData = checkExoPlayerCache(cacheKey)
    val result = when {
        isYouTubeMusicTrack(song) -> getYouTubeMusicAudioUrl(
            song = song,
            suppressError = hasCachedData,
            forceRefresh = forceRefresh
        )
        isBiliTrack(song) -> getBiliAudioUrl(song, suppressError = hasCachedData)
        else -> getNeteaseSongUrl(song, suppressError = hasCachedData)
    }

    return if (result is SongUrlResult.Failure && hasCachedData && !isYouTubeMusicTrack(song)) {
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

internal fun PlayerManager.shouldAttemptUrlRefresh(
    error: PlaybackException,
    song: SongItem?,
    isOfflineCache: Boolean
): Boolean {
    if (song == null || isOfflineCache) return false
    if (isLocalSong(song)) return false
    return error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
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
            player.play()
        } else {
            player.pause()
        }
    }
}

internal fun PlayerManager.refreshCurrentSongUrlImpl(
    resumePositionMs: Long,
    allowFallback: Boolean,
    reason: String,
    bypassCooldown: Boolean = false,
    fallbackSeekPositionMs: Long? = null,
    resumePlaybackAfterRefresh: Boolean = true,
    resumedPlaybackCommandSource: PlaybackCommandSource? = null
) {
    val song = _currentSongFlow.value ?: return
    if (isLocalSong(song)) return
    NPLogger.d(
        "NERI-PlayerManager",
        "refreshCurrentSongUrl: song=${song.name}, resumePositionMs=$resumePositionMs, allowFallback=$allowFallback, reason=$reason, bypassCooldown=$bypassCooldown, resumePlaybackAfterRefresh=$resumePlaybackAfterRefresh, commandSource=$resumedPlaybackCommandSource, stack=[${debugStackHint()}]"
    )
    if (urlRefreshInProgress) {
        NPLogger.w(
            "NERI-PlayerManager",
            "refreshCurrentSongUrl skipped: another refresh is already running for song=${song.name}"
        )
        if (allowFallback) {
            resumePlaybackFallback(
                seekPositionMs = fallbackSeekPositionMs,
                resumePlaybackAfterRefresh = resumePlaybackAfterRefresh
            )
        }
        return
    }

    val cacheKey = computeCacheKey(song)
    val now = SystemClock.elapsedRealtime()
    if (!bypassCooldown &&
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

    urlRefreshInProgress = true
    lastUrlRefreshKey = cacheKey
    lastUrlRefreshAtMs = now

    ioScope.launch {
        try {
            NPLogger.d("NERI-PlayerManager", "Refreshing stream url ($reason): $cacheKey")
            val result = resolveSongUrl(
                song = song,
                forceRefresh = isYouTubeMusicTrack(song)
            )
            if (result is SongUrlResult.Success &&
                _currentSongFlow.value?.sameIdentityAs(song) == true
            ) {
                maybeUpdateSongDuration(song, result.durationMs ?: 0L)
                withContext(Dispatchers.Main) {
                    applyResolvedMediaItem(
                        _currentSongFlow.value ?: song,
                        result.url,
                        result.mimeType,
                        result.expectedContentLength,
                        result.audioInfo,
                        resumePositionMs,
                        resumePlaybackAfterRefresh
                    )
                    consecutivePlayFailures = 0
                    if (
                        resumePlaybackAfterRefresh &&
                        resumedPlaybackCommandSource == PlaybackCommandSource.LOCAL
                    ) {
                        emitPlaybackCommand(
                            type = "PLAY",
                            source = resumedPlaybackCommandSource,
                            positionMs = resumePositionMs.coerceAtLeast(0L),
                            currentIndex = currentIndex
                        )
                    }
                }
            } else if (allowFallback) {
                resumePlaybackFallback(
                    seekPositionMs = fallbackSeekPositionMs,
                    resumePlaybackAfterRefresh = resumePlaybackAfterRefresh
                )
            } else {
                clearPendingSeekPosition()
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.player_playback_network_error)))
                withContext(Dispatchers.Main) {
                    pause(commandSource = PlaybackCommandSource.REMOTE_SYNC)
                }
            }
        } finally {
            urlRefreshInProgress = false
        }
    }
}

private suspend fun PlayerManager.applyResolvedMediaItem(
    song: SongItem,
    url: String,
    mimeType: String?,
    expectedContentLength: Long?,
    audioInfo: PlaybackAudioInfo?,
    resumePositionMs: Long,
    resumePlaybackAfterRefresh: Boolean
) {
    if (_currentSongFlow.value?.sameIdentityAs(song) != true) return

    val cacheKey = computeCacheKey(song)
    invalidateMismatchedCachedResource(
        cacheKey = cacheKey,
        expectedContentLength = expectedContentLength
    )
    val mediaItem = buildMediaItem(song, url, cacheKey, mimeType)

    _currentMediaUrl.value = url
    _currentPlaybackAudioInfo.value = audioInfo
    currentMediaUrlResolvedAtMs = SystemClock.elapsedRealtime()
    persistState()

    withContext(Dispatchers.Main) {
        preparePlayerForManagedStart(
            resolvePlaybackStartPlan(shouldFadeIn = false, fadeDurationMs = 0L)
        )
        resetTrackEndDeduplicationState()
        player.setMediaItem(mediaItem)
        syncExoRepeatMode()
        if (resumePositionMs > 0) {
            player.seekTo(resumePositionMs)
            _playbackPositionMs.value = resumePositionMs
        }
        player.prepare()
        player.playWhenReady = resumePlaybackAfterRefresh
        if (resumePlaybackAfterRefresh) {
            player.play()
        } else {
            player.pause()
        }
    }
}

private fun PlayerManager.checkLocalCache(song: SongItem): SongUrlResult? {
    val context = application
    val localReference = AudioDownloadManager.getLocalPlaybackUri(context, song) ?: return null
    val durationMs = if (song.durationMs <= 0L) {
        try {
            val retriever = android.media.MediaMetadataRetriever()
            val localUri = localReference.toUri()
            when (localUri.scheme?.lowercase()) {
                "content", "android.resource" -> retriever.setDataSource(context, localUri)
                "file" -> retriever.setDataSource(localUri.path)
                null, "" -> retriever.setDataSource(localReference)
                else -> retriever.setDataSource(context, localUri)
            }
            val d = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            retriever.release()
            d
        } catch (_: Exception) {
            null
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

private fun PlayerManager.checkExoPlayerCache(cacheKey: String): Boolean {
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
    expectedContentLength: Long?
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
        cache.removeResource(cacheKey)
    } catch (e: Exception) {
        NPLogger.w(
            "NERI-PlayerManager",
            "移除不匹配缓存失败: key=$cacheKey, error=${e.message}"
        )
    }
}

private suspend fun PlayerManager.getNeteaseSongUrl(
    song: SongItem,
    suppressError: Boolean = false
): SongUrlResult = withContext(Dispatchers.IO) {
    try {
        val qualityCandidates = buildNeteaseQualityCandidates(preferredQuality)
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
                        if (quality != preferredQuality) {
                            NPLogger.w(
                                "NERI-PlayerManager",
                                "当前音质不可用，已自动降级: id=${song.id}, preferred=$preferredQuality, resolved=$quality"
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
                    return@withContext success
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

        previewFallback?.let { return@withContext it }

        if (!suppressError) {
            val messageRes = when (lastFailureReason) {
                NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION ->
                    R.string.player_netease_no_permission_switch_platform
                NeteasePlaybackResponseParser.FailureReason.NO_PLAY_URL,
                NeteasePlaybackResponseParser.FailureReason.UNKNOWN,
                null -> R.string.error_no_play_url
            }
            postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(messageRes)))
        }
        SongUrlResult.Failure
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        NPLogger.e("NERI-PlayerManager", "Failed to get url", e)
        if (!suppressError) {
            postPlayerEvent(
                PlayerEvent.ShowError(
                    getLocalizedString(
                        R.string.player_playback_url_error_detail,
                        e.message.orEmpty()
                    )
                )
            )
        }
        SongUrlResult.Failure
    }
}

private suspend fun PlayerManager.getBiliAudioUrl(
    song: SongItem,
    suppressError: Boolean = false
): SongUrlResult = withContext(Dispatchers.IO) {
    try {
        val resolved = resolveBiliSong(song, biliClient)
        if (resolved == null || resolved.cid == 0L) {
            if (!suppressError) {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(R.string.player_playback_video_info_unavailable)
                    )
                )
            }
            return@withContext SongUrlResult.Failure
        }

        val (availableStreams, audioStream) = biliRepo.getAudioWithDecision(
            resolved.videoInfo.bvid,
            resolved.cid
        )

        if (audioStream?.url != null) {
            NPLogger.d("NERI-PlayerManager-BiliAudioUrl", audioStream.url)
            SongUrlResult.Success(
                url = audioStream.url,
                mimeType = audioStream.mimeType,
                audioInfo = buildBiliPlaybackAudioInfo(audioStream, availableStreams) {
                    getLocalizedString(it)
                }
            )
        } else {
            if (!suppressError) {
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
            }
            SongUrlResult.Failure
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        NPLogger.e("NERI-PlayerManager", "Failed to get Bili play url", e)
        if (!suppressError) {
            postPlayerEvent(
                PlayerEvent.ShowError(
                    getLocalizedString(
                        R.string.player_playback_url_error_detail,
                        e.message.orEmpty()
                    )
                )
            )
        }
        SongUrlResult.Failure
    }
}

private suspend fun PlayerManager.getYouTubeMusicAudioUrl(
    song: SongItem,
    suppressError: Boolean = false,
    forceRefresh: Boolean = false
): SongUrlResult = withContext(Dispatchers.IO) {
    val videoId = extractYouTubeMusicVideoId(song.mediaUri)
    if (videoId.isNullOrBlank()) {
        if (!suppressError) {
            postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
        }
        return@withContext SongUrlResult.Failure
    }

    val resolveStartedAtMs = System.currentTimeMillis()
    try {
        // 播放时优先保留更高码率的 Opus，避免被 m4a 偏好压到 140
        val resolvedPlayableAudio = youtubeMusicPlaybackRepository.getBestPlayableAudio(
            videoId = videoId,
            preferredQualityOverride = youtubePreferredQuality,
            forceRefresh = forceRefresh,
            preferM4a = false
        )?.takeIf { it.url.isNotBlank() }
        if (resolvedPlayableAudio != null) {
            maybeUpdateSongDuration(song, resolvedPlayableAudio.durationMs)
            NPLogger.d(
                "NERI-PlayerManager",
                "Resolved YouTube Music stream: videoId=$videoId, type=${resolvedPlayableAudio.streamType}, mime=${resolvedPlayableAudio.mimeType}, contentLength=${resolvedPlayableAudio.contentLength}, elapsedMs=${System.currentTimeMillis() - resolveStartedAtMs}"
            )
            SongUrlResult.Success(
                url = resolvedPlayableAudio.url,
                durationMs = resolvedPlayableAudio.durationMs.takeIf { it > 0L },
                mimeType = resolvedPlayableAudio.mimeType,
                audioInfo = buildYouTubePlaybackAudioInfo(resolvedPlayableAudio) {
                    getLocalizedString(it)
                }
            )
        } else {
            NPLogger.w(
                "NERI-PlayerManager",
                "Resolve YouTube Music stream returned empty: videoId=$videoId, elapsedMs=${System.currentTimeMillis() - resolveStartedAtMs}"
            )
            if (!suppressError) {
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
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
            postPlayerEvent(
                PlayerEvent.ShowError(
                    getLocalizedString(
                        R.string.player_playback_url_error_detail,
                        e.message.orEmpty()
                    )
                )
            )
        }
        SongUrlResult.Failure
    }
}
