@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player

import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.bili.buildBiliPartSong
import moe.ouom.neriplayer.core.player.debug.playbackStateName
import moe.ouom.neriplayer.core.player.model.PlayerEvent
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.core.player.policy.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.PlaybackStartPlan
import moe.ouom.neriplayer.core.player.policy.resolveManagedPlaybackStartPlan
import moe.ouom.neriplayer.core.player.policy.resolveManualResumePlaybackDecision
import moe.ouom.neriplayer.core.player.policy.resolveYouTubeWarmupTargets
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.SearchManager
import kotlin.random.Random

internal fun PlayerManager.cancelVolumeFadeImpl(resetToFull: Boolean = false) {
    val hadActiveFade = volumeFadeJob?.isActive == true
    if (hadActiveFade || resetToFull) {
        NPLogger.d(
            "NERI-PlayerManager",
            "cancelVolumeFade: hadActiveFade=$hadActiveFade, resetToFull=$resetToFull, currentSong=${_currentSongFlow.value?.name}"
        )
    }
    volumeFadeJob?.cancel()
    volumeFadeJob = null
    if (resetToFull && isPlayerInitialized()) {
        runPlayerActionOnMainThread {
            runCatching { player.volume = 1f }
        }
    }
}

internal fun PlayerManager.cancelPendingPauseRequestImpl(resetVolumeToFull: Boolean = false) {
    val hadPendingPause = pendingPauseJob?.isActive == true
    if (hadPendingPause || resetVolumeToFull) {
        NPLogger.d(
            "NERI-PlayerManager",
            "cancelPendingPauseRequest: hadPendingPause=$hadPendingPause, resetVolumeToFull=$resetVolumeToFull, currentSong=${_currentSongFlow.value?.name}"
        )
    }
    pendingPauseJob?.cancel()
    pendingPauseJob = null
    if (resetVolumeToFull && hadPendingPause && isPlayerInitialized()) {
        runPlayerActionOnMainThread {
            if (isPlayerInitialized()) {
                player.volume = 1f
            }
        }
    }
}

internal fun PlayerManager.preparePlayerForManagedStart(plan: PlaybackStartPlan) {
    if (!isPlayerInitialized()) return
    cancelVolumeFade()
    NPLogger.d(
        "NERI-PlayerManager",
        "preparePlayerForManagedStart: useFadeIn=${plan.useFadeIn}, fadeDurationMs=${plan.fadeDurationMs}, initialVolume=${plan.initialVolume}, currentSong=${_currentSongFlow.value?.name}"
    )
    player.playWhenReady = false
    player.volume = plan.initialVolume
}

internal suspend fun PlayerManager.fadeOutCurrentPlaybackIfNeeded(
    enabled: Boolean,
    fadeOutDurationMs: Long = playbackCrossfadeOutDurationMs
) {
    if (!enabled || !isPlayerInitialized()) {
        return
    }

    val shouldFade = _isPlayingFlow.value
    if (!shouldFade) {
        return
    }

    val durationMs = fadeOutDurationMs.coerceAtLeast(0L)
    if (durationMs <= 0L) {
        return
    }

    cancelVolumeFade()
    val startVolume = withContext(Dispatchers.Main) { player.volume.coerceIn(0f, 1f) }
    if (startVolume <= 0f) {
        return
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "fadeOutCurrentPlaybackIfNeeded: durationMs=$durationMs, startVolume=$startVolume, currentSong=${_currentSongFlow.value?.name}"
    )

    val steps = fadeStepsFor(durationMs)
    if (steps <= 0) return
    val stepDelay = (durationMs / steps).coerceAtLeast(1L)
    repeat(steps) { step ->
        val fraction = (step + 1).toFloat() / steps
        withContext(Dispatchers.Main) {
            if (!isPlayerInitialized()) {
                return@withContext
            }
            player.volume = (startVolume * (1f - fraction)).coerceAtLeast(0f)
        }
        delay(stepDelay)
    }

    withContext(Dispatchers.Main) {
        if (isPlayerInitialized()) {
            player.volume = 0f
        }
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "fadeOutCurrentPlaybackIfNeeded completed: durationMs=$durationMs, currentSong=${_currentSongFlow.value?.name}"
    )
}

internal fun PlayerManager.startPlayerPlaybackWithFade(plan: PlaybackStartPlan) {
    cancelVolumeFade()
    NPLogger.d(
        "NERI-PlayerManager",
        "startPlayerPlaybackWithFade: useFadeIn=${plan.useFadeIn}, fadeDurationMs=${plan.fadeDurationMs}, initialVolume=${plan.initialVolume}, currentSong=${_currentSongFlow.value?.name}"
    )
    runPlayerActionOnMainThread {
        if (!isPlayerInitialized()) return@runPlayerActionOnMainThread
        player.volume = plan.initialVolume
        player.playWhenReady = true
        player.play()
    }
    if (!plan.useFadeIn) {
        return
    }

    val steps = fadeStepsFor(plan.fadeDurationMs)
    if (steps <= 0) return
    val stepDelay = (plan.fadeDurationMs / steps).coerceAtLeast(1L)
    volumeFadeJob = mainScope.launch {
        repeat(steps) { step ->
            delay(stepDelay)
            if (!isPlayerInitialized()) return@launch
            player.volume = ((step + 1).toFloat() / steps).coerceAtMost(1f)
        }
        if (isPlayerInitialized()) {
            player.volume = 1f
        }
        volumeFadeJob = null
    }
}

