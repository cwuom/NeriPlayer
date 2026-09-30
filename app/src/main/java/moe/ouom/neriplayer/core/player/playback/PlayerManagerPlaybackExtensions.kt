@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.playback

import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.identity.stableKey

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.platform.bili.playback.resolver.buildBiliPartSong
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.lyricon.LyriconManager
import moe.ouom.neriplayer.core.lyricon.mediaLyriconPositionMs
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.LocalPlaylistPlaybackSource
import moe.ouom.neriplayer.core.player.audio.focus.StartupAudioFocusController
import moe.ouom.neriplayer.core.player.debug.playbackStateName
import moe.ouom.neriplayer.core.player.lifecycle.clearUsbExclusiveInterruptedPlaybackIntent
import moe.ouom.neriplayer.core.player.lifecycle.prepareUsbExclusiveRouteForManualPlayback
import moe.ouom.neriplayer.core.player.lifecycle.updateAudioOffloadPreferences
import moe.ouom.neriplayer.core.player.lyrics.isExternalBluetoothLyricCadenceActive
import moe.ouom.neriplayer.core.player.lyrics.updateExternalBluetoothLyricLine
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.core.player.persistence.persistStateNow
import moe.ouom.neriplayer.core.player.persistence.prepareStatePersist
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.command.PlaybackStartPlan
import moe.ouom.neriplayer.core.player.policy.command.USB_TRACK_TRANSITION_PROTECTION_FADE_DURATION_MS
import moe.ouom.neriplayer.core.player.policy.command.resolveEffectivePlaybackStartPlan
import moe.ouom.neriplayer.core.player.policy.command.resolveManagedPlaybackStartPlan
import moe.ouom.neriplayer.core.player.policy.command.resolveManualResumePlaybackDecision
import moe.ouom.neriplayer.core.player.policy.command.resolveNoFadePlaybackStartPlan
import moe.ouom.neriplayer.core.player.policy.command.resolvePauseVolumePlan
import moe.ouom.neriplayer.core.player.policy.command.resolvePlaybackContinuationStartPlan
import moe.ouom.neriplayer.core.player.policy.command.shouldPausePlaybackWhenToggling
import moe.ouom.neriplayer.core.player.policy.pending.resolvePendingMediaLoadEntryAction
import moe.ouom.neriplayer.core.player.policy.pending.resolvePendingPauseAction
import moe.ouom.neriplayer.core.player.policy.pending.resolvePendingPlayAction
import moe.ouom.neriplayer.core.player.policy.pending.resolvePendingSeekAction
import moe.ouom.neriplayer.core.player.policy.pending.resolveSeekExecutionAction
import moe.ouom.neriplayer.core.player.policy.pending.PendingSeekAction
import moe.ouom.neriplayer.core.player.policy.pending.SeekExecutionAction
import moe.ouom.neriplayer.core.player.policy.pending.shouldApplyResolvedMedia
import moe.ouom.neriplayer.core.player.policy.pending.shouldApplyResolvedMediaSideEffects
import moe.ouom.neriplayer.core.player.policy.progress.PLAYBACK_PROGRESS_STATS_UPDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.player.policy.progress.resolvePlaybackProgressUpdateIntervalMs
import moe.ouom.neriplayer.core.player.policy.progress.shouldRunPlaybackProgressUpdates
import moe.ouom.neriplayer.core.player.policy.skip.BiliSkipSegmentSource
import moe.ouom.neriplayer.core.player.policy.skip.resolveBiliSkipSegmentPromptMessageRes
import moe.ouom.neriplayer.core.player.policy.wake.PlaybackTransitionWakeLock
import moe.ouom.neriplayer.core.player.prefetch.cancelGenericUrlPrefetchUnlessReusableForSong
import moe.ouom.neriplayer.core.player.prefetch.cancelYouTubePrefetchForPlaybackDemand
import moe.ouom.neriplayer.core.player.prefetch.clearPlaybackDemandCacheKey
import moe.ouom.neriplayer.core.player.prefetch.kickoffYouTubePlaybackIntentWarmup
import moe.ouom.neriplayer.core.player.prefetch.replacePlaybackDemandCacheKey
import moe.ouom.neriplayer.core.player.resolver.youtube.YouTubeSeekRefreshPolicy
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.url.cancelUrlRefreshIfNotReusableForPendingLoad
import moe.ouom.neriplayer.core.player.url.allowsCustomCacheKey
import moe.ouom.neriplayer.core.player.url.listenTogetherFallbackResult
import moe.ouom.neriplayer.core.player.url.listenTogetherPreferredQualityKey
import moe.ouom.neriplayer.core.player.url.mergeListenTogetherFallbackResult
import moe.ouom.neriplayer.core.player.url.resolveSongUrl
import moe.ouom.neriplayer.core.player.url.resolvePlaybackAudioInfoForListenTogetherStreamCandidate
import moe.ouom.neriplayer.core.player.url.synchronizeCachedPlaybackDescriptor
import moe.ouom.neriplayer.core.player.url.youtubePlaybackRecoveryStrategyForSeek
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.watchdog.cancelPlaybackStartupWatchdog
import moe.ouom.neriplayer.core.player.watchdog.clearActivePlaybackCandidates
import moe.ouom.neriplayer.core.player.watchdog.configureActivePlaybackCandidates
import moe.ouom.neriplayer.core.player.watchdog.currentPlaybackCandidate
import moe.ouom.neriplayer.core.player.watchdog.isPlaybackActuallyAdvancing
import moe.ouom.neriplayer.core.player.watchdog.resetPlaybackProgressAdvanceBaseline
import moe.ouom.neriplayer.core.player.watchdog.resetPlaybackRuntimeWatchdog
import moe.ouom.neriplayer.core.player.watchdog.recordPlaybackRuntimeProgress
import moe.ouom.neriplayer.core.player.watchdog.schedulePlaybackStartupWatchdog
import moe.ouom.neriplayer.core.player.watchdog.schedulePlaybackRuntimeWatchdog
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager
import moe.ouom.neriplayer.data.local.playlist.runLocalPlaylistMutationSafely
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.api.youtube.transport.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.api.youtube.transport.youtubeMusicThumbnailUrl
import moe.ouom.neriplayer.data.ltw.playback.shouldShowListenTogetherPreviewClipNotice
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import kotlin.time.Duration.Companion.milliseconds

private val playbackAutoSkipPolicy = PlaybackAutoSkipPolicy(BiliPlaybackAutoSkipTargets)

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
    if (resetToFull && !isAudioRouteMuteSuppressed() && isPlayerInitialized()) {
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
    if (
        resetVolumeToFull &&
        hadPendingPause &&
        !isAudioRouteMuteSuppressed() &&
        isPlayerInitialized()
    ) {
        runPlayerActionOnMainThread {
            if (isPlayerInitialized()) {
                player.volume = 1f
            }
        }
    }
}

private fun PlayerManager.isAudioRouteMuteSuppressed(): Boolean {
    return audioRouteMuteRestoreVolume?.let { it > 0f } == true
}

private fun PlayerManager.volumeWhileAudioRouteMuted(volume: Float): Float {
    return if (isAudioRouteMuteSuppressed()) 0f else volume
}

internal fun PlayerManager.clearAudioRouteMuteSuppression(reason: String) {
    clearAudioRouteMuteSuppression(
        reason = reason,
        preserveExplicitRestore = shouldMuteListenTogetherListenerForAudioRouteLoss()
    )
}

internal fun PlayerManager.clearAudioRouteMuteSuppression(
    reason: String,
    preserveExplicitRestore: Boolean
) {
    if (
        preserveExplicitRestore &&
        shouldDeferAudioRouteMuteRestore(audioRouteMuteRequiresExplicitRestore)
    ) {
        NPLogger.d(
            "NERI-PlayerManager",
            "clearAudioRouteMuteSuppression(): keep explicit listener mute, reason=$reason, currentSong=${_currentSongFlow.value?.name}"
        )
        return
    }
    val suppressedVolume = audioRouteMuteRestoreVolume
    audioRouteMuteRestoreVolume = null
    audioRouteMuteRequiresExplicitRestore = false
    _audioRouteMuteSuppressedFlow.value = false
    if (suppressedVolume == null) return
    resetPlaybackRuntimeWatchdog(reason = "audio_route_mute_cleared")
    NPLogger.d(
        "NERI-PlayerManager",
        "clearAudioRouteMuteSuppression(): reason=$reason, suppressedVolume=$suppressedVolume, currentSong=${_currentSongFlow.value?.name}"
    )
}

internal fun shouldDeferAudioRouteMuteRestore(
    requiresExplicitRestore: Boolean
): Boolean = requiresExplicitRestore

internal fun resolveAudioRouteMuteRestoreVolume(
    currentVolume: Float,
    existingRestoreVolume: Float?
): Float? {
    return existingRestoreVolume?.takeIf { it > 0f }
        ?: currentVolume.coerceIn(0f, 1f).takeIf { it > 0f }
}

