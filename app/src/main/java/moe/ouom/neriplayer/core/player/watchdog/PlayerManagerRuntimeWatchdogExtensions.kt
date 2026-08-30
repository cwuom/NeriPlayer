@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.watchdog

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.debug.playbackStateName
import moe.ouom.neriplayer.core.player.model.PlayerEvent
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.progress.PLAYBACK_RUNTIME_STALL_MAX_RECOVERY_ATTEMPTS
import moe.ouom.neriplayer.core.player.policy.progress.PLAYBACK_RUNTIME_STALL_POLL_INTERVAL_MS
import moe.ouom.neriplayer.core.player.policy.progress.PLAYBACK_RUNTIME_STALL_TIMEOUT_MS
import moe.ouom.neriplayer.core.player.policy.progress.PlaybackRuntimeStallPolicy
import moe.ouom.neriplayer.core.player.policy.progress.RuntimePlaybackStallAction
import moe.ouom.neriplayer.core.player.playback.pauseImpl
import moe.ouom.neriplayer.core.player.policy.wake.PlaybackTransitionWakeLock

internal fun PlayerManager.resetPlaybackRuntimeWatchdog(reason: String) {
    if (playbackRuntimeWatchdogJob?.isActive == true) {
        NPLogger.d(
            "NERI-PlaybackWatchdog",
            "cancel runtime playback watchdog: reason=$reason"
        )
    }
    playbackRuntimeWatchdogToken += 1L
    playbackRuntimeWatchdogJob?.cancel()
    playbackRuntimeWatchdogJob = null
    playbackRuntimeStallRecoveryAttempts = 0
    playbackRuntimeLastProgressPositionMs = 0L
    playbackRuntimeLastProgressAtElapsedRealtimeMs = 0L
}

internal fun PlayerManager.recordPlaybackRuntimeProgress(positionMs: Long) {
    val normalizedPositionMs = positionMs.coerceAtLeast(0L)
    if (
        playbackRuntimeLastProgressAtElapsedRealtimeMs == 0L ||
        PlaybackRuntimeStallPolicy.hasPositionAdvanced(
            currentPositionMs = normalizedPositionMs,
            lastPositionMs = playbackRuntimeLastProgressPositionMs
        )
    ) {
        playbackRuntimeLastProgressPositionMs = normalizedPositionMs
        playbackRuntimeLastProgressAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
        playbackRuntimeStallRecoveryAttempts = 0
    }
}

/**
 * 为一次恢复动作留出观察窗口，但不把未确认恢复的尝试次数归零
 */
private fun PlayerManager.markPlaybackRuntimeRecoveryAttempt(positionMs: Long) {
    playbackRuntimeLastProgressPositionMs = positionMs.coerceAtLeast(0L)
    playbackRuntimeLastProgressAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
}

internal fun PlayerManager.schedulePlaybackRuntimeWatchdog(reason: String) {
    if (!shouldWatchRuntimePlayback()) return
    if (playbackRuntimeWatchdogJob?.isActive == true) return
    if (playbackRuntimeLastProgressAtElapsedRealtimeMs == 0L) {
        recordPlaybackRuntimeProgress(player.currentPosition)
    }

    val requestToken = playbackRequestToken
    val watchdogToken = playbackRuntimeWatchdogToken + 1L
    playbackRuntimeWatchdogToken = watchdogToken
    playbackRuntimeWatchdogJob = mainScope.launch {
        try {
            while (isActive) {
                delay(PLAYBACK_RUNTIME_STALL_POLL_INTERVAL_MS)
                if (watchdogToken != playbackRuntimeWatchdogToken) return@launch
                if (requestToken != playbackRequestToken) return@launch
                if (!shouldWatchRuntimePlayback()) return@launch

                val positionMs = runCatching { player.currentPosition.coerceAtLeast(0L) }
                    .getOrDefault(playbackRuntimeLastProgressPositionMs)
                if (
                    PlaybackRuntimeStallPolicy.hasPositionAdvanced(
                        currentPositionMs = positionMs,
                        lastPositionMs = playbackRuntimeLastProgressPositionMs
                    )
                ) {
                    recordPlaybackRuntimeProgress(positionMs)
                    continue
                }

                val elapsedSinceProgressMs = SystemClock.elapsedRealtime() -
                    playbackRuntimeLastProgressAtElapsedRealtimeMs
                when (
                    PlaybackRuntimeStallPolicy.decide(
                        initialized = initialized,
                        pendingMediaLoad = isPendingMediaLoadActive(),
                        hasMediaItem = player.currentMediaItem != null,
                        resumePlaybackRequested = resumePlaybackRequested,
                        playWhenReady = player.playWhenReady,
                        isPlaying = player.isPlaying,
                        playbackState = player.playbackState,
                        bufferedDurationMs = runCatching {
                            player.totalBufferedDuration
                        }.getOrDefault(0L),
                        playbackSuppressionReason = player.playbackSuppressionReason,
                        audioRouteMuteSuppressed = audioRouteMuteSuppressedFlow.value,
                        pendingPause = pendingPauseJob?.isActive == true,
                        progressAdvanceReported = playbackProgressAdvanceReported,
                        elapsedSinceProgressMs = elapsedSinceProgressMs,
                        recoveryAttempts = playbackRuntimeStallRecoveryAttempts,
                        maxRecoveryAttempts = PLAYBACK_RUNTIME_STALL_MAX_RECOVERY_ATTEMPTS,
                        timeoutMs = PLAYBACK_RUNTIME_STALL_TIMEOUT_MS
                    )
                ) {
                    RuntimePlaybackStallAction.WAIT,
                    RuntimePlaybackStallAction.IGNORE -> Unit
                    RuntimePlaybackStallAction.RECOVER -> {
                        recoverRuntimePlaybackStall(
                            requestToken = requestToken,
                            reason = reason,
                            positionMs = positionMs,
                            elapsedSinceProgressMs = elapsedSinceProgressMs
                        )
                    }
                    RuntimePlaybackStallAction.EXHAUSTED -> {
                        reportRuntimePlaybackStallExhausted(
                            requestToken = requestToken,
                            positionMs = positionMs,
                            elapsedSinceProgressMs = elapsedSinceProgressMs
                        )
                        return@launch
                    }
                }
            }
        } finally {
            if (watchdogToken == playbackRuntimeWatchdogToken) {
                playbackRuntimeWatchdogJob = null
            }
        }
    }
    NPLogger.d(
        "NERI-PlaybackWatchdog",
        "schedule runtime playback watchdog: reason=$reason, token=$requestToken, " +
            "positionMs=${playbackRuntimeLastProgressPositionMs}"
    )
}