internal fun PlayerManager.resolveCurrentPlaybackStartPlan(
    useTrackTransitionFade: Boolean = false,
    forceStartupProtectionFade: Boolean = false
): PlaybackStartPlan {
    return resolveManagedPlaybackStartPlan(
        playbackFadeInEnabled = playbackFadeInEnabled,
        playbackFadeInDurationMs = playbackFadeInDurationMs,
        playbackCrossfadeInDurationMs = playbackCrossfadeInDurationMs,
        useTrackTransitionFade = useTrackTransitionFade,
        forceStartupProtectionFade = forceStartupProtectionFade
    )
}

internal fun PlayerManager.handleTrackEnded() {
    clearPendingSeekPosition()
    _playbackPositionMs.value = 0L
    val isLastInPlaylist = if (player.shuffleModeEnabled) {
        shuffleFuture.isEmpty() && shuffleBag.isEmpty()
    } else {
        currentIndex >= currentPlaylist.lastIndex
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "handleTrackEnded: currentIndex=$currentIndex, queueSize=${currentPlaylist.size}, repeatMode=$repeatModeSetting, shuffle=${player.shuffleModeEnabled}, isLastInPlaylist=$isLastInPlaylist"
    )

    if (sleepTimerManager.shouldStopOnTrackEnd(isLastInPlaylist)) {
        pause()
        sleepTimerManager.cancel()
        return
    }

    when (repeatModeSetting) {
        Player.REPEAT_MODE_ONE -> {
            markAutoTrackAdvance()
            playAtIndex(currentIndex)
        }
        Player.REPEAT_MODE_ALL -> {
            markAutoTrackAdvance()
            next(force = true)
        }
        else -> {
            if (player.shuffleModeEnabled) {
                if (shuffleFuture.isNotEmpty() || shuffleBag.isNotEmpty()) {
                    markAutoTrackAdvance()
                    next(force = false)
                } else {
                    stopPlaybackPreservingQueue()
                }
            } else {
                if (currentIndex < currentPlaylist.lastIndex) {
                    markAutoTrackAdvance()
                    next(force = false)
                } else {
                    stopPlaybackPreservingQueue()
                }
            }
        }
    }
}

internal fun PlayerManager.playPlaylistImpl(
    songs: List<SongItem>,
    startIndex: Int,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
) {
    ensureInitialized()
    check(initialized) { "Call PlayerManager.initialize(application) first." }
    if (songs.isEmpty()) {
        NPLogger.w("NERI-Player", "playPlaylist called with EMPTY list")
        return
    }
    val targetSong = songs.getOrNull(startIndex.coerceIn(0, songs.lastIndex)) ?: songs.first()
    if (shouldBlockLocalRoomControl(commandSource) ||
        shouldBlockLocalSongSwitch(targetSong, commandSource)
    ) {
        return
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "playPlaylist: size=${songs.size}, requestedStart=$startIndex, resolvedStart=${startIndex.coerceIn(0, songs.lastIndex)}, source=$commandSource, target=${targetSong.name}, stack=[${debugStackHint()}]"
    )
    suppressAutoResumeForCurrentSession = false
    consecutivePlayFailures = 0
    currentPlaylist = songs
    _currentQueueFlow.value = currentPlaylist
    currentIndex = startIndex.coerceIn(0, songs.lastIndex)

    shuffleHistory.clear()
    shuffleFuture.clear()
    if (player.shuffleModeEnabled) {
        rebuildShuffleBag(excludeIndex = currentIndex)
    } else {
        shuffleBag.clear()
    }

    playAtIndex(currentIndex, commandSource = commandSource)
    emitPlaybackCommand(
        type = "PLAY_PLAYLIST",
        source = commandSource,
        queue = currentPlaylist,
        currentIndex = currentIndex
    )
    ioScope.launch {
        persistState()
    }
}

internal fun PlayerManager.rebuildShuffleBag(excludeIndex: Int? = null) {
    shuffleBag = currentPlaylist.indices.toMutableList()
    if (excludeIndex != null) shuffleBag.remove(excludeIndex)
    shuffleBag.shuffle()
    NPLogger.d(
        "NERI-PlayerManager",
        "rebuildShuffleBag: queueSize=${currentPlaylist.size}, excludeIndex=$excludeIndex, bagSize=${shuffleBag.size}, historySize=${shuffleHistory.size}, futureSize=${shuffleFuture.size}"
    )
}