internal fun PlayerManager.suppressPlaybackForAudioRouteLoss(reason: String) {
    if (!isPlayerInitialized()) return
    val requiresExplicitRestore = shouldMuteListenTogetherListenerForAudioRouteLoss()
    cancelVolumeFade(resetToFull = false)
    runPlayerActionOnMainThread {
        if (!isPlayerInitialized()) return@runPlayerActionOnMainThread
        val currentVolume = runCatching { player.volume.coerceIn(0f, 1f) }.getOrDefault(1f)
        val restoreVolume = resolveAudioRouteMuteRestoreVolume(
            currentVolume = currentVolume,
            existingRestoreVolume = audioRouteMuteRestoreVolume
        )
        if (restoreVolume == null) {
            audioRouteMuteRestoreVolume = null
            audioRouteMuteRequiresExplicitRestore = false
            _audioRouteMuteSuppressedFlow.value = false
            resetPlaybackRuntimeWatchdog(reason = "audio_route_mute_noop")
            return@runPlayerActionOnMainThread
        }
        audioRouteMuteRestoreVolume = restoreVolume
        audioRouteMuteRequiresExplicitRestore =
            audioRouteMuteRequiresExplicitRestore || requiresExplicitRestore
        _audioRouteMuteSuppressedFlow.value = true
        resetPlaybackRuntimeWatchdog(reason = "audio_route_mute_entered")
        player.volume = 0f
        NPLogger.d(
            "NERI-PlayerManager",
            "suppressPlaybackForAudioRouteLoss(): reason=$reason, capturedVolume=$restoreVolume, explicitRestore=${audioRouteMuteRequiresExplicitRestore}, currentSong=${_currentSongFlow.value?.name}"
        )
    }
}

internal fun PlayerManager.restoreAudioRouteMuteImpl() {
    val restoreVolume = audioRouteMuteRestoreVolume ?: run {
        audioRouteMuteRequiresExplicitRestore = false
        _audioRouteMuteSuppressedFlow.value = false
        return
    }
    audioRouteMuteRestoreVolume = null
    audioRouteMuteRequiresExplicitRestore = false
    _audioRouteMuteSuppressedFlow.value = false
    resetPlaybackRuntimeWatchdog(reason = "audio_route_mute_restored")
    if (!isPlayerInitialized()) return
    runPlayerActionOnMainThread {
        if (!isPlayerInitialized()) return@runPlayerActionOnMainThread
        player.volume = restoreVolume.coerceIn(0f, 1f)
        schedulePlaybackRuntimeWatchdog(reason = "audio_route_mute_restored")
        NPLogger.d(
            "NERI-PlayerManager",
            "restoreAudioRouteMuteImpl(): restoredVolume=$restoreVolume, currentSong=${_currentSongFlow.value?.name}"
        )
    }
}

internal fun PlayerManager.restorePlaybackAfterTransientAudioRouteLoss(reason: String) {
    if (shouldDeferAudioRouteMuteRestore(audioRouteMuteRequiresExplicitRestore)) {
        NPLogger.d(
            "NERI-PlayerManager",
            "restorePlaybackAfterTransientAudioRouteLoss(): keep listener muted until explicit restore, reason=$reason, currentSong=${_currentSongFlow.value?.name}"
        )
        return
    }
    val restoreVolume = audioRouteMuteRestoreVolume ?: run {
        _audioRouteMuteSuppressedFlow.value = false
        return
    }
    audioRouteMuteRestoreVolume = null
    _audioRouteMuteSuppressedFlow.value = false
    resetPlaybackRuntimeWatchdog(reason = "audio_route_mute_transient_restored")
    if (!isPlayerInitialized()) return
    val shouldRestore = runCatching {
        player.playWhenReady || player.isPlaying
    }.getOrDefault(false) || _isPlayingFlow.value || playJob?.isActive == true
    if (!shouldRestore) {
        NPLogger.d(
            "NERI-PlayerManager",
            "restorePlaybackAfterTransientAudioRouteLoss(): skipped restore for inactive playback, reason=$reason, currentSong=${_currentSongFlow.value?.name}"
        )
        return
    }
    runPlayerActionOnMainThread {
        if (!isPlayerInitialized()) return@runPlayerActionOnMainThread
        player.volume = restoreVolume.coerceIn(0f, 1f)
        schedulePlaybackRuntimeWatchdog(reason = "audio_route_mute_transient_restored")
        NPLogger.d(
            "NERI-PlayerManager",
            "restorePlaybackAfterTransientAudioRouteLoss(): reason=$reason, restoredVolume=$restoreVolume, currentSong=${_currentSongFlow.value?.name}"
        )
    }
}

internal fun PlayerManager.pauseForAudioRouteLoss(reason: String) {
    if (shouldMuteListenTogetherListenerForAudioRouteLoss()) {
        NPLogger.d(
            "NERI-PlayerManager",
            "pauseForAudioRouteLoss(): keep Listen Together listener playing silently, reason=$reason, currentSong=${_currentSongFlow.value?.name}"
        )
        return
    }
    _playWhenReadyFlow.value = false
    _isPlayingFlow.value = false
    if (lyriconEnabled) {
        LyriconManager.setPlaybackState(false)
    }
    syncPlaybackControlPlayingState()
    pauseImpl(
        forcePersist = false,
        commandSource = PlaybackCommandSource.LOCAL,
        allowFadeOut = false,
        preserveMutedVolume = true,
        debugReason = "audio_route_loss:$reason",
        flushPlayerOutput = true,
    )
}

private fun PlayerManager.persistPausedPlaybackState(
    forcePersist: Boolean,
    positionMs: Long,
    shouldResumePlayback: Boolean,
    reason: String
) {
    if (!forcePersist) {
        scheduleStatePersist(
            positionMs = positionMs,
            shouldResumePlayback = shouldResumePlayback
        )
        return
    }
    val request = prepareStatePersist(positionMs, shouldResumePlayback) ?: return
    ioScope.launch {
        try {
            runCatching { drainPlaybackStatsPersistJobBlocking(reason) }
                .onFailure { error ->
                    NPLogger.w(
                        "NERI-PlayerManager",
                        "pause persistence could not drain playback stats: reason=$reason",
                        error
                    )
                }
            persistStateNow(request, reason)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(
                "NERI-PlayerManager",
                "pause persistence failed: reason=$reason",
                error
            )
        }
    }
}

internal fun PlayerManager.preparePlayerForManagedStart(plan: PlaybackStartPlan) {
    if (!isPlayerInitialized()) return
    cancelVolumeFade()
    val effectivePlan = resolveEffectivePlaybackStartPlan(
        plan = plan,
        usbExclusivePlaybackEnabled = usbExclusivePlaybackEnabled
    )
    NPLogger.d(
        "NERI-PlayerManager",
        "preparePlayerForManagedStart: useFadeIn=${effectivePlan.useFadeIn}, fadeDurationMs=${effectivePlan.fadeDurationMs}, initialVolume=${effectivePlan.initialVolume}, currentSong=${_currentSongFlow.value?.name}"
    )
    player.playWhenReady = false
    player.volume = volumeWhileAudioRouteMuted(effectivePlan.initialVolume)
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
        delay(stepDelay.milliseconds)
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
    StartupAudioFocusController.release("playback_start")
    val effectivePlan = resolveEffectivePlaybackStartPlan(
        plan = plan,
        usbExclusivePlaybackEnabled = usbExclusivePlaybackEnabled
    )
    NPLogger.d(
        "NERI-PlayerManager",
        "startPlayerPlaybackWithFade: useFadeIn=${effectivePlan.useFadeIn}, fadeDurationMs=${effectivePlan.fadeDurationMs}, initialVolume=${effectivePlan.initialVolume}, currentSong=${_currentSongFlow.value?.name}"
    )
    runPlayerActionOnMainThread {
        if (!isPlayerInitialized()) return@runPlayerActionOnMainThread
        if (usbExclusivePlaybackEnabled && !isUsbExclusiveNativePlaybackStable()) {
            markUsbExclusivePlaybackPreparing(true, "playback_start")
        }
        if (!prepareUsbExclusiveRouteForManualPlayback("playback_start")) {
            return@runPlayerActionOnMainThread
        }
        applyAudioFocusPolicyOnMainThread()
        player.volume = volumeWhileAudioRouteMuted(effectivePlan.initialVolume)
        player.playWhenReady = true
        player.play()
    }
    if (!effectivePlan.useFadeIn || isAudioRouteMuteSuppressed()) {
        return
    }

    val steps = fadeStepsFor(effectivePlan.fadeDurationMs)
    if (steps <= 0) return
    val stepDelay = (effectivePlan.fadeDurationMs / steps).coerceAtLeast(1L)
    volumeFadeJob = mainScope.launch {
        repeat(steps) { step ->
            delay(stepDelay.milliseconds)
            if (!isPlayerInitialized()) return@launch
            player.volume = volumeWhileAudioRouteMuted(
                ((step + 1).toFloat() / steps).coerceAtMost(1f)
            )
        }
        if (isPlayerInitialized()) {
            player.volume = volumeWhileAudioRouteMuted(1f)
        }
        volumeFadeJob = null
    }
}

