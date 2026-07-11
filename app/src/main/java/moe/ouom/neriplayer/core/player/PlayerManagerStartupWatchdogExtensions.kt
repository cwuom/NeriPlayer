@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.debug.playbackStateName
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlaybackUrlCandidate
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.core.player.policy.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.hasPlaybackProgressAdvancedSinceBaseline
import moe.ouom.neriplayer.core.player.policy.resolvePlaybackStartPlan
import moe.ouom.neriplayer.util.NPLogger

internal fun PlayerManager.configureActivePlaybackCandidates(
    result: SongUrlResult.Success,
    resumePositionMs: Long,
    commandSource: PlaybackCommandSource,
    resetRecoveryAttempts: Boolean = true
) {
    activePlaybackCandidates = result.playbackCandidates()
    activePlaybackUrlIndex = 0
    activePlaybackResumePositionMs = resumePositionMs.coerceAtLeast(0L)
    activePlaybackCommandSource = commandSource
    if (resetRecoveryAttempts) {
        startupStallRecoveryAttempts = 0
    }
    resetPlaybackProgressAdvanceBaseline(activePlaybackResumePositionMs)
}

internal fun PlayerManager.clearActivePlaybackCandidates() {
    activePlaybackCandidates = emptyList()
    activePlaybackUrlIndex = 0
    activePlaybackResumePositionMs = 0L
    activePlaybackCommandSource = PlaybackCommandSource.LOCAL
    startupStallRecoveryAttempts = 0
    resetPlaybackProgressAdvanceBaseline(0L)
}

internal fun PlayerManager.currentPlaybackCandidate(): PlaybackUrlCandidate? {
    return activePlaybackCandidates.getOrNull(activePlaybackUrlIndex)
}

internal fun PlayerManager.resetPlaybackProgressAdvanceBaseline(positionMs: Long) {
    playbackProgressBaselinePositionMs = positionMs.coerceAtLeast(0L)
    playbackProgressAdvanceReported = false
}

internal fun PlayerManager.schedulePlaybackStartupWatchdog(reason: String) {
    if (!shouldWatchPlaybackStartup()) return
    val timeoutMs = startupWatchdogTimeoutMs()
    val requestToken = playbackRequestToken
    val watchdogToken = playbackStartupWatchdogToken + 1L
    playbackStartupWatchdogToken = watchdogToken
    playbackStartupWatchdogJob?.cancel()
    val startPositionMs = runCatching { player.currentPosition.coerceAtLeast(0L) }
        .getOrDefault(_playbackPositionMs.value.coerceAtLeast(0L))
    val startedAtMs = SystemClock.elapsedRealtime()

    playbackStartupWatchdogJob = mainScope.launch {
        delay(timeoutMs)
        if (playbackStartupWatchdogToken != watchdogToken) return@launch
        if (requestToken != playbackRequestToken) return@launch
        if (!isStartupPlaybackStalled(startPositionMs)) return@launch

        NPLogger.w(
            "NERI-PlayerManager",
            "playback startup stalled: reason=$reason, elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}, " +
                "state=${playbackStateName(player.playbackState)}, positionMs=${player.currentPosition.coerceAtLeast(0L)}, " +
                "urlIndex=$activePlaybackUrlIndex/${activePlaybackCandidates.size}, attempts=$startupStallRecoveryAttempts"
        )
        recoverPlaybackStartupStall(requestToken)
    }
}

internal fun PlayerManager.cancelPlaybackStartupWatchdog(reason: String) {
    if (playbackStartupWatchdogJob?.isActive == true) {
        NPLogger.d("NERI-PlayerManager", "cancel playback startup watchdog: reason=$reason")
    }
    playbackStartupWatchdogToken += 1L
    playbackStartupWatchdogJob?.cancel()
    playbackStartupWatchdogJob = null
}

private fun PlayerManager.shouldWatchPlaybackStartup(): Boolean {
    if (!initialized || isPendingMediaLoadActive()) return false
    if (!isPlayerInitialized()) return false
    if (player.currentMediaItem == null || !player.playWhenReady) return false
    if (_currentSongFlow.value == null) return false
    return player.playbackState == Player.STATE_BUFFERING ||
        player.playbackState == Player.STATE_READY
}

private fun PlayerManager.startupWatchdogTimeoutMs(): Long {
    val song = _currentSongFlow.value ?: return STARTUP_STALL_REMOTE_TIMEOUT_MS
    if (isLocalSong(song)) return STARTUP_STALL_LOCAL_TIMEOUT_MS
    if (isYouTubeMusicTrack(song)) return STARTUP_STALL_YOUTUBE_TIMEOUT_MS
    return STARTUP_STALL_REMOTE_TIMEOUT_MS
}

private fun PlayerManager.isStartupPlaybackStalled(startPositionMs: Long): Boolean {
    if (!shouldWatchPlaybackStartup()) return false
    val currentPositionMs = player.currentPosition.coerceAtLeast(0L)
    val advancedMs = currentPositionMs - startPositionMs.coerceAtLeast(0L)
    return advancedMs <= STARTUP_STALL_POSITION_TOLERANCE_MS
}