internal fun PlayerManager.playAtIndex(
    index: Int,
    resumePositionMs: Long = 0L,
    useTrackTransitionFade: Boolean = false,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
    forceStartupProtectionFade: Boolean = false
) {
    if (currentPlaylist.isEmpty() || index !in currentPlaylist.indices) {
        NPLogger.w("NERI-Player", "playAtIndex called with invalid index: $index")
        return
    }

    if (consecutivePlayFailures >= MAX_CONSECUTIVE_FAILURES) {
        NPLogger.e(
            "NERI-PlayerManager",
            "Too many consecutive playback failures: $consecutivePlayFailures"
        )
        mainScope.launch {
            Toast.makeText(
                application,
                getLocalizedString(R.string.toast_playback_stopped),
                Toast.LENGTH_SHORT
            ).show()
        }
        stopPlaybackPreservingQueue(clearMediaUrl = true)
        return
    }

    val song = currentPlaylist[index]
    NPLogger.d(
        "NERI-PlayerManager",
        "playAtIndex: index=$index, song=${song.name}, resumePositionMs=$resumePositionMs, transitionFade=$useTrackTransitionFade, source=$commandSource, forceStartupProtectionFade=$forceStartupProtectionFade, nextToken=${playbackRequestToken + 1}, stack=[${debugStackHint()}]"
    )
    cancelPendingPauseRequest()
    _currentSongFlow.value = song
    _currentMediaUrl.value = null
    _currentPlaybackAudioInfo.value = null
    currentMediaUrlResolvedAtMs = 0L
    val shouldAwaitAuthoritativeStream =
        commandSource == PlaybackCommandSource.REMOTE_SYNC &&
            shouldWaitForListenTogetherAuthoritativeStream(song)
    if (shouldAwaitAuthoritativeStream) {
        stopCurrentPlaybackForListenTogetherAwaitingStream()
    }
    resumePlaybackRequested = true
    restoredShouldResumePlayback = false
    restoredResumePositionMs = 0L
    ioScope.launch {
        persistState(
            positionMs = resumePositionMs.coerceAtLeast(0L),
            shouldResumePlayback = true
        )
    }

    if (player.shuffleModeEnabled) {
        shuffleBag.remove(index)
    }

    playJob?.cancel()
    playbackRequestToken += 1
    val requestToken = playbackRequestToken
    clearPendingSeekPosition()
    _playbackPositionMs.value = 0L
    maybeWarmCurrentAndUpcomingYouTubeMusic(index)
    playJob = ioScope.launch {
        val result = resolveSongUrl(song)
        if (requestToken != playbackRequestToken || !isActive) {
            NPLogger.d(
                "NERI-PlayerManager",
                "播放请求已过期，跳过本次 URL 解析结果: song=${song.name}, requestToken=$requestToken, currentToken=$playbackRequestToken, active=$isActive"
            )
            return@launch
        }

        when (result) {
            is SongUrlResult.Success -> {
                consecutivePlayFailures = 0

                result.noticeMessage?.let { message ->
                    postPlayerEvent(PlayerEvent.ShowError(message))
                }
                maybeUpdateSongDuration(song, result.durationMs ?: 0L)
                val cacheKey = computeCacheKey(song)
                NPLogger.d(
                    "NERI-PlayerManager",
                    "Using custom cache key: $cacheKey for song: ${song.name}"
                )
                invalidateMismatchedCachedResource(
                    cacheKey = cacheKey,
                    expectedContentLength = result.expectedContentLength
                )

                val mediaItem = buildMediaItem(
                    _currentSongFlow.value ?: song,
                    result.url,
                    cacheKey,
                    result.mimeType
                )

                _currentMediaUrl.value = result.url
                _currentPlaybackAudioInfo.value = result.audioInfo
                currentMediaUrlResolvedAtMs = SystemClock.elapsedRealtime()
                persistState(
                    positionMs = resumePositionMs.coerceAtLeast(0L),
                    shouldResumePlayback = true
                )
                if (requestToken != playbackRequestToken || !isActive) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "播放请求已过期，跳过媒体项装载: song=${song.name}, requestToken=$requestToken, currentToken=$playbackRequestToken, active=$isActive"
                    )
                    return@launch
                }

                fadeOutCurrentPlaybackIfNeeded(
                    enabled = useTrackTransitionFade,
                    fadeOutDurationMs = playbackCrossfadeOutDurationMs
                )
                if (requestToken != playbackRequestToken || !isActive) {
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    if (requestToken != playbackRequestToken) {
                        return@withContext
                    }
                    val startPlan = resolveCurrentPlaybackStartPlan(
                        useTrackTransitionFade = useTrackTransitionFade,
                        forceStartupProtectionFade = forceStartupProtectionFade &&
                            resumePositionMs > 0L
                    )
                    preparePlayerForManagedStart(startPlan)
                    resetTrackEndDeduplicationState()
                    player.setMediaItem(mediaItem)
                    syncExoRepeatMode()
                    if (resumePositionMs > 0L) {
                        player.seekTo(resumePositionMs)
                        _playbackPositionMs.value = resumePositionMs
                    }
                    player.prepare()
                    startPlayerPlaybackWithFade(startPlan)
                }
                maybeAutoMatchBiliMetadata(song, requestToken)
            }
            SongUrlResult.WaitingForAuthoritativeStream -> {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "Waiting for authoritative listen-together stream: song=${song.name}, stableKey=${song.listenTogetherStableKeyOrNull()}"
                )
                resumePlaybackRequested = false
                ioScope.launch {
                    persistState(
                        positionMs = resumePositionMs.coerceAtLeast(0L),
                        shouldResumePlayback = false
                    )
                }
            }
            is SongUrlResult.RequiresLogin -> {
                NPLogger.w(
                    "NERI-PlayerManager",
                    "Requires login to play: id=${song.id}, source=${song.album}"
                )
                postPlayerEvent(
                    PlayerEvent.ShowLoginPrompt(
                        getLocalizedString(R.string.player_playback_login_required)
                    )
                )
                withContext(Dispatchers.Main) { next() }
            }
            is SongUrlResult.Failure -> {
                NPLogger.e(
                    "NERI-PlayerManager",
                    "获取播放地址失败，跳过当前歌曲: id=${song.id}, source=${song.album}"
                )
                consecutivePlayFailures++
                withContext(Dispatchers.Main) { next() }
            }
        }
    }
}