internal fun PlayerManager.resolveCurrentPlaybackStartPlan(
    useTrackTransitionFade: Boolean = false,
    useUsbTransitionProtection: Boolean = false,
    forceStartupProtectionFade: Boolean = false
): PlaybackStartPlan {
    return resolveManagedPlaybackStartPlan(
        playbackFadeInEnabled = playbackFadeInEnabled,
        playbackFadeInDurationMs = playbackFadeInDurationMs,
        playbackCrossfadeInDurationMs = playbackCrossfadeInDurationMs,
        useTrackTransitionFade = useTrackTransitionFade,
        useUsbTransitionProtection = useUsbTransitionProtection,
        forceStartupProtectionFade = forceStartupProtectionFade
    )
}

internal fun PlayerManager.playPlaylistImpl(
    songs: List<SongItem>,
    startIndex: Int,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
    bypassLoudVolumeWarning: Boolean = false,
    localPlaylistId: Long? = null
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
    if (requestUsbExclusiveLoudPlaybackConfirmation(
            commandSource = commandSource,
            bypassWarning = bypassLoudVolumeWarning,
            continuePlayback = {
                playPlaylistImpl(
                    songs = songs,
                    startIndex = startIndex,
                    commandSource = commandSource,
                    bypassLoudVolumeWarning = true,
                    localPlaylistId = localPlaylistId
                )
            }
        )
    ) {
        return
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "playPlaylist: size=${songs.size}, requestedStart=$startIndex, resolvedStart=${startIndex.coerceIn(0, songs.lastIndex)}, source=$commandSource, target=${targetSong.name}, stack=[${debugStackHint()}]"
    )
    suppressAutoResumeForCurrentSession = false
    consecutivePlayFailures = 0
    localPlaylistPlaybackSource = localPlaylistId?.let { playlistId ->
        LocalPlaylistPlaybackSource(
            playlistId = playlistId,
            songKeys = songs.mapTo(LinkedHashSet(songs.size)) { song -> song.stableKey() }
        )
    }
    publishShuffledCurrentSong(queueSessionBindings.startPlaylist(songs, startIndex, commandSource))

    playAtIndex(currentIndex, commandSource = commandSource)
    emitPlaybackCommand(
        type = "PLAY_PLAYLIST",
        source = commandSource,
        queue = currentPlaylist.toList(),
        currentIndex = currentIndex,
        positionMs = _playbackPositionMs.value
    )
    scheduleStatePersist()
}

