package moe.ouom.neriplayer.core.player.policy.progress

import androidx.media3.common.Player

const val PLAYBACK_RUNTIME_STALL_TIMEOUT_MS = 12_000L
const val PLAYBACK_RUNTIME_STALL_POLL_INTERVAL_MS = 1_500L
const val PLAYBACK_RUNTIME_STALL_POSITION_TOLERANCE_MS = 10L
const val PLAYBACK_RUNTIME_STALL_MAX_RECOVERY_ATTEMPTS = 2
const val PLAYBACK_RUNTIME_READY_NOT_PLAYING_TIMEOUT_MS = 4_000L
const val PLAYBACK_RUNTIME_EMPTY_BUFFER_TIMEOUT_MS = 8_000L

enum class RuntimePlaybackStallAction {
    IGNORE,
    WAIT,
    RECOVER,
    EXHAUSTED
}

/**
 * 只负责运行期卡顿的纯状态决策，恢复副作用由看门狗执行
 */
object PlaybackRuntimeStallPolicy {
    fun decide(
        initialized: Boolean,
        pendingMediaLoad: Boolean,
        hasMediaItem: Boolean,
        resumePlaybackRequested: Boolean,
        playWhenReady: Boolean,
        isPlaying: Boolean,
        playbackState: Int,
        bufferedDurationMs: Long,
        playbackSuppressionReason: Int,
        audioRouteMuteSuppressed: Boolean,
        pendingPause: Boolean,
        progressAdvanceReported: Boolean,
        elapsedSinceProgressMs: Long,
        recoveryAttempts: Int,
        maxRecoveryAttempts: Int = PLAYBACK_RUNTIME_STALL_MAX_RECOVERY_ATTEMPTS,
        timeoutMs: Long = PLAYBACK_RUNTIME_STALL_TIMEOUT_MS
    ): RuntimePlaybackStallAction {
        if (
            !shouldMonitorProgress(initialized, pendingMediaLoad, hasMediaItem, progressAdvanceReported) ||
            isRecoverySuppressed(
                resumePlaybackRequested, playWhenReady, pendingPause,
                playbackSuppressionReason, audioRouteMuteSuppressed
            )
        ) {
            return RuntimePlaybackStallAction.IGNORE
        }
        if (playbackState != Player.STATE_READY && playbackState != Player.STATE_BUFFERING) {
            return RuntimePlaybackStallAction.IGNORE
        }
        val effectiveTimeoutMs = resolveStallTimeout(playbackState, isPlaying, bufferedDurationMs, timeoutMs)
        if (elapsedSinceProgressMs < effectiveTimeoutMs) {
            return RuntimePlaybackStallAction.WAIT
        }
        return if (recoveryAttempts >= maxRecoveryAttempts.coerceAtLeast(1)) {
            RuntimePlaybackStallAction.EXHAUSTED
        } else {
            RuntimePlaybackStallAction.RECOVER
        }
    }

    private fun shouldMonitorProgress(
        initialized: Boolean,
        pendingMediaLoad: Boolean,
        hasMediaItem: Boolean,
        progressAdvanceReported: Boolean
    ): Boolean = initialized && !pendingMediaLoad && hasMediaItem && progressAdvanceReported

    private fun isRecoverySuppressed(
        resumePlaybackRequested: Boolean,
        playWhenReady: Boolean,
        pendingPause: Boolean,
        playbackSuppressionReason: Int,
        audioRouteMuteSuppressed: Boolean
    ): Boolean = !resumePlaybackRequested || !playWhenReady || pendingPause ||
        playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE || audioRouteMuteSuppressed

    private fun resolveStallTimeout(
        playbackState: Int,
        isPlaying: Boolean,
        bufferedDurationMs: Long,
        timeoutMs: Long
    ): Long {
        return when (playbackState) {
            Player.STATE_READY if !isPlaying ->
                PLAYBACK_RUNTIME_READY_NOT_PLAYING_TIMEOUT_MS
            Player.STATE_BUFFERING if bufferedDurationMs <= 0L ->
                PLAYBACK_RUNTIME_EMPTY_BUFFER_TIMEOUT_MS
            else -> timeoutMs.coerceAtLeast(0L)
        }
    }

    fun hasPositionAdvanced(
        currentPositionMs: Long,
        lastPositionMs: Long,
        toleranceMs: Long = PLAYBACK_RUNTIME_STALL_POSITION_TOLERANCE_MS
    ): Boolean {
        val current = currentPositionMs.coerceAtLeast(0L)
        val last = lastPositionMs.coerceAtLeast(0L)
        return kotlin.math.abs(current - last) > toleranceMs.coerceAtLeast(0L)
    }
}