private fun PlayerManager.maybeAutoMatchBiliMetadata(song: SongItem, requestToken: Long) {
    if (!isBiliTrack(song)) return
    if (song.matchedSongId != null || !song.matchedLyric.isNullOrEmpty()) return
    if (song.customName != null || song.customArtist != null || song.customCoverUrl != null) return

    ioScope.launch {
        val currentSong = _currentSongFlow.value ?: return@launch
        if (requestToken != playbackRequestToken || !currentSong.sameIdentityAs(song)) {
            return@launch
        }

        val candidate =
            SearchManager.findBestSearchCandidate(song.name, song.artist) ?: return@launch
        val latestSong = _currentSongFlow.value ?: return@launch
        if (requestToken != playbackRequestToken || !latestSong.sameIdentityAs(song)) {
            return@launch
        }

        replaceMetadataFromSearch(latestSong, candidate, isAuto = true)
    }
}

private fun PlayerManager.maybeWarmCurrentAndUpcomingYouTubeMusic(currentSongIndex: Int) {
    val targets = resolveYouTubeWarmupTargets(
        playlist = currentPlaylist,
        currentSongIndex = currentSongIndex,
        preferredQuality = youtubePreferredQuality
    )
    if (!targets.hasWork) {
        return
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "maybeWarmCurrentAndUpcomingYouTubeMusic: currentVideoId=${targets.currentVideoId}, nextVideoId=${targets.nextVideoId}, preferredQuality=${targets.preferredQuality}, queueSize=${currentPlaylist.size}"
    )

    ioScope.launch {
        runCatching {
            youtubeMusicPlaybackRepository.warmBootstrapAsync()
        }.onFailure { error ->
            NPLogger.w(
                "NERI-PlayerManager",
                "Warm YouTube Music bootstrap failed: ${error.message}"
            )
        }

        targets.currentVideoId?.let { videoId ->
            runCatching {
                youtubeMusicPlaybackRepository.kickoffPlayableAudioPrefetch(
                    videoId = videoId,
                    preferredQualityOverride = targets.preferredQuality,
                    requireDirect = true,
                    preferM4a = true
                )
            }.onFailure { error ->
                NPLogger.w(
                    "NERI-PlayerManager",
                    "Warm current YouTube Music stream failed for $videoId: ${error.message}"
                )
            }
        }

        targets.nextVideoId?.let { videoId ->
            runCatching {
                youtubeMusicPlaybackRepository.kickoffPlayableAudioPrefetch(
                    videoId = videoId,
                    preferredQualityOverride = targets.preferredQuality,
                    requireDirect = true,
                    preferM4a = true
                )
            }.onFailure { error ->
                NPLogger.w(
                    "NERI-PlayerManager",
                    "Prefetch next YouTube Music stream failed for $videoId: ${error.message}"
                )
            }
        }
    }
}