internal fun PlayerManager.playAtIndex(
    index: Int,
    resumePositionMs: Long = 0L,
    useTrackTransitionFade: Boolean = false,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
    forceStartupProtectionFade: Boolean = false,
    startPlanOverride: PlaybackStartPlan? = null,
    startPaused: Boolean = false,
    allowRememberedLongFormPosition: Boolean =
        commandSource == PlaybackCommandSource.LOCAL
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
            AppFeedback.showToast(
                context = application,
                message = getLocalizedString(R.string.toast_playback_stopped)
            )
        }
        stopPlaybackPreservingQueue(clearMediaUrl = true)
        return
    }

    val song = currentPlaylist[index]
    var keepTransitionWakeLockUntilPlaybackProgress = false
    val resolvedResumePositionMs = resolveRememberedLongFormPlaybackStartPosition(
        song = song,
        requestedPositionMs = resumePositionMs,
        allowRememberedPosition = allowRememberedLongFormPosition
    )
    val useUsbTransitionProtection = usbExclusivePlaybackEnabled &&
        (player.isPlaying || player.playWhenReady)
    NPLogger.d(
        "NERI-PlayerManager",
            "playAtIndex: index=$index, song=${song.name}, resumePositionMs=$resolvedResumePositionMs, " +
            "transitionFade=$useTrackTransitionFade, usbTransitionProtection=" +
            "$useUsbTransitionProtection, source=$commandSource, " +
            "forceStartupProtectionFade=$forceStartupProtectionFade, " +
            "nextToken=${playbackRequestToken + 1}, stack=[${debugStackHint()}]"
    )
    replacePlaybackDemandCacheKey(
        cacheKey = song
            .takeUnless { isLocalSong(it) || isDirectStreamUrl(it.streamUrl) }
            ?.let(::computeCacheKey),
        reason = "play_at_index_request"
    )
    kickoffYouTubePlaybackIntentWarmup(song, source = "play_at_index")
    cancelPendingPauseRequest()
    val previousSong = _currentSongFlow.value
    val retainCurrentAudioInfo = commandSource == PlaybackCommandSource.REMOTE_SYNC &&
        previousSong?.sameIdentityAs(song) == true
    setCurrentSongForPlayback(song, syncLyricon = false)
    _currentMediaUrl.value = null
    if (!retainCurrentAudioInfo) {
        _currentPlaybackAudioInfo.value = null
    }
    currentMediaUrlResolvedAtMs = 0L
    updateResumePlaybackRequested(!startPaused)
    clearUsbExclusiveInterruptedPlaybackIntent("play_at_index")
    clearRestoredPlayback()
    scheduleStatePersist(
        positionMs = resolvedResumePositionMs,
        shouldResumePlayback = !startPaused
    )
    bumpCurrentQueueDisplayRevision()

    playJob?.cancel()
    cancelYouTubePrefetchForPlaybackDemand(song, reason = "play_at_index")
    cancelGenericUrlPrefetchUnlessReusableForSong(song, reason = "play_at_index")
    playbackRequestToken += 1
    val requestToken = playbackRequestToken
    BiliSponsorBlockPlaybackController.onPlaybackRequestStarted(song, requestToken)
    BiliVideoSkipPlaybackController.onPlaybackRequestStarted(song, requestToken)
    if (isBiliTrack(song) && !isListenTogetherActive()) {
        BiliVideoSkipPlaybackController.prepareActiveBiliTrackTarget(
            song = song,
            requestToken = requestToken,
            scope = ioScope
        )
    }
    PlaybackTransitionWakeLock.acquire(
        context = application,
        requestToken = requestToken,
        reason = "play_at_index"
    )
    maybeHydrateSongForPlayback(index, song, requestToken)
    cancelUrlRefreshIfNotReusableForPendingLoad(
        song = song,
        resumePositionMs = resolvedResumePositionMs,
        requestGeneration = requestToken,
        commandSource = commandSource
    )
    clearPendingSeekPosition()
    enterPendingMediaLoad(resolvedResumePositionMs)
    playJob = ioScope.launch {
        try {
        val localResult = resolveSongUrl(
            song = song,
            playbackRequestTokenOverride = requestToken,
            shouldApplyCacheMutation = {
                shouldApplyResolvedMedia(requestToken, playbackRequestToken) && isActive
            }
        )
        val result = mergeListenTogetherFallbackResult(
            localResult = localResult,
            listenTogetherFallback = listenTogetherFallbackResult(song),
            preferredQualityKey = listenTogetherPreferredQualityKey(song)
        )
        if (!shouldApplyResolvedMedia(requestToken, playbackRequestToken) || !isActive) {
            NPLogger.d(
                "NERI-PlayerManager",
                "播放请求已过期，跳过本次 URL 解析结果: song=${song.name}, requestToken=$requestToken, currentToken=$playbackRequestToken, active=$isActive"
            )
            return@launch
        }

        when (result) {
            is SongUrlResult.Success -> {
                if (!shouldApplyResolvedMedia(requestToken, playbackRequestToken) || !isActive) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "播放请求已过期，跳过媒体项装载: song=${song.name}, requestToken=$requestToken, currentToken=$playbackRequestToken, active=$isActive"
                    )
                    return@launch
                }

                fadeOutCurrentPlaybackIfNeeded(
                    enabled = useTrackTransitionFade || useUsbTransitionProtection,
                    fadeOutDurationMs = if (useUsbTransitionProtection) {
                        USB_TRACK_TRANSITION_PROTECTION_FADE_DURATION_MS
                    } else {
                        playbackCrossfadeOutDurationMs
                    }
                )
                if (!shouldApplyResolvedMedia(requestToken, playbackRequestToken) || !isActive) {
                    return@launch
                }

                var appliedResolvedMedia = false
                var switchedToAuthoritativeStreamWait = false
                withContext(Dispatchers.Main) {
                    if (!shouldApplyResolvedMediaSideEffects(
                            requestGeneration = requestToken,
                            currentRequestGeneration = playbackRequestToken,
                            requestActive = true
                        )
                    ) {
                        return@withContext
                    }
                    if (
                        shouldAwaitListenTogetherSharedStreamFallback(
                            song = song,
                            localResolutionRequiresSharedStream = result.isPreviewClip
                        )
                    ) {
                        switchedToAuthoritativeStreamWait = true
                        stopCurrentPlaybackForListenTogetherAwaitingStream()
                        return@withContext
                    }
                    consecutivePlayFailures = 0
                    result.noticeMessage?.let { message ->
                        if (shouldShowListenTogetherPreviewClipNotice(
                                isPreviewClip = result.isPreviewClip,
                                listenerAudioLinkSharingActive =
                                    isListenTogetherAudioLinkFallbackEnabled(),
                                controllerLinkConfirmedUnavailable =
                                    isListenTogetherAuthoritativeStreamConfirmedUnavailable(song)
                            )
                        ) {
                            postPlayerEvent(PlayerEvent.ShowError(message))
                        }
                    }
                    maybeUpdateSongDuration(song, result.durationMs ?: 0L)
                    val cacheKey = result.cacheKeyOverride ?: computeCacheKey(song)
                    replacePlaybackDemandCacheKey(
                        cacheKey = cacheKey.takeUnless {
                            isLocalSong(song) || isDirectStreamUrl(song.streamUrl)
                        },
                        reason = "play_at_index_resolved"
                    )
                    configureActivePlaybackCandidates(
                        result,
                        resolvedResumePositionMs,
                        commandSource
                    )
                    val selectedCandidate = currentPlaybackCandidate()
                    val selectedUrl = selectedCandidate?.url ?: result.url
                    val selectedAudioInfo = resolvePlaybackAudioInfoForListenTogetherStreamCandidate(
                        candidate = selectedCandidate,
                        resolvedAudioInfo = result.audioInfo,
                        existingAudioInfo = _currentPlaybackAudioInfo.value
                    )
                    val selectedMimeType = selectedCandidate?.mimeType ?: result.mimeType
                    val selectedExpectedContentLength =
                        selectedCandidate?.expectedContentLength ?: result.expectedContentLength
                    val selectedRepresentationIdentity =
                        selectedCandidate?.representationIdentity ?: result.representationIdentity
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "Using custom cache key: $cacheKey for song: ${song.name}"
                    )
                    val cacheSynchronization = synchronizeCachedPlaybackDescriptor(
                        cacheKey = cacheKey,
                        audioInfo = selectedAudioInfo,
                        expectedContentLength = selectedExpectedContentLength,
                        representationIdentity = selectedRepresentationIdentity,
                        shouldApplyMutation = {
                            shouldApplyResolvedMediaSideEffects(
                                requestGeneration = requestToken,
                                currentRequestGeneration = playbackRequestToken,
                                requestActive = true
                            )
                        }
                    )
                    if (!shouldApplyResolvedMediaSideEffects(
                            requestGeneration = requestToken,
                            currentRequestGeneration = playbackRequestToken,
                            requestActive = isActive
                        )
                    ) {
                        return@withContext
                    }
                    val mediaItem = buildMediaItem(
                        _currentSongFlow.value ?: song,
                        selectedUrl,
                        cacheKey,
                        selectedMimeType,
                        allowCustomCacheKey = cacheSynchronization.allowsCustomCacheKey()
                    )
                    syncLyriconSong(_currentSongFlow.value ?: song)
                    _currentMediaUrl.value = selectedUrl
                    _currentPlaybackAudioInfo.value = selectedAudioInfo
                    updateAudioOffloadPreferences("resolved_stream_source")
                    currentMediaUrlResolvedAtMs = SystemClock.elapsedRealtime()
                    scheduleStatePersist(
                        positionMs = resolvedResumePositionMs,
                        shouldResumePlayback = !startPaused
                    )
                    val startPlan = startPlanOverride ?: resolveCurrentPlaybackStartPlan(
                        useTrackTransitionFade = useTrackTransitionFade,
                        useUsbTransitionProtection = useUsbTransitionProtection,
                        forceStartupProtectionFade = forceStartupProtectionFade &&
                            resolvedResumePositionMs > 0L
                    )
                    preparePlayerForManagedStart(startPlan)
                    resetTrackEndDeduplicationState()
                    applyWakeModeForPlaybackUrl(selectedUrl)
                    player.setMediaItem(mediaItem)
                    loadedMediaRequestToken = requestToken
                    pendingMediaLoadActive = false
                    syncExoRepeatMode()
                    val startPositionMs = pendingSeekPositionOrNull()
                        ?: resolvedResumePositionMs
                    if (startPositionMs > 0L) {
                        player.seekTo(startPositionMs)
                        _playbackPositionMs.value = startPositionMs
                    }
                    resetPlaybackProgressAdvanceBaseline(startPositionMs)
                    clearPendingSeekPosition()
                    player.prepare()
                    if (resumePlaybackRequested) {
                        startPlayerPlaybackWithFade(startPlan)
                        startProgressUpdates()
                        schedulePlaybackStartupWatchdog(reason = "media_resolved")
                        keepTransitionWakeLockUntilPlaybackProgress = true
                    } else {
                        player.playWhenReady = false
                        player.pause()
                    }
                    appliedResolvedMedia = true
                }
                if (switchedToAuthoritativeStreamWait) {
                    scheduleStatePersist(
                        positionMs = resolvedResumePositionMs,
                        shouldResumePlayback = true
                    )
                    return@launch
                }
                if (!appliedResolvedMedia) {
                    return@launch
                }
                maybeWarmNextYouTubeMusicAfterCurrentResolved()
            }
            SongUrlResult.WaitingForAuthoritativeStream -> {
                withContext(Dispatchers.Main) {
                    stopCurrentPlaybackForListenTogetherAwaitingStream()
                }
                NPLogger.d(
                    "NERI-PlayerManager",
                    "Waiting for authoritative listen-together stream: song=${song.name}, stableKey=${song.listenTogetherStableKeyOrNull()}"
                )
                scheduleStatePersist(
                    positionMs = resolvedResumePositionMs,
                    shouldResumePlayback = true
                )
            }
            is SongUrlResult.RequiresLogin -> {
                if (
                    shouldAwaitListenTogetherSharedStreamFallback(
                        song = song,
                        localResolutionRequiresSharedStream = true
                    )
                ) {
                    withContext(Dispatchers.Main) {
                        stopCurrentPlaybackForListenTogetherAwaitingStream()
                    }
                    scheduleStatePersist(
                        positionMs = resolvedResumePositionMs,
                        shouldResumePlayback = true
                    )
                    return@launch
                }
                clearPlaybackDemandCacheKey(reason = "play_at_index_requires_login")
                NPLogger.w(
                    "NERI-PlayerManager",
                    "Requires login to play: id=${song.id}, source=${song.album}"
                )
                postPlayerEvent(
                    PlayerEvent.ShowLoginPrompt(
                        getLocalizedString(R.string.player_playback_login_required)
                    )
                )
                withContext(Dispatchers.Main) {
                    nextImpl(
                        commandSource = commandSource,
                        bypassLoudVolumeWarning = true
                    )
                }
            }
            is SongUrlResult.Failure -> {
                if (
                    shouldAwaitListenTogetherSharedStreamFallback(
                        song = song,
                        localResolutionRequiresSharedStream = true
                    )
                ) {
                    withContext(Dispatchers.Main) {
                        stopCurrentPlaybackForListenTogetherAwaitingStream()
                    }
                    scheduleStatePersist(
                        positionMs = resolvedResumePositionMs,
                        shouldResumePlayback = true
                    )
                    return@launch
                }
                clearPlaybackDemandCacheKey(reason = "play_at_index_failure")
                NPLogger.e(
                    "NERI-PlayerManager",
                    "获取播放地址失败，跳过当前歌曲: id=${song.id}, source=${song.album}"
                )
                consecutivePlayFailures++
                withContext(Dispatchers.Main) {
                    advanceAfterPlaybackFailure(
                        source = "resolve_song_url_failure",
                        commandSource = commandSource
                    )
                }
            }
        }
        } finally {
            if (
                !keepTransitionWakeLockUntilPlaybackProgress ||
                requestToken != playbackRequestToken ||
                !resumePlaybackRequested
            ) {
                PlaybackTransitionWakeLock.release(requestToken, "play_request_finished")
            } else {
                NPLogger.d(
                    "NERI-PlaybackWakeLock",
                    "keep transition wake lock until position advances: token=$requestToken"
                )
            }
        }
    }
}

private fun PlayerManager.maybeHydrateSongForPlayback(
    index: Int,
    song: SongItem,
    requestToken: Long
) {
    if (isYouTubeMusicTrack(song) && song.coverUrl.isNullOrBlank() && song.customCoverUrl.isNullOrBlank()) {
        val videoId = extractYouTubeMusicVideoId(song.mediaUri).orEmpty()
        if (videoId.isNotBlank()) {
            val thumbnailUrl = youtubeMusicThumbnailUrl(videoId)
            hydrateSongMetadata(
                originalSong = song,
                updatedSong = song.copy(
                    coverUrl = thumbnailUrl,
                    originalCoverUrl = song.originalCoverUrl ?: thumbnailUrl
                )
            )
        }
        return
    }
    if (!isLocalSong(song)) {
        return
    }

    ioScope.launch {
        val isPublishedManagedDownload = GlobalDownloadManager.hasDownloadedSongCached(song)
        // 目录和 URI 线索只决定是否做轻量文本补全，不代表下载已经完成
        val isManagedDownloadSource = isPublishedManagedDownload ||
            ManagedDownloadStorage.isLikelyManagedDownloadSongFast(application, song)
        // 已发布下载的标题和封面来自目录索引，来源线索命中时也只补侧载文本
        // 避免 TagLib 读取整段音频和内嵌歌词抢占歌词快路径
        val hydratedSong = if (isManagedDownloadSource) {
            LocalAudioImportManager.hydrateLocalSongTextMetadata(
                context = application,
                song = song,
                resolveCoverFallback = false,
                includeEmbeddedFallback = false
            )
        } else {
            LocalAudioImportManager.hydrateLocalSongMetadata(application, song)
        }
        if (hydratedSong == song) {
            return@launch
        }

        var applied = false
        withContext(Dispatchers.Main) {
            if (requestToken != playbackRequestToken) {
                return@withContext
            }
            applied = updateCurrentQueueSongs { playlist ->
                if (index !in playlist.indices || !playlist[index].sameIdentityAs(song)) {
                    return@updateCurrentQueueSongs null
                }
                playlist.toMutableList().also { it[index] = hydratedSong }
            } != null
            if (!applied) return@withContext
            if (_currentSongFlow.value?.sameIdentityAs(song) == true) {
                setCurrentSongForPlayback(hydratedSong, syncLyricon = false)
            }
        }

        if (!applied) {
            return@launch
        }

        runLocalPlaylistMutationSafely("hydratePlaybackSongMetadata") {
            withContext(Dispatchers.IO) {
                localRepo.updateSongMetadata(song, hydratedSong)
            }
        }
        scheduleStatePersist()
    }
}

