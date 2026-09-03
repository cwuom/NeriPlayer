package moe.ouom.neriplayer.core.player.policy.progress

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRuntimeStallPolicyTest {

    @Test
    fun waitsBeforeTheBoundedRuntimeTimeout() {
        assertEquals(
            RuntimePlaybackStallAction.WAIT,
            evaluateRuntimeStall(
                elapsedSinceProgressMs = PLAYBACK_RUNTIME_STALL_TIMEOUT_MS - 1L,
                recoveryAttempts = 0
            )
        )
    }

    @Test
    fun recoversWhenReadyOrBufferingStopsAdvancingAfterTimeout() {
        listOf(Player.STATE_READY, Player.STATE_BUFFERING).forEach { state ->
            assertEquals(
                RuntimePlaybackStallAction.RECOVER,
                evaluateRuntimeStall(
                    playbackState = state,
                    isPlaying = state == Player.STATE_BUFFERING,
                    elapsedSinceProgressMs = PLAYBACK_RUNTIME_STALL_TIMEOUT_MS,
                    recoveryAttempts = 0
                )
            )
        }
    }

    @Test
    fun doesNotRecoverAfterTheBoundedAttemptBudget() {
        assertEquals(
            RuntimePlaybackStallAction.EXHAUSTED,
            evaluateRuntimeStall(
                elapsedSinceProgressMs = PLAYBACK_RUNTIME_STALL_TIMEOUT_MS,
                recoveryAttempts = PLAYBACK_RUNTIME_STALL_MAX_RECOVERY_ATTEMPTS
            )
        )
    }

    @Test
    fun readyButNotPlayingUsesTheFastRecoveryWindow() {
        assertEquals(
            RuntimePlaybackStallAction.WAIT,
            evaluateRuntimeStall(
                isPlaying = false,
                elapsedSinceProgressMs =
                    PLAYBACK_RUNTIME_READY_NOT_PLAYING_TIMEOUT_MS - 1L,
                recoveryAttempts = 0
            )
        )
        assertEquals(
            RuntimePlaybackStallAction.RECOVER,
            evaluateRuntimeStall(
                isPlaying = false,
                elapsedSinceProgressMs = PLAYBACK_RUNTIME_READY_NOT_PLAYING_TIMEOUT_MS,
                recoveryAttempts = 0
            )
        )
    }

    @Test
    fun emptyBufferingUsesTheShorterNetworkRecoveryWindow() {
        assertEquals(
            RuntimePlaybackStallAction.WAIT,
            evaluateRuntimeStall(
                playbackState = Player.STATE_BUFFERING,
                isPlaying = false,
                bufferedDurationMs = 0L,
                elapsedSinceProgressMs = PLAYBACK_RUNTIME_EMPTY_BUFFER_TIMEOUT_MS - 1L,
                recoveryAttempts = 0
            )
        )
        assertEquals(
            RuntimePlaybackStallAction.RECOVER,
            evaluateRuntimeStall(
                playbackState = Player.STATE_BUFFERING,
                isPlaying = false,
                bufferedDurationMs = 0L,
                elapsedSinceProgressMs = PLAYBACK_RUNTIME_EMPTY_BUFFER_TIMEOUT_MS,
                recoveryAttempts = 0
            )
        )
    }

    @Test
    fun ignoresIntentionalPauseAndSuppression() {
        assertEquals(
            RuntimePlaybackStallAction.IGNORE,
            evaluateRuntimeStall(
                resumePlaybackRequested = false,
                elapsedSinceProgressMs = PLAYBACK_RUNTIME_STALL_TIMEOUT_MS,
                recoveryAttempts = 0
            )
        )
        assertEquals(
            RuntimePlaybackStallAction.IGNORE,
            evaluateRuntimeStall(
                playbackSuppressionReason =
                    Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
                elapsedSinceProgressMs = PLAYBACK_RUNTIME_STALL_TIMEOUT_MS,
                recoveryAttempts = 0
            )
        )
        assertEquals(
            RuntimePlaybackStallAction.IGNORE,
            evaluateRuntimeStall(
                audioRouteMuteSuppressed = true,
                elapsedSinceProgressMs = PLAYBACK_RUNTIME_STALL_TIMEOUT_MS,
                recoveryAttempts = 0
            )
        )
        assertEquals(
            RuntimePlaybackStallAction.IGNORE,
            evaluateRuntimeStall(
                playWhenReady = false,
                elapsedSinceProgressMs = PLAYBACK_RUNTIME_STALL_TIMEOUT_MS,
                recoveryAttempts = 0
            )
        )
    }

    @Test
    fun ignoresPendingPauseAndUnstableStates() {
        assertEquals(
            RuntimePlaybackStallAction.IGNORE,
            evaluateRuntimeStall(
                pendingPause = true,
                elapsedSinceProgressMs = PLAYBACK_RUNTIME_STALL_TIMEOUT_MS,
                recoveryAttempts = 0
            )
        )
        assertEquals(
            RuntimePlaybackStallAction.IGNORE,
            evaluateRuntimeStall(
                playbackState = Player.STATE_IDLE,
                elapsedSinceProgressMs = PLAYBACK_RUNTIME_STALL_TIMEOUT_MS,
                recoveryAttempts = 0
            )
        )
        assertEquals(
            RuntimePlaybackStallAction.IGNORE,
            evaluateRuntimeStall(
                progressAdvanceReported = false,
                elapsedSinceProgressMs = PLAYBACK_RUNTIME_STALL_TIMEOUT_MS,
                recoveryAttempts = 0
            )
        )
    }

    @Test
    fun positionAdvanceTreatsSeekOrForwardMovementAsActivity() {
        assertTrue(
            PlaybackRuntimeStallPolicy.hasPositionAdvanced(
                currentPositionMs = 10_251L,
                lastPositionMs = 10_000L
            )
        )
        assertTrue(
            PlaybackRuntimeStallPolicy.hasPositionAdvanced(
                currentPositionMs = 9_700L,
                lastPositionMs = 10_000L
            )
        )
        assertTrue(
            PlaybackRuntimeStallPolicy.hasPositionAdvanced(
                currentPositionMs = 10_120L,
                lastPositionMs = 10_000L
            )
        )
        assertTrue(
            PlaybackRuntimeStallPolicy.hasPositionAdvanced(
                currentPositionMs = 10_020L,
                lastPositionMs = 10_000L
            )
        )
        assertFalse(
            PlaybackRuntimeStallPolicy.hasPositionAdvanced(
                currentPositionMs = 10_005L,
                lastPositionMs = 10_000L
            )
        )
    }
}

private fun evaluateRuntimeStall(
    playbackState: Int = Player.STATE_READY,
    resumePlaybackRequested: Boolean = true,
    playWhenReady: Boolean = true,
    isPlaying: Boolean = true,
    playbackSuppressionReason: Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
    bufferedDurationMs: Long = 1L,
    audioRouteMuteSuppressed: Boolean = false,
    pendingPause: Boolean = false,
    progressAdvanceReported: Boolean = true,
    elapsedSinceProgressMs: Long,
    recoveryAttempts: Int
): RuntimePlaybackStallAction {
    return PlaybackRuntimeStallPolicy.decide(
        initialized = true,
        pendingMediaLoad = false,
        hasMediaItem = true,
        resumePlaybackRequested = resumePlaybackRequested,
        playWhenReady = playWhenReady,
        isPlaying = isPlaying,
        playbackState = playbackState,
        bufferedDurationMs = bufferedDurationMs,
        playbackSuppressionReason = playbackSuppressionReason,
        audioRouteMuteSuppressed = audioRouteMuteSuppressed,
        pendingPause = pendingPause,
        progressAdvanceReported = progressAdvanceReported,
        elapsedSinceProgressMs = elapsedSinceProgressMs,
        recoveryAttempts = recoveryAttempts
    )
}