internal fun PlayerManager.playBiliVideoPartsImpl(
    videoInfo: BiliClient.VideoBasicInfo,
    startIndex: Int,
    coverUrl: String
) {
    ensureInitialized()
    check(initialized) { "Call PlayerManager.initialize(application) first." }
    val songs = videoInfo.pages.map { page -> buildBiliPartSong(page, videoInfo, coverUrl) }
    NPLogger.d(
        "NERI-PlayerManager",
        "playBiliVideoParts: bvid=${videoInfo.bvid}, pages=${songs.size}, requestedStart=$startIndex, title=${videoInfo.title}"
    )
    playPlaylist(songs, startIndex)
}

internal fun PlayerManager.playImpl(
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
) {
    ensureInitialized()
    if (!initialized) return
    if (shouldBlockLocalRoomControl(commandSource)) return
    cancelPendingPauseRequest(resetVolumeToFull = true)
    suppressAutoResumeForCurrentSession = false
    resumePlaybackRequested = true
    val song = _currentSongFlow.value
    val preparedInPlayer = isPreparedInPlayer()
    NPLogger.d(
        "NERI-PlayerManager",
        "play requested: source=$commandSource, prepared=$preparedInPlayer, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, song=${song?.name}, stack=[${debugStackHint()}]"
    )
    if (preparedInPlayer && song != null && !isLocalSong(song)) {
        val url = _currentMediaUrl.value
        if (!url.isNullOrBlank()) {
            val ageMs = if (currentMediaUrlResolvedAtMs > 0L) {
                SystemClock.elapsedRealtime() - currentMediaUrlResolvedAtMs
            } else {
                Long.MAX_VALUE
            }
            if (
                ageMs >= MEDIA_URL_STALE_MS ||
                YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeResume(song, url)
            ) {
                refreshCurrentSongUrl(
                    resumePositionMs = player.currentPosition,
                    allowFallback = false,
                    reason = "stale_resume",
                    bypassCooldown = true,
                    resumedPlaybackCommandSource = commandSource
                )
                return
            }
        }
    }
    when {
        preparedInPlayer -> {
            syncExoRepeatMode()
            startPlayerPlaybackWithFade(resolveCurrentPlaybackStartPlan())
            val resumePositionMs = player.currentPosition.coerceAtLeast(0L)
            _playbackPositionMs.value = resumePositionMs
            ioScope.launch {
                persistState(
                    positionMs = resumePositionMs,
                    shouldResumePlayback = true
                )
            }
            emitPlaybackCommand(
                type = "PLAY",
                source = commandSource,
                positionMs = resumePositionMs,
                currentIndex = currentIndex
            )
        }
        currentPlaylist.isNotEmpty() && currentIndex != -1 -> {
            val manualResumeDecision = resolveManualResumePlaybackDecision(
                keepLastPlaybackProgressEnabled = keepLastPlaybackProgressEnabled,
                restoredResumePositionMs = restoredResumePositionMs,
                persistedPlaybackPositionMs = _playbackPositionMs.value,
                isPlayerPrepared = preparedInPlayer,
                currentMediaUrlResolvedAtMs = currentMediaUrlResolvedAtMs
            )
            playAtIndex(
                currentIndex,
                resumePositionMs = manualResumeDecision.resumePositionMs,
                commandSource = commandSource,
                forceStartupProtectionFade = manualResumeDecision.forceStartupProtectionFade
            )
            emitPlaybackCommand(
                type = "PLAY",
                source = commandSource,
                positionMs = manualResumeDecision.resumePositionMs,
                currentIndex = currentIndex
            )
        }
        currentPlaylist.isNotEmpty() -> {
            playAtIndex(0, commandSource = commandSource)
            emitPlaybackCommand(
                type = "PLAY",
                source = commandSource,
                positionMs = 0L,
                currentIndex = 0
            )
        }
        else -> {}
    }
}

internal fun PlayerManager.handleTrackEndedIfNeededImpl(source: String) {
    val currentKey = trackEndDeduplicationKey(
        mediaId = player.currentMediaItem?.mediaId,
        fallbackSongKey = _currentSongFlow.value?.stableKey()
    )
    if (!shouldHandleTrackEnd(lastHandledKey = lastHandledTrackEndKey, currentKey = currentKey)) {
        NPLogger.d(
            "NERI-PlayerManager",
            "忽略重复的曲目结束事件: source=$source, key=$currentKey"
        )
        return
    }
    val now = SystemClock.elapsedRealtime()
    if (now - lastTrackEndHandledAtMs < 500L) {
        NPLogger.d(
            "NERI-PlayerManager",
            "忽略过近的曲目结束事件: source=$source, key=$currentKey, delta=${now - lastTrackEndHandledAtMs}ms"
        )
        return
    }
    lastHandledTrackEndKey = currentKey
    lastTrackEndHandledAtMs = now
    NPLogger.d(
        "NERI-PlayerManager",
        "开始处理曲目结束事件: source=$source, key=$currentKey, index=$currentIndex, queueSize=${currentPlaylist.size}"
    )
    handleTrackEnded()
}