internal fun PlayerManager.enterPendingMediaLoad(requestedPositionMs: Long) {
    val action = resolvePendingMediaLoadEntryAction(requestedPositionMs)
    cancelPlaybackStartupWatchdog(reason = "pending_media_load")
    resetPlaybackRuntimeWatchdog(reason = "pending_media_load")
    clearActivePlaybackCandidates()
    pendingMediaLoadActive = true
    pendingMediaLoadPositionMs = action.positionMs
    if (action.stopProgressUpdates) stopProgressUpdates()
    cancelVolumeFade(resetToFull = true)
    if (action.stopPlayer) runCatching { player.stop() }
    if (action.clearMediaItems) runCatching { player.clearMediaItems() }
    _isPlayingFlow.value = action.isPlaying
    _playWhenReadyFlow.value = action.playWhenReady
    _playerPlaybackStateFlow.value = action.playbackState
    _playbackPositionMs.value = action.positionMs
}

private fun PlayerManager.maybeWarmNextYouTubeMusicAfterCurrentResolved() {
    val currentSong = _currentSongFlow.value ?: return
    if (!isYouTubeMusicTrack(currentSong) || currentMediaUrlResolvedAtMs <= 0) {
        return
    }
    val nextStartIndex = currentIndex + 1
    if (nextStartIndex !in currentPlaylist.indices) {
        return
    }
    prefetchYouTubeQueueWindow(
        playlist = currentPlaylist,
        startIndex = nextStartIndex,
        source = "after_current_resolved"
    )
}

internal fun PlayerManager.playBiliVideoPartsImpl(
    videoInfo: VideoBasicInfo,
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
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
    bypassLoudVolumeWarning: Boolean = false,
    allowFadeIn: Boolean = true
) {
    ensureInitialized()
    if (!initialized) return
    if (commandSource == PlaybackCommandSource.LOCAL && requestListenTogetherSafetyPauseResume()) {
        return
    }
    if (commandSource == PlaybackCommandSource.LOCAL && shouldBlockLocalRoomControl(commandSource)) return
    if (requestUsbExclusiveLoudPlaybackConfirmation(
            commandSource = commandSource,
            bypassWarning = bypassLoudVolumeWarning,
            continuePlayback = {
                playImpl(
                    commandSource = commandSource,
                    bypassLoudVolumeWarning = true,
                    allowFadeIn = allowFadeIn
                )
            }
        )
    ) {
        return
    }
    if (isPendingMediaLoadActive() && playJob?.isActive == true) {
        val action = resolvePendingPlayAction(pendingLoadActive = true)
        cancelPendingPauseRequest(resetVolumeToFull = true)
        suppressAutoResumeForCurrentSession = false
        updateResumePlaybackRequested(action.resumePlaybackRequested)
        scheduleStatePersist(
            positionMs = _playbackPositionMs.value,
            shouldResumePlayback = true
        )
        emitPlaybackCommand(
            type = "PLAY",
            source = commandSource,
            positionMs = _playbackPositionMs.value,
            currentIndex = currentIndex
        )
        return
    }
    val resumeVolumeFromPendingPause = if (
        pendingPauseJob?.isActive == true &&
        isPlayerInitialized()
    ) {
        runCatching { player.volume.coerceIn(0f, 1f) }.getOrNull()
    } else {
        null
    }
    cancelPendingPauseRequest(resetVolumeToFull = resumeVolumeFromPendingPause == null)
    suppressAutoResumeForCurrentSession = false
    updateResumePlaybackRequested(true)
    if (!usbExclusivePlaybackEnabled) {
        clearUsbExclusiveInterruptedPlaybackIntent("manual_play")
    }
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
            startPlayerPlaybackWithFade(
                if (allowFadeIn) {
                    resolvePlaybackContinuationStartPlan(
                        plan = resolveCurrentPlaybackStartPlan(),
                        currentVolume = resumeVolumeFromPendingPause
                    )
                } else {
                    resolveNoFadePlaybackStartPlan()
                }
            )
            val resumePositionMs = player.currentPosition.coerceAtLeast(0L)
            _playbackPositionMs.value = resumePositionMs
            resetPlaybackProgressAdvanceBaseline(resumePositionMs)
            startProgressUpdates()
            schedulePlaybackStartupWatchdog(reason = "manual_resume_prepared")
            scheduleStatePersist(
                positionMs = resumePositionMs,
                shouldResumePlayback = true
            )
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
                forceStartupProtectionFade = manualResumeDecision.forceStartupProtectionFade,
                startPlanOverride = if (allowFadeIn) null else resolveNoFadePlaybackStartPlan()
            )
            emitPlaybackCommand(
                type = "PLAY",
                source = commandSource,
                positionMs = manualResumeDecision.resumePositionMs,
                currentIndex = currentIndex
            )
        }
        currentPlaylist.isNotEmpty() -> {
            playAtIndex(
                index = 0,
                commandSource = commandSource,
                startPlanOverride = if (allowFadeIn) null else resolveNoFadePlaybackStartPlan()
            )
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
    val currentKey = currentTrackEndKey()
    if (!admitTrackEnd(source, currentKey)) return
    NPLogger.d(
        "NERI-PlayerManager",
        "开始处理曲目结束事件: source=$source, key=$currentKey, index=$currentIndex, queueSize=${currentPlaylist.size}"
    )
    playbackStatsOwner.onTrackEnded(writesEnabled = initialized)
    handleTrackEnded()
}

private fun PlayerManager.currentTrackEndKey(): String =
    trackEndKeyForSong(player.currentMediaItem?.mediaId, _currentSongFlow.value)

private fun PlayerManager.admitTrackEnd(source: String, currentKey: String): Boolean {
    if (!acceptTrackEndIdentity(source, currentKey)) return false
    return acceptTrackEndTiming(source, currentKey)
}

private fun PlayerManager.acceptTrackEndIdentity(source: String, currentKey: String): Boolean {
    if (isDuplicateTrackEnd(currentKey)) {
        NPLogger.d(
            "NERI-PlayerManager",
            "忽略重复的曲目结束事件: source=$source, key=$currentKey"
        )
        return false
    }
    return true
}

private fun PlayerManager.isDuplicateTrackEnd(currentKey: String): Boolean =
    shouldSkipDuplicateTrackEnd(
        repeatOne = repeatModeSetting == Player.REPEAT_MODE_ONE,
        lastHandledKey = lastHandledTrackEndKey,
        currentKey = currentKey
    )

private fun PlayerManager.acceptTrackEndTiming(source: String, currentKey: String): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (now - lastTrackEndHandledAtMs < 500L) {
        NPLogger.d(
            "NERI-PlayerManager",
            "忽略过近的曲目结束事件: source=$source, key=$currentKey, delta=${now - lastTrackEndHandledAtMs}ms"
        )
        return false
    }
    lastHandledTrackEndKey = currentKey
    lastTrackEndHandledAtMs = now
    return true
}

