package moe.ouom.neriplayer.core.player.policy.progress

import androidx.media3.common.Player

internal const val PLAYBACK_RUNTIME_STALL_TIMEOUT_MS = 12_000L
internal const val PLAYBACK_RUNTIME_STALL_POLL_INTERVAL_MS = 1_500L
internal const val PLAYBACK_RUNTIME_STALL_POSITION_TOLERANCE_MS = 10L
internal const val PLAYBACK_RUNTIME_STALL_MAX_RECOVERY_ATTEMPTS = 2
internal const val PLAYBACK_RUNTIME_READY_NOT_PLAYING_TIMEOUT_MS = 4_000L
internal const val PLAYBACK_RUNTIME_EMPTY_BUFFER_TIMEOUT_MS = 8_000L

internal enum class RuntimePlaybackStallAction {
    IGNORE,
    WAIT,
    RECOVER,
    EXHAUSTED
}

/**
 * 只负责运行期卡顿的纯状态决策，恢复副作用由看门狗执行
 */
internal object PlaybackRuntimeStallPolicy {
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
            !initialized ||
            pendingMediaLoad ||
            !hasMediaItem ||
            !resumePlaybackRequested ||
            !playWhenReady ||
            !progressAdvanceReported ||
            pendingPause ||
            playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ||
            audioRouteMuteSuppressed
        ) {
            return RuntimePlaybackStallAction.IGNORE
        }
        if (playbackState != Player.STATE_READY && playbackState != Player.STATE_BUFFERING) {
            return RuntimePlaybackStallAction.IGNORE
        }
        val effectiveTimeoutMs = when (playbackState) {
            Player.STATE_READY if !isPlaying ->
                PLAYBACK_RUNTIME_READY_NOT_PLAYING_TIMEOUT_MS
            Player.STATE_BUFFERING if bufferedDurationMs <= 0L ->
                PLAYBACK_RUNTIME_EMPTY_BUFFER_TIMEOUT_MS
            else -> timeoutMs.coerceAtLeast(0L)
        }
        if (elapsedSinceProgressMs < effectiveTimeoutMs) {
            return RuntimePlaybackStallAction.WAIT
        }
        return if (recoveryAttempts >= maxRecoveryAttempts.coerceAtLeast(1)) {
            RuntimePlaybackStallAction.EXHAUSTED
        } else {
            RuntimePlaybackStallAction.RECOVER
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