internal fun PlayerManager.pauseImpl(
    forcePersist: Boolean = false,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
) {
    ensureInitialized()
    if (!initialized) return
    if (shouldBlockLocalRoomControl(commandSource)) return
    NPLogger.d(
        "NERI-PlayerManager",
        "pause requested: forcePersist=$forcePersist, source=$commandSource, currentSong=${_currentSongFlow.value?.name}, isPlaying=${player.isPlaying}, playWhenReady=${player.playWhenReady}, stack=[${debugStackHint()}]"
    )
    cancelPendingPauseRequest()
    resumePlaybackRequested = false
    playbackRequestToken += 1
    playJob?.cancel()
    playJob = null
    val shouldFadeOut =
        playbackFadeInEnabled && playbackFadeOutDurationMs > 0L && isPlayerInitialized()
    if (shouldFadeOut) {
        val scheduledPauseToken = playbackRequestToken
        lateinit var scheduledPauseJob: Job
        scheduledPauseJob = mainScope.launch {
            try {
                fadeOutCurrentPlaybackIfNeeded(
                    enabled = true,
                    fadeOutDurationMs = playbackFadeOutDurationMs
                )
                if (scheduledPauseToken != playbackRequestToken) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "暂停请求已过期，跳过淡出后的暂停: requestToken=$scheduledPauseToken, currentToken=$playbackRequestToken"
                    )
                    return@launch
                }
                pauseInternal(forcePersist, resetVolumeToFull = false)
            } finally {
                if (pendingPauseJob === scheduledPauseJob) {
                    pendingPauseJob = null
                }
            }
        }
        pendingPauseJob = scheduledPauseJob
    } else {
        pauseInternal(forcePersist, resetVolumeToFull = true)
    }
    emitPlaybackCommand(
        type = "PAUSE",
        source = commandSource,
        positionMs = _playbackPositionMs.value,
        currentIndex = currentIndex
    )
}

private fun PlayerManager.pauseInternal(forcePersist: Boolean, resetVolumeToFull: Boolean) {
    pendingPauseJob = null
    resumePlaybackRequested = false
    val currentSong = _currentSongFlow.value
    val currentPosition = player.currentPosition.coerceAtLeast(0L)
    val expectedDuration = currentSong?.durationMs?.takeIf { it > 0L } ?: player.duration
    val shouldForceFlushShortLocalSong =
        currentSong?.let(::isLocalSong) == true && expectedDuration in 1L..5_000L
    playbackRequestToken += 1
    playJob?.cancel()
    playJob = null
    cancelVolumeFade(resetToFull = resetVolumeToFull)
    val stackHint = Throwable().stackTrace.take(6).joinToString(" <- ") {
        "${it.fileName}:${it.lineNumber}"
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "pauseInternal: song=${currentSong?.name}, positionMs=$currentPosition, state=${playbackStateName(player.playbackState)}, playWhenReady=${player.playWhenReady}, forcePersist=$forcePersist, stack=[$stackHint]"
    )
    player.playWhenReady = false
    player.pause()
    if (shouldForceFlushShortLocalSong) {
        runCatching {
            player.seekTo(currentPosition.coerceAtMost(expectedDuration.coerceAtLeast(0L)))
        }
        _playbackPositionMs.value = currentPosition
    }
    if (!resetVolumeToFull) {
        runPlayerActionOnMainThread {
            if (isPlayerInitialized()) {
                player.volume = 1f
            }
        }
    }
    if (forcePersist) {
        moe.ouom.neriplayer.core.player.state.blockingIo {
            persistState(positionMs = currentPosition, shouldResumePlayback = false)
        }
    } else {
        ioScope.launch {
            persistState(positionMs = currentPosition, shouldResumePlayback = false)
        }
    }
}

internal fun PlayerManager.togglePlayPauseImpl() {
    ensureInitialized()
    if (!initialized) return
    if (player.isPlaying || player.playWhenReady || playJob?.isActive == true) {
        pause()
    } else {
        play()
    }
}

internal fun PlayerManager.seekToImpl(
    positionMs: Long,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
) {
    ensureInitialized()
    if (!initialized) return
    if (shouldBlockLocalRoomControl(commandSource)) return
    val resolvedPositionMs = positionMs.coerceAtLeast(0L)
    NPLogger.d(
        "NERI-PlayerManager",
        "seekTo requested: positionMs=$resolvedPositionMs, source=$commandSource, currentSong=${_currentSongFlow.value?.name}, currentUrl=${_currentMediaUrl.value}, stack=[${debugStackHint()}]"
    )
    if (
        YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(
            _currentSongFlow.value,
            _currentMediaUrl.value
        )
    ) {
        rememberPendingSeekPosition(resolvedPositionMs)
    } else {
        clearPendingSeekPosition()
    }
    player.seekTo(resolvedPositionMs)
    _playbackPositionMs.value = resolvedPositionMs
    ioScope.launch {
        persistState(
            positionMs = resolvedPositionMs,
            shouldResumePlayback = shouldResumePlaybackSnapshot()
        )
    }
    emitPlaybackCommand(
        type = "SEEK",
        source = commandSource,
        positionMs = resolvedPositionMs,
        currentIndex = currentIndex
    )
}