internal fun PlayerManager.pauseImpl(
    forcePersist: Boolean = false,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
    allowFadeOut: Boolean = true,
    preserveMutedVolume: Boolean = false,
    debugReason: String = "pause_internal",
    flushPlayerOutput: Boolean = false,
) {
    ensureInitialized()
    if (!initialized) return
    val internalUsbTransition = debugReason.startsWith("usb_toggle_")
    if (!internalUsbTransition && shouldBlockLocalRoomControl(commandSource)) return
    clearRestoredPlayback()
    if (isPendingMediaLoadActive()) {
        val action = resolvePendingPauseAction(
            pendingLoadActive = true,
            exposedPositionMs = _playbackPositionMs.value
        )
        cancelPlaybackStartupWatchdog(reason = debugReason)
        cancelPendingPauseRequest(resetVolumeToFull = true)
        updateResumePlaybackRequested(action.resumePlaybackRequested)
        if (!action.resumePlaybackRequested) {
            PlaybackTransitionWakeLock.release(
                playbackRequestToken,
                "pending_pause:$debugReason"
            )
        }
        if (!internalUsbTransition) {
            clearUsbExclusiveInterruptedPlaybackIntent("pending_pause:$debugReason")
        }
        playbackRequestToken += 1
        playJob?.cancel()
        playJob = null
        pendingMediaLoadActive = false
        pendingMediaLoadPositionMs = action.persistPositionMs
        _playWhenReadyFlow.value = action.resumePlaybackAfterLoad
        _isPlayingFlow.value = false
        if (flushPlayerOutput) {
            runCatching {
                player.playWhenReady = false
                player.stop()
            }
            _playerPlaybackStateFlow.value = Player.STATE_IDLE
        }
        if (lyriconEnabled) {
            LyriconManager.setPlaybackState(false)
        }
        clearAudioRouteMuteSuppression(
            reason = debugReason,
            preserveExplicitRestore = shouldDeferAudioRouteMuteRestore(
                audioRouteMuteRequiresExplicitRestore
            )
        )
        persistPausedPlaybackState(
            forcePersist = forcePersist,
            positionMs = action.persistPositionMs,
            shouldResumePlayback = action.persistShouldResumePlayback,
            reason = debugReason
        )
        emitPlaybackCommand(
            type = "PAUSE",
            source = commandSource,
            positionMs = action.persistPositionMs,
            currentIndex = currentIndex
        )
        return
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "pause requested: forcePersist=$forcePersist, source=$commandSource, allowFadeOut=$allowFadeOut, preserveMutedVolume=$preserveMutedVolume, reason=$debugReason, currentSong=${_currentSongFlow.value?.name}, isPlaying=${player.isPlaying}, playWhenReady=${player.playWhenReady}, stack=[${debugStackHint()}]"
    )
    cancelPendingPauseRequest()
    cancelPlaybackStartupWatchdog(reason = debugReason)
    updateResumePlaybackRequested(false)
    PlaybackTransitionWakeLock.release(playbackRequestToken, "pause:$debugReason")
    if (!internalUsbTransition) {
        clearUsbExclusiveInterruptedPlaybackIntent("pause:$debugReason")
    }
    playbackRequestToken += 1
    playJob?.cancel()
    playJob = null
    val effectiveAllowFadeOut = allowFadeOut && !shouldBypassUsbExclusivePauseFade(debugReason)
    val pauseVolumePlan = resolvePauseVolumePlan(
        allowFadeOut = effectiveAllowFadeOut,
        preserveMutedVolume = preserveMutedVolume,
        playbackFadeInEnabled = playbackFadeInEnabled,
        playbackFadeOutDurationMs = playbackFadeOutDurationMs,
        isPlayerInitialized = isPlayerInitialized()
    )
    if (pauseVolumePlan.shouldFadeOut) {
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
                pauseInternal(
                    forcePersist = forcePersist,
                    resetVolumeBeforePause = pauseVolumePlan.resetVolumeBeforePause,
                    restoreVolumeAfterPause = pauseVolumePlan.restoreVolumeAfterPause,
                    debugReason = debugReason,
                    flushPlayerOutput = flushPlayerOutput,
                )
            } finally {
                if (pendingPauseJob === scheduledPauseJob) {
                    pendingPauseJob = null
                }
            }
        }
        pendingPauseJob = scheduledPauseJob
    } else {
        pauseInternal(
            forcePersist = forcePersist,
            resetVolumeBeforePause = pauseVolumePlan.resetVolumeBeforePause,
            restoreVolumeAfterPause = pauseVolumePlan.restoreVolumeAfterPause,
            debugReason = debugReason,
            flushPlayerOutput = flushPlayerOutput,
        )
    }
    emitPlaybackCommand(
        type = "PAUSE",
        source = commandSource,
        positionMs = _playbackPositionMs.value,
        currentIndex = currentIndex
    )
}

private fun PlayerManager.shouldBypassUsbExclusivePauseFade(debugReason: String): Boolean {
    if (!usbExclusivePlaybackEnabled && !debugReason.contains("usb", ignoreCase = true)) {
        return false
    }
    val pathState = UsbExclusiveAudioPathTracker.state.value
    return pathState.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB ||
        pathState.fallbackReason?.contains("native", ignoreCase = true) == true ||
        pathState.fallbackReason?.contains("usb", ignoreCase = true) == true ||
        debugReason.contains("usb", ignoreCase = true)
}

private fun PlayerManager.pauseInternal(
    forcePersist: Boolean,
    resetVolumeBeforePause: Boolean,
    restoreVolumeAfterPause: Boolean,
    debugReason: String,
    flushPlayerOutput: Boolean,
) {
    pendingPauseJob = null
    updateResumePlaybackRequested(false)
    val currentSong = _currentSongFlow.value
    val currentPosition = player.currentPosition.coerceAtLeast(0L)
    val expectedDuration = expectedPauseDuration(currentSong)
    playbackRequestToken += 1
    playJob?.cancel()
    playJob = null
    cancelVolumeFade(resetToFull = resetVolumeBeforePause)
    val stackHint = Throwable().stackTrace.take(6).joinToString(" <- ") {
        "${it.fileName}:${it.lineNumber}"
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "pauseInternal: reason=$debugReason, song=${currentSongNameForProgressLog()}, positionMs=$currentPosition, state=${playbackStateName(player.playbackState)}, playWhenReady=${player.playWhenReady}, forcePersist=$forcePersist, resetVolumeBeforePause=$resetVolumeBeforePause, restoreVolumeAfterPause=$restoreVolumeAfterPause, stack=[$stackHint]"
    )
    player.playWhenReady = false
    pausePlayerOutput(flushPlayerOutput)
    pauseLyriconIfEnabled()
    syncPlaybackStatsPlayingState(
        playing = false,
        reason = debugReason
    )
    flushShortLocalSongIfNeeded(currentSong, expectedDuration, currentPosition)
    restorePauseVolumeIfNeeded(restoreVolumeAfterPause)
    clearAudioRouteMuteSuppression(
        reason = debugReason,
        preserveExplicitRestore = shouldDeferAudioRouteMuteRestore(
            audioRouteMuteRequiresExplicitRestore
        )
    )
    persistLongFormPlaybackProgress(
        song = currentSong,
        positionMs = currentPosition,
        durationMs = maxOf(
            expectedDuration.coerceAtLeast(0L),
            playbackDurationFlow.value
        )
    )
    persistPausedPlaybackState(
        forcePersist = forcePersist,
        positionMs = currentPosition,
        shouldResumePlayback = false,
        reason = debugReason
    )
}

private fun PlayerManager.expectedPauseDuration(song: SongItem?): Long =
    playbackProgressOwner.resolveExpectedPauseDuration(song?.durationMs, player.duration)

private fun PlayerManager.pausePlayerOutput(flushPlayerOutput: Boolean) {
    if (flushPlayerOutput) {
        player.stop()
        _playerPlaybackStateFlow.value = Player.STATE_IDLE
        stopProgressUpdates()
    } else {
        player.pause()
    }
}

private fun PlayerManager.pauseLyriconIfEnabled() {
    if (lyriconEnabled) LyriconManager.setPlaybackState(false)
}

private fun PlayerManager.flushShortLocalSongIfNeeded(
    song: SongItem?, expectedDuration: Long, currentPosition: Long
) {
    if (song == null) return
    flushLocalShortSongIfNeeded(song, expectedDuration, currentPosition)
}

private fun PlayerManager.flushLocalShortSongIfNeeded(
    song: SongItem, expectedDuration: Long, currentPosition: Long
) {
    if (!isLocalSong(song)) return
    flushShortSongIfNeeded(expectedDuration, currentPosition)
}

private fun PlayerManager.flushShortSongIfNeeded(expectedDuration: Long, currentPosition: Long) {
    if (!playbackProgressOwner.shouldFlushShortLocalSong(expectedDuration)) return
    runCatching { player.seekTo(currentPosition.coerceAtMost(expectedDuration.coerceAtLeast(0L))) }
    _playbackPositionMs.value = currentPosition
}

private fun PlayerManager.restorePauseVolumeIfNeeded(restoreVolumeAfterPause: Boolean) {
    if (!restoreVolumeAfterPause) return
    restoreUnmutedPauseVolume()
}

private fun PlayerManager.restoreUnmutedPauseVolume() {
    if (isAudioRouteMuteSuppressed()) return
    runPlayerActionOnMainThread {
        if (isPlayerInitialized()) player.volume = 1f
    }
}

internal fun PlayerManager.togglePlayPauseImpl(allowFade: Boolean = true) {
    ensureInitialized()
    if (!initialized) return
    if (isAudioRouteMuteSuppressed()) {
        restoreAudioRouteMuteImpl()
        return
    }
    if (shouldPausePlaybackWhenToggling(
            resumePlaybackRequested = resumePlaybackRequested,
            pendingPauseJobActive = pendingPauseJob?.isActive == true,
            playerIsPlaying = player.isPlaying,
            playerPlayWhenReady = player.playWhenReady,
            playJobActive = playJob?.isActive == true
        )
    ) {
        pauseImpl(
            allowFadeOut = allowFade,
            debugReason = if (allowFade) "toggle_play_pause" else "skip_interval_editor"
        )
    } else {
        playImpl(allowFadeIn = allowFade)
    }
}