private fun PlayerManager.shouldWatchRuntimePlayback(): Boolean {
    if (!initialized || !isPlayerInitialized() || isPendingMediaLoadActive()) return false
    if (_currentSongFlow.value == null || player.currentMediaItem == null) return false
    if (urlRefreshInProgress) return false
    if (!resumePlaybackRequested || !player.playWhenReady) return false
    if (!playbackProgressAdvanceReported) return false
    return player.playbackState == Player.STATE_READY ||
        player.playbackState == Player.STATE_BUFFERING
}

private fun PlayerManager.recoverRuntimePlaybackStall(
    requestToken: Long,
    reason: String,
    positionMs: Long,
    elapsedSinceProgressMs: Long
) {
    if (requestToken != playbackRequestToken || !shouldWatchRuntimePlayback()) return
    playbackRuntimeStallRecoveryAttempts += 1
    val attempt = playbackRuntimeStallRecoveryAttempts
    markPlaybackRuntimeRecoveryAttempt(positionMs)
    NPLogger.w(
        "NERI-PlaybackWatchdog",
        "runtime playback stall: source=$reason, attempt=$attempt, " +
            "elapsedMs=$elapsedSinceProgressMs, state=${playbackStateName(player.playbackState)}, " +
            "isPlaying=${player.isPlaying}, playWhenReady=${player.playWhenReady}, " +
            "suppression=${player.playbackSuppressionReason}, positionMs=$positionMs, " +
            "bufferedMs=${runCatching { player.totalBufferedDuration }.getOrDefault(0L)}, " +
            "requestToken=$requestToken"
    )

    if (attempt == 1) {
        runCatching {
            applyAudioFocusPolicyOnMainThread()
            player.playWhenReady = true
            player.play()
        }.onFailure { error ->
            NPLogger.w(
                "NERI-PlaybackWatchdog",
                "runtime playback soft restart failed",
                error
            )
        }
        return
    }

    if (
        trySwitchToNextPlaybackCandidateForRecovery(
            reason = "runtime_stall",
            invalidateCurrentCache = false,
            expectedRequestToken = requestToken
        )
    ) {
        return
    }

    val song = _currentSongFlow.value
    if (song != null && !isLocalSong(song)) {
        refreshCurrentSongUrl(
            resumePositionMs = positionMs,
            allowFallback = false,
            reason = "runtime_stall_${playbackStateName(player.playbackState)}",
            bypassCooldown = true,
            fallbackSeekPositionMs = positionMs,
            resumePlaybackAfterRefresh = true,
            resumedPlaybackCommandSource = activePlaybackCommandSource
        )
        return
    }

    NPLogger.w(
        "NERI-PlaybackWatchdog",
        "runtime playback stall has no alternate source: keep queue and expose retry"
    )
}

private fun PlayerManager.reportRuntimePlaybackStallExhausted(
    requestToken: Long,
    positionMs: Long,
    elapsedSinceProgressMs: Long
) {
    if (requestToken != playbackRequestToken) return
    NPLogger.e(
        "NERI-PlaybackWatchdog",
        "runtime playback recovery exhausted: attempts=$playbackRuntimeStallRecoveryAttempts, " +
            "elapsedMs=$elapsedSinceProgressMs, state=${playbackStateName(player.playbackState)}, " +
            "positionMs=$positionMs, mediaId=${player.currentMediaItem?.mediaId}"
    )
    resetPlaybackRuntimeWatchdog(reason = "recovery_exhausted")
    PlaybackTransitionWakeLock.release(requestToken, "runtime_stall_exhausted")
    postPlayerEvent(
        PlayerEvent.ShowError(
            getLocalizedString(R.string.player_playback_failed_with_code, "RUNTIME_STALL")
        )
    )
    pauseImpl(
        forcePersist = true,
        commandSource = PlaybackCommandSource.LOCAL_SAFETY,
        allowFadeOut = false,
        debugReason = "runtime_stall_exhausted",
        flushPlayerOutput = true
    )
}