internal fun PlayerManager.nextImpl(
    force: Boolean = false,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
) {
    ensureInitialized()
    if (!initialized) return
    if (shouldBlockLocalRoomControl(commandSource)) return
    if (currentPlaylist.isEmpty()) return
    val isShuffle = player.shuffleModeEnabled
    val useTransitionFade =
        playbackCrossfadeNextEnabled && (player.isPlaying || player.playWhenReady)
    NPLogger.d(
        "NERI-PlayerManager",
        "next requested: force=$force, source=$commandSource, isShuffle=$isShuffle, currentIndex=$currentIndex, queueSize=${currentPlaylist.size}, transitionFade=$useTransitionFade, stack=[${debugStackHint()}]"
    )

    if (isShuffle) {
        if (shuffleFuture.isNotEmpty()) {
            val nextIdx = shuffleFuture.removeAt(shuffleFuture.lastIndex)
            if (currentIndex != -1) shuffleHistory.add(currentIndex)
            currentIndex = nextIdx
            playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
            emitPlaybackCommand(
                type = "NEXT",
                source = commandSource,
                currentIndex = currentIndex,
                force = force
            )
            return
        }

        if (shuffleBag.isEmpty()) {
            if (force || repeatModeSetting == Player.REPEAT_MODE_ALL) {
                rebuildShuffleBag(excludeIndex = currentIndex)
            } else {
                stopPlaybackPreservingQueue()
                return
            }
        }

        if (shuffleBag.isEmpty()) {
            playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
            return
        }

        if (currentIndex != -1) shuffleHistory.add(currentIndex)

        val pick = if (shuffleBag.size == 1) 0 else Random.nextInt(shuffleBag.size)
        currentIndex = shuffleBag.removeAt(pick)
        playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
        emitPlaybackCommand(
            type = "NEXT",
            source = commandSource,
            currentIndex = currentIndex,
            force = force
        )
    } else {
        if (currentIndex < currentPlaylist.lastIndex) {
            currentIndex++
        } else {
            if (force || repeatModeSetting == Player.REPEAT_MODE_ALL) {
                currentIndex = 0
            } else {
                NPLogger.d("NERI-Player", "Already at the end of the playlist.")
                return
            }
        }
        playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
        emitPlaybackCommand(
            type = "NEXT",
            source = commandSource,
            currentIndex = currentIndex,
            force = force
        )
    }
}

internal fun PlayerManager.previousImpl(
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
) {
    ensureInitialized()
    if (!initialized) return
    if (shouldBlockLocalRoomControl(commandSource)) return
    if (currentPlaylist.isEmpty()) return
    val isShuffle = player.shuffleModeEnabled
    val useTransitionFade =
        playbackCrossfadeNextEnabled && (player.isPlaying || player.playWhenReady)
    NPLogger.d(
        "NERI-PlayerManager",
        "previous requested: source=$commandSource, isShuffle=$isShuffle, currentIndex=$currentIndex, queueSize=${currentPlaylist.size}, transitionFade=$useTransitionFade, stack=[${debugStackHint()}]"
    )

    if (isShuffle) {
        if (shuffleHistory.isNotEmpty()) {
            if (currentIndex != -1) shuffleFuture.add(currentIndex)
            val prev = shuffleHistory.removeAt(shuffleHistory.lastIndex)
            currentIndex = prev
            playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
            emitPlaybackCommand(
                type = "PREVIOUS",
                source = commandSource,
                currentIndex = currentIndex
            )
        } else {
            NPLogger.d("NERI-Player", "No previous track in shuffle history.")
        }
    } else {
        if (currentIndex > 0) {
            currentIndex--
            playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
            emitPlaybackCommand(
                type = "PREVIOUS",
                source = commandSource,
                currentIndex = currentIndex
            )
        } else {
            if (repeatModeSetting == Player.REPEAT_MODE_ALL && currentPlaylist.isNotEmpty()) {
                currentIndex = currentPlaylist.lastIndex
                playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
                emitPlaybackCommand(
                    type = "PREVIOUS",
                    source = commandSource,
                    currentIndex = currentIndex
                )
            } else {
                NPLogger.d("NERI-Player", "Already at the start of the playlist.")
            }
        }
    }
}

internal fun PlayerManager.cycleRepeatModeImpl() {
    ensureInitialized()
    if (!initialized) return
    val previousMode = repeatModeSetting
    val newMode = when (repeatModeSetting) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
        else -> Player.REPEAT_MODE_OFF
    }
    repeatModeSetting = newMode
    syncExoRepeatMode()
    _repeatModeFlow.value = newMode
    NPLogger.d(
        "NERI-PlayerManager",
        "cycleRepeatMode: previousMode=$previousMode, newMode=$newMode, exoRepeatMode=${player.repeatMode}"
    )
    ioScope.launch {
        persistState()
    }
}