internal fun PlayerManager.seekToImpl(
    positionMs: Long,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
) {
    ensureInitialized()
    if (!acceptSeekCommand(commandSource)) return
    val resolvedPositionMs = positionMs.coerceAtLeast(0L)
    playbackPositionGeneration += 1L
    logSeekRequested(resolvedPositionMs, commandSource)
    val preparedSeek = preparePlaybackSeek(resolvedPositionMs)
    applyPlaybackSeek(resolvedPositionMs, commandSource, preparedSeek)
    refreshPlaybackUrlAfterSeek(resolvedPositionMs, commandSource, preparedSeek)
}

private fun PlayerManager.logSeekRequested(positionMs: Long, commandSource: PlaybackCommandSource) {
    NPLogger.d(
        "NERI-PlayerManager",
        "seekTo requested: positionMs=$positionMs, source=$commandSource, currentSong=${_currentSongFlow.value?.name}, currentUrl=${_currentMediaUrl.value}, stack=[${debugStackHint()}]"
    )
}

private fun PlayerManager.acceptSeekCommand(commandSource: PlaybackCommandSource): Boolean {
    if (!initialized) return false
    return allowsLocalRoomSeek(commandSource)
}

private fun PlayerManager.allowsLocalRoomSeek(commandSource: PlaybackCommandSource): Boolean =
    !shouldBlockLocalRoomControl(commandSource)

private data class PreparedPlaybackSeek(
    val song: SongItem?,
    val durationMs: Long,
    val expeditedYouTubeRecovery: Boolean,
    val executionAction: SeekExecutionAction,
    val pendingAction: PendingSeekAction
)

private fun PlayerManager.preparePlaybackSeek(positionMs: Long): PreparedPlaybackSeek {
    val currentSong = _currentSongFlow.value
    val currentUrl = _currentMediaUrl.value
    val currentPositionMs = player.currentPosition.coerceAtLeast(0L)
    val knownDurationMs = knownSeekDuration(currentSong)
    val shouldExpediteYouTubeSeekRecovery =
        YouTubeSeekRefreshPolicy.shouldUseExpeditedRecoveryAfterSeek(
            song = currentSong,
            currentUrl = currentUrl,
            previousPositionMs = currentPositionMs,
            targetPositionMs = positionMs,
            durationMs = knownDurationMs
        )
    val shouldRefreshYouTubeUrlBeforeSeek = shouldRefreshYouTubeUrlForSeek(
        currentSong,
        currentUrl,
        shouldExpediteYouTubeSeekRecovery
    )
    val pendingLoadActive = isPendingMediaLoadActive()
    // 正在装载新媒体时交给现有 pending-load 流程，避免替旧媒体启动一条并行刷新
    val seekExecutionAction = resolveSeekExecutionAction(
        pendingLoadActive = pendingLoadActive,
        urlRefreshRequested = shouldRefreshYouTubeUrlBeforeSeek
    )
    rememberYouTubeSeekPosition(
        positionMs,
        shouldRefreshYouTubeUrlBeforeSeek,
        shouldExpediteYouTubeSeekRecovery
    )
    val pendingSeekAction = resolvePendingSeekAction(
        pendingLoadActive = pendingLoadActive,
        requestedPositionMs = positionMs
    )
    applyPendingSeekPosition(pendingSeekAction)
    return PreparedPlaybackSeek(
        song = currentSong,
        durationMs = knownDurationMs,
        expeditedYouTubeRecovery = shouldExpediteYouTubeSeekRecovery,
        executionAction = seekExecutionAction,
        pendingAction = pendingSeekAction
    )
}

private fun shouldRefreshYouTubeUrlForSeek(
    song: SongItem?,
    currentUrl: String?,
    expeditedRecovery: Boolean
): Boolean {
    if (expeditedRecovery) return true
    return YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(song, currentUrl)
}

private fun PlayerManager.knownSeekDuration(song: SongItem?): Long =
    maxOf(player.duration, song?.durationMs ?: 0L).coerceAtLeast(0L)

private fun PlayerManager.rememberYouTubeSeekPosition(
    positionMs: Long,
    refreshUrl: Boolean,
    expeditedRecovery: Boolean
) {
    if (refreshUrl) {
        rememberPendingSeekPosition(positionMs)
        playbackProgressOwner.setExpeditedYouTubeSeekRecoveryPending(expeditedRecovery)
    } else {
        clearPendingSeekPosition()
    }
}

private fun PlayerManager.applyPendingSeekPosition(pendingSeekAction: PendingSeekAction) {
    pendingSeekAction.pendingSeekPositionMs?.let(::rememberPendingSeekPosition)
    pendingMediaLoadPositionMs = pendingSeekAction.exposedPositionMs
}

private fun PlayerManager.applyPlaybackSeek(
    positionMs: Long,
    commandSource: PlaybackCommandSource,
    preparedSeek: PreparedPlaybackSeek
) {
    seekPlayerIfReady(positionMs, preparedSeek.executionAction)
    syncSeekOutputs(positionMs)
    persistLongFormPlaybackProgress(
        song = preparedSeek.song,
        positionMs = positionMs,
        durationMs = preparedSeek.durationMs
    )
    scheduleStatePersist(
        positionMs = preparedSeek.pendingAction.persistPositionMs,
        shouldResumePlayback = shouldResumePlaybackSnapshot()
    )
    emitPlaybackCommand(
        type = "SEEK",
        source = commandSource,
        positionMs = positionMs,
        currentIndex = currentIndex
    )
}

private fun PlayerManager.seekPlayerIfReady(positionMs: Long, action: SeekExecutionAction) {
    if (action.seekPlayerNow) player.seekTo(positionMs)
}

private fun PlayerManager.syncSeekOutputs(positionMs: Long) {
    if (lyriconEnabled) {
        LyriconManager.setPosition(positionMs)
    }
    updateExternalBluetoothLyricLine(positionMs)
    playbackStatsOwner.onManualSeek(positionMs)
    _playbackPositionMs.value = positionMs
}

private fun PlayerManager.refreshPlaybackUrlAfterSeek(
    positionMs: Long,
    commandSource: PlaybackCommandSource,
    preparedSeek: PreparedPlaybackSeek
) {
    if (preparedSeek.executionAction.refreshUrlInBackground) {
        refreshCurrentSongUrl(
            resumePositionMs = positionMs,
            allowFallback = false,
            reason = seekRefreshReason(preparedSeek.expeditedYouTubeRecovery),
            bypassCooldown = true,
            resumePlaybackAfterRefresh = shouldResumePlaybackSnapshot(),
            resumedPlaybackCommandSource = commandSource,
            youtubeRecoveryStrategy = youtubePlaybackRecoveryStrategyForSeek()
        )
    }
}

private fun seekRefreshReason(expedited: Boolean): String =
    if (expedited) "youtube_seek_expedited_url_refresh" else "youtube_seek_url_refresh"

private fun PlayerManager.shouldStartProgressUpdatesNow(): Boolean {
    if (isProgressJobRunning()) return false
    return shouldRunPlaybackProgressUpdates(
        initialized = initialized,
        pendingMediaLoad = isPendingMediaLoadActive(),
        hasMediaItem = hasProgressMediaItem(),
        isPlaying = player.isPlaying,
        playWhenReady = player.playWhenReady
    )
}

private fun PlayerManager.isProgressJobRunning(): Boolean =
    true.equals(progressJob?.isActive)

private fun PlayerManager.hasProgressMediaItem(): Boolean = player.currentMediaItem != null

internal fun PlayerManager.startProgressUpdates() {
    if (!shouldStartProgressUpdatesNow()) return
    NPLogger.d(
        "NERI-PlayerManager",
        "startProgressUpdates: currentSong=${currentSongNameForProgressLog()}, playbackState=${playbackStateName(player.playbackState)}"
    )
    playbackProgressOwner.resetStatsClock()
    progressJob = mainScope.launch {
        while (isActive) {
            val intervalMs = progressUpdateIntervalMs()
            runProgressUpdateTick()
            delay(intervalMs.milliseconds)
        }
    }
}

private fun PlayerManager.progressUpdateIntervalMs(): Long =
    resolvePlaybackProgressUpdateIntervalMs(
        playbackProgressAdvanceReported = playbackProgressAdvanceReported,
        interactiveNowPlayingVisible = interactiveNowPlayingVisible,
        realtimeExternalLyricsActive = isExternalBluetoothLyricCadenceActive()
    )

private fun PlayerManager.runProgressUpdateTick() {
    val positionMs = readProgressPosition() ?: return
    publishProgressPosition(positionMs)
}

private fun PlayerManager.readProgressPosition(): Long? =
    try {
        resolveDisplayedPlaybackPosition(player.currentPosition.coerceAtLeast(0L))
    } catch (error: Throwable) {
        logProgressReadFailure(error)
        null
    }

private fun PlayerManager.logProgressReadFailure(error: Throwable) {
    NPLogger.w("NERI-PlayerManager", "progress update read failed for ${currentSongNameForProgressLog()}", error)
}

private fun PlayerManager.publishProgressPosition(positionMs: Long) {
    _playbackPositionMs.value = positionMs
    reportFirstProgressAdvanceIfNeeded()
    reportRuntimeProgressIfAdvanced()
    val durationMs = readProgressDuration()
    if (applyProgressAutoSkipIfNeeded(positionMs, durationMs)) return
    updateLyriconProgressIfEnabled(positionMs, durationMs)
    updateExternalBluetoothLyricLine(positionMs)
    maybePersistPlaybackProgress(positionMs)
    maybePersistLongFormPlaybackProgress(positionMs)
    maybeReportProgressStats(positionMs)
}