private fun PlayerManager.recoverPlaybackStartupStall(requestToken: Long) {
    if (requestToken != playbackRequestToken) return
    startupStallRecoveryAttempts += 1

    if (startupStallRecoveryAttempts > STARTUP_STALL_MAX_RECOVERY_ATTEMPTS) {
        consecutivePlayFailures++
        advanceAfterPlaybackFailure(source = "startup_stall")
        return
    }

    if (trySwitchToNextPlaybackCandidateForRecovery(reason = "startup_stall")) {
        return
    }

    val song = _currentSongFlow.value
    if (
        song != null &&
        !isLocalSong(song)
    ) {
        val resumePositionMs = player.currentPosition.coerceAtLeast(0L)
        refreshCurrentSongUrl(
            resumePositionMs = resumePositionMs,
            allowFallback = false,
            reason = "startup_stall_${playbackStateName(player.playbackState)}",
            bypassCooldown = true,
            fallbackSeekPositionMs = resumePositionMs,
            resumePlaybackAfterRefresh = true,
            resumedPlaybackCommandSource = activePlaybackCommandSource
        )
        return
    }

    consecutivePlayFailures++
    advanceAfterPlaybackFailure(source = "startup_stall")
}

internal fun PlayerManager.trySwitchToNextPlaybackCandidateForRecovery(reason: String): Boolean {
    val nextIndex = activePlaybackUrlIndex + 1
    val candidate = activePlaybackCandidates.getOrNull(nextIndex) ?: return false
    val requestToken = playbackRequestToken
    if (requestToken != playbackRequestToken) return false

    activePlaybackUrlIndex = nextIndex
    activePlaybackResumePositionMs = player.currentPosition.coerceAtLeast(0L)
    NPLogger.w(
        "NERI-PlayerManager",
        "switch playback candidate: reason=$reason, index=$nextIndex/${activePlaybackCandidates.size}, url=${candidate.url}"
    )
    mainScope.launch {
        applyPlaybackCandidate(
            candidate = candidate,
            resumePositionMs = activePlaybackResumePositionMs,
            requestToken = requestToken
        )
    }
    return true
}

private suspend fun PlayerManager.applyPlaybackCandidate(
    candidate: PlaybackUrlCandidate,
    resumePositionMs: Long,
    requestToken: Long
) {
    val song = _currentSongFlow.value ?: return
    val cacheKey = candidate.cacheKeyOverride ?: computeCacheKey(song)
    invalidateMismatchedCachedResource(
        cacheKey = cacheKey,
        expectedContentLength = candidate.expectedContentLength
    )
    if (requestToken != playbackRequestToken) return
    val mediaItem = buildMediaItem(song, candidate.url, cacheKey, candidate.mimeType)
    preparePlayerForManagedStart(resolvePlaybackStartPlan(shouldFadeIn = false, fadeDurationMs = 0L))
    resetTrackEndDeduplicationState()
    player.setMediaItem(mediaItem)
    loadedMediaRequestToken = requestToken
    pendingMediaLoadActive = false
    syncExoRepeatMode()
    if (resumePositionMs > 0L) {
        player.seekTo(resumePositionMs)
        _playbackPositionMs.value = resumePositionMs
    }
    resetPlaybackProgressAdvanceBaseline(resumePositionMs)
    clearPendingSeekPosition()
    _currentMediaUrl.value = candidate.url
    _currentPlaybackAudioInfo.value = candidate.audioInfo
    currentMediaUrlResolvedAtMs = SystemClock.elapsedRealtime()
    player.prepare()
    startPlayerPlaybackWithFade(resolvePlaybackStartPlan(shouldFadeIn = false, fadeDurationMs = 0L))
    startProgressUpdates()
    scheduleStatePersist(positionMs = resumePositionMs, shouldResumePlayback = true)
    schedulePlaybackStartupWatchdog(reason = "candidate_switch")
}

internal fun PlayerManager.shouldTreatReadyAtStartAsUnhealthyPrepared(): Boolean {
    if (!isPlayerInitialized()) return false
    if (player.playbackState != Player.STATE_READY) return false
    if (!player.playWhenReady || player.isPlaying) return false
    return player.currentPosition.coerceAtLeast(0L) <= STARTUP_STALL_POSITION_TOLERANCE_MS
}

internal fun PlayerManager.isPlaybackActuallyAdvancing(): Boolean {
    if (!isPlayerInitialized()) return false
    if (!player.isPlaying) return false
    return hasPlaybackProgressAdvancedSinceBaseline(
        currentPositionMs = player.currentPosition,
        baselinePositionMs = playbackProgressBaselinePositionMs,
        toleranceMs = STARTUP_STALL_POSITION_TOLERANCE_MS
    )
}