internal fun PlayerManager.setShuffleImpl(enabled: Boolean) {
    ensureInitialized()
    if (!initialized) return
    if (player.shuffleModeEnabled == enabled) return
    NPLogger.d(
        "NERI-PlayerManager",
        "setShuffle: enabled=$enabled, currentIndex=$currentIndex, queueSize=${currentPlaylist.size}, historySize=${shuffleHistory.size}, futureSize=${shuffleFuture.size}"
    )
    player.shuffleModeEnabled = enabled
    shuffleHistory.clear()
    shuffleFuture.clear()
    if (enabled) {
        rebuildShuffleBag(excludeIndex = currentIndex)
    } else {
        shuffleBag.clear()
    }
    ioScope.launch {
        persistState()
    }
}

internal fun PlayerManager.startProgressUpdates() {
    stopProgressUpdates()
    NPLogger.d(
        "NERI-PlayerManager",
        "startProgressUpdates: currentSong=${_currentSongFlow.value?.name}, playbackState=${playbackStateName(player.playbackState)}"
    )
    progressJob = mainScope.launch {
        while (isActive) {
            val positionMs = resolveDisplayedPlaybackPosition(
                player.currentPosition.coerceAtLeast(0L)
            )
            _playbackPositionMs.value = positionMs
            maybePersistPlaybackProgress(positionMs)
            delay(PLAYBACK_PROGRESS_UPDATE_INTERVAL_MS)
        }
    }
}

internal fun PlayerManager.stopProgressUpdatesImpl() {
    if (progressJob?.isActive == true) {
        NPLogger.d(
            "NERI-PlayerManager",
            "stopProgressUpdates: currentSong=${_currentSongFlow.value?.name}, currentPosition=${_playbackPositionMs.value}"
        )
    }
    progressJob?.cancel()
    progressJob = null
}

private fun PlayerManager.maybePersistPlaybackProgress(positionMs: Long) {
    if (currentPlaylist.isEmpty()) return
    if (!shouldResumePlaybackSnapshot()) return
    val now = SystemClock.elapsedRealtime()
    if (now - lastStatePersistAtMs < STATE_PERSIST_INTERVAL_MS) return
    lastStatePersistAtMs = now
    NPLogger.d(
        "NERI-PlayerManager",
        "maybePersistPlaybackProgress(): positionMs=$positionMs, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, song=${_currentSongFlow.value?.name}"
    )
    ioScope.launch {
        persistState(positionMs = positionMs, shouldResumePlayback = true)
    }
}

internal fun PlayerManager.stopPlaybackPreservingQueueImpl(clearMediaUrl: Boolean = false) {
    NPLogger.d(
        "NERI-PlayerManager",
        "stopPlaybackPreservingQueue(): clearMediaUrl=$clearMediaUrl, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, currentSong=${_currentSongFlow.value?.name}, mediaUrlPresent=${!_currentMediaUrl.value.isNullOrBlank()}, stack=[${debugStackHint()}]"
    )
    cancelPendingPauseRequest(resetVolumeToFull = true)
    playbackRequestToken += 1
    playJob?.cancel()
    playJob = null
    lastHandledTrackEndKey = null
    resumePlaybackRequested = false
    lastAutoTrackAdvanceAtMs = 0L
    stopProgressUpdates()
    cancelVolumeFade(resetToFull = true)
    runCatching { player.stop() }
    runCatching { player.clearMediaItems() }
    _isPlayingFlow.value = false
    _playWhenReadyFlow.value = false
    _playerPlaybackStateFlow.value = Player.STATE_IDLE
    clearPendingSeekPosition()
    _playbackPositionMs.value = 0L
    if (currentPlaylist.isEmpty()) {
        currentIndex = -1
        _currentSongFlow.value = null
        _currentMediaUrl.value = null
        _currentPlaybackAudioInfo.value = null
        currentMediaUrlResolvedAtMs = 0L
    } else {
        currentIndex = currentIndex.coerceIn(0, currentPlaylist.lastIndex)
        _currentSongFlow.value = currentPlaylist.getOrNull(currentIndex)
        if (clearMediaUrl) {
            _currentMediaUrl.value = null
            _currentPlaybackAudioInfo.value = null
            currentMediaUrlResolvedAtMs = 0L
        }
    }
    consecutivePlayFailures = 0
    NPLogger.d(
        "NERI-PlayerManager",
        "stopPlaybackPreservingQueue(): completed, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, retainedSong=${_currentSongFlow.value?.name}, mediaUrlPresent=${!_currentMediaUrl.value.isNullOrBlank()}"
    )
    ioScope.launch {
        persistState()
    }
}