private fun PlayerManager.reportFirstProgressAdvanceIfNeeded() {
    if (playbackProgressAdvanceReported) return
    reportFirstProgressAdvanceIfDetected()
}

private fun PlayerManager.reportFirstProgressAdvanceIfDetected() {
    if (!isPlaybackActuallyAdvancing()) return
    playbackProgressAdvanceReported = true
    startupStallRecoveryAttempts = 0
    cancelPlaybackStartupWatchdog(reason = "position_advanced")
    PlaybackTransitionWakeLock.release(playbackRequestToken, "position_advanced")
    recordPlaybackRuntimeProgress(player.currentPosition)
    schedulePlaybackRuntimeWatchdog(reason = "position_advanced")
    syncPlaybackStatsPlayingState(playing = true, reason = "progress_position_advanced")
}

private fun PlayerManager.reportRuntimeProgressIfAdvanced() {
    if (!playbackProgressAdvanceReported) return
    recordPlaybackRuntimeProgress(player.currentPosition)
    schedulePlaybackRuntimeWatchdog(reason = "progress_tick")
}

private fun PlayerManager.readProgressDuration(): Long {
    val durationMs = readPlayerDurationOrFallback()
    if (durationMs > 0L) playbackProgressOwner.setDuration(durationMs)
    return durationMs
}

private fun PlayerManager.readPlayerDurationOrFallback(): Long =
    try {
        player.duration.coerceAtLeast(0L)
    } catch (_: Throwable) {
        playbackDurationFlow.value
    }

private fun PlayerManager.applyProgressAutoSkipIfNeeded(positionMs: Long, durationMs: Long): Boolean {
    val skip = playbackAutoSkipPolicy.resolve(
        song = _currentSongFlow.value,
        positionMs = positionMs,
        durationMs = durationMs,
        listenTogetherActive = isListenTogetherActive()
    ) ?: return false
    applyPlaybackAutoSkip(skip, positionMs)
    return true
}

private fun PlayerManager.updateLyriconProgressIfEnabled(positionMs: Long, durationMs: Long) {
    if (!lyriconEnabled) return
    LyriconManager.setPlaybackSpeed(playbackSoundConfig.speed)
    // 与高级歌词同源: 进度环原始媒体位置, 显示 lead 在 LyriconManager 内处理
    LyriconManager.setPosition(
        mediaLyriconPositionMs(positionMs = positionMs, durationMs = durationMs)
    )
}

private fun PlayerManager.maybeReportProgressStats(positionMs: Long) {
    if (!playbackProgressOwner.shouldRecordStats(PLAYBACK_PROGRESS_STATS_UPDATE_INTERVAL_MS)) return
    reportProgressStats(positionMs)
}

private fun PlayerManager.reportProgressStats(positionMs: Long) {
    if (playbackStatsOwner.onProgress(positionMs, writesEnabled = initialized)) {
        markTrackEndHandledForStatsFallback()
    }
    maybePersistPlaybackStatsProgress()
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
    resetPlaybackRuntimeWatchdog(reason = "progress_updates_stopped")
}

private fun PlayerManager.applyPlaybackAutoSkip(skip: PlaybackAutoSkipDecision, positionMs: Long) {
    NPLogger.d(skip.logTag, "${skip.logAction}: from=${positionMs}ms, to=${skip.positionMs}ms")
    showPlaybackAutoSkipPrompt(skip.source)
    seekTo(positionMs = skip.positionMs, commandSource = PlaybackCommandSource.LOCAL_SAFETY)
    AudioPlayerService.refreshPlaybackWidgetAfterSeekFromActiveService(reason = skip.widgetReason)
}

private fun PlayerManager.showPlaybackAutoSkipPrompt(source: BiliSkipSegmentSource) {
    resolveBiliSkipSegmentPromptMessageRes(
        promptsEnabled = biliSkipSegmentPromptEnabled,
        source = source
    )?.let { messageRes ->
        AppFeedback.showToast(context = application, message = getLocalizedString(messageRes))
    }
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
    scheduleStatePersist(positionMs = positionMs, shouldResumePlayback = true)
}

private fun PlayerManager.maybePersistLongFormPlaybackProgress(positionMs: Long) {
    playbackProgressOwner.persistPeriodicLongFormProgress(positionMs, STATE_PERSIST_INTERVAL_MS)
}

private fun PlayerManager.maybePersistPlaybackStatsProgress() {
    playbackStatsOwner.flushPeriodic(writesEnabled = initialized)
}

internal fun PlayerManager.stopPlaybackPreservingQueueImpl(clearMediaUrl: Boolean = false) {
    logQueueStopStart(clearMediaUrl)
    cancelPendingPauseRequest(resetVolumeToFull = true)
    clearPlaybackDemandCacheKey(reason = "stop_playback_preserving_queue")
    playbackRequestToken += 1
    cancelActivePlayForQueueStop()
    pendingMediaLoadActive = false
    cancelPlaybackStartupWatchdog(reason = "stop_playback_preserving_queue")
    clearActivePlaybackCandidates()
    cancelPrefetchForQueueStop()
    lastHandledTrackEndKey = null
    updateResumePlaybackRequested(false)
    PlaybackTransitionWakeLock.release(
        playbackRequestToken,
        "stop_playback_preserving_queue"
    )
    playbackTransportOwner.resetForRelease()
    stopProgressUpdates()
    cancelVolumeFade(resetToFull = true)
    clearAudioRouteMuteSuppression(reason = "stop_playback_preserving_queue")
    persistCurrentLongFormPlaybackProgress()
    syncPlaybackStatsPlayingState(
        playing = false,
        reason = "stop_playback_preserving_queue"
    )
    stopPlayerForQueuePreservation()
    restoreQueueAfterStop(clearMediaUrl)
    consecutivePlayFailures = 0
    logQueueStopComplete()
    scheduleStatePersist()
}

private fun PlayerManager.logQueueStopStart(clearMediaUrl: Boolean) {
    NPLogger.d(
        "NERI-PlayerManager",
        "stopPlaybackPreservingQueue(): clearMediaUrl=$clearMediaUrl, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, currentSong=${currentSongNameForProgressLog()}, mediaUrlPresent=${hasQueueStopMediaUrl()}, stack=[${debugStackHint()}]"
    )
}

private fun PlayerManager.cancelActivePlayForQueueStop() {
    playJob?.cancel()
    playJob = null
}

private fun PlayerManager.cancelPrefetchForQueueStop() {
    currentYouTubePrefetchJob?.cancel()
    currentYouTubePrefetchJob = null
    currentYouTubePrefetchVideoIds = emptySet()
}

private fun PlayerManager.stopPlayerForQueuePreservation() {
    runCatching { player.stop() }
    runCatching { player.clearMediaItems() }
    _isPlayingFlow.value = false
    pauseLyriconIfEnabled()
    _playWhenReadyFlow.value = false
    _playerPlaybackStateFlow.value = Player.STATE_IDLE
    clearPendingSeekPosition()
    _playbackPositionMs.value = 0L
}

private fun PlayerManager.restoreQueueAfterStop(clearMediaUrl: Boolean) {
    if (currentPlaylist.isEmpty()) clearEmptyQueueAfterStop()
    else restoreNonemptyQueueAfterStop(clearMediaUrl)
}

private fun PlayerManager.clearEmptyQueueAfterStop() {
    clearRestoredPlayback()
    currentIndex = -1
    setCurrentSongForPlayback(null)
    clearQueueStopMediaUrl()
}

private fun PlayerManager.restoreNonemptyQueueAfterStop(clearMediaUrl: Boolean) {
    currentIndex = currentIndex.coerceIn(0, currentPlaylist.lastIndex)
    setCurrentSongForPlayback(currentPlaylist.getOrNull(currentIndex))
    if (clearMediaUrl) clearQueueStopMediaUrl()
}

private fun PlayerManager.clearQueueStopMediaUrl() {
    _currentMediaUrl.value = null
    _currentPlaybackAudioInfo.value = null
    currentMediaUrlResolvedAtMs = 0L
}

private fun PlayerManager.logQueueStopComplete() {
    NPLogger.d(
        "NERI-PlayerManager",
        "stopPlaybackPreservingQueue(): completed, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, retainedSong=${currentSongNameForProgressLog()}, mediaUrlPresent=${hasQueueStopMediaUrl()}"
    )
}

private fun PlayerManager.currentSongNameForProgressLog(): String? = _currentSongFlow.value?.name

private fun PlayerManager.hasQueueStopMediaUrl(): Boolean = hasNonBlankMediaUrl(_currentMediaUrl.value)

internal fun hasNonBlankMediaUrl(url: String?): Boolean = !url.isNullOrBlank()

internal fun PlayerManager.stopPlaybackImmediatelyImpl(
    reason: String,
    forcePersist: Boolean = true
) {
    pauseImpl(
        forcePersist = forcePersist,
        commandSource = PlaybackCommandSource.LOCAL_SAFETY,
        allowFadeOut = false,
        debugReason = reason,
        flushPlayerOutput = true,
    )
}
