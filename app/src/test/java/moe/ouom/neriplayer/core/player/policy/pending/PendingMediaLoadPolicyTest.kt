package moe.ouom.neriplayer.core.player.policy.pending

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMediaLoadPolicyTest {

    @Test
    fun `entering pending load exposes requested position instead of old player position`() {
        val position = resolvePendingMediaLoadPosition(
            pendingLoadActive = true,
            requestedPositionMs = 0L,
            livePlayerPositionMs = 45_000L
        )

        assertEquals(0L, position)
    }

    @Test
    fun `applied current request exposes live player position instead of pending position`() {
        val position = resolvePendingMediaLoadPosition(
            pendingLoadActive = false,
            requestedPositionMs = 0L,
            livePlayerPositionMs = 1_250L
        )

        assertEquals(1_250L, position)
    }

    @Test
    fun `entering pending load requests stale media quarantine`() {
        val action = resolvePendingMediaLoadEntryAction(requestedPositionMs = 0L)

        assertTrue(action.stopProgressUpdates)
        assertTrue(action.stopPlayer)
        assertTrue(action.clearMediaItems)
        assertFalse(action.isPlaying)
        assertFalse(action.playWhenReady)
        assertEquals(Player.STATE_IDLE, action.playbackState)
        assertEquals(0L, action.positionMs)
    }

    @Test
    fun `stale ready callback is rejected during newer pending load`() {
        val accepted = shouldAcceptPlayerCallback(
            currentRequestGeneration = 2L,
            loadedMediaGeneration = 1L
        )

        assertFalse(accepted)
    }

    @Test
    fun `stale ended callback is rejected during newer pending load`() {
        val accepted = shouldAcceptPlayerCallback(
            currentRequestGeneration = 4L,
            loadedMediaGeneration = 3L
        )

        assertFalse(accepted)
    }

    @Test
    fun `stale playback state callback is rejected before exposing state`() {
        val accepted = shouldExposePlayerCallbackState(
            currentRequestGeneration = 4L,
            loadedMediaGeneration = 3L,
            pendingLoadActive = true
        )

        assertFalse(accepted)
    }

    @Test
    fun `current playback state callback is exposed after request applies`() {
        val accepted = shouldExposePlayerCallbackState(
            currentRequestGeneration = 4L,
            loadedMediaGeneration = 4L,
            pendingLoadActive = false
        )

        assertTrue(accepted)
    }

    @Test
    fun `stale player error is rejected during newer pending load`() {
        val accepted = shouldAcceptPlayerCallback(
            currentRequestGeneration = 7L,
            loadedMediaGeneration = 6L
        )

        assertFalse(accepted)
    }

    @Test
    fun `current loaded media callback is accepted`() {
        val accepted = shouldAcceptPlayerCallback(
            currentRequestGeneration = 7L,
            loadedMediaGeneration = 7L
        )

        assertTrue(accepted)
    }

    @Test
    fun `older generation callback is accepted when no media load is pending`() {
        val accepted = shouldAcceptPlayerCallback(
            currentRequestGeneration = 8L,
            loadedMediaGeneration = 7L,
            pendingLoadActive = false
        )

        assertTrue(accepted)
    }

    @Test
    fun `seek during pending load stores target seek without touching stale media`() {
        val action = resolvePendingSeekAction(
            pendingLoadActive = true,
            requestedPositionMs = 12_345L
        )

        assertFalse(action.seekPlayerNow)
        assertEquals(12_345L, action.pendingSeekPositionMs)
        assertEquals(12_345L, action.exposedPositionMs)
        assertEquals(12_345L, action.persistPositionMs)
    }

    @Test
    fun `pause during pending load suppresses pending autoplay`() {
        val action = resolvePendingPauseAction(
            pendingLoadActive = true,
            exposedPositionMs = 12_345L
        )

        assertFalse(action.resumePlaybackRequested)
        assertFalse(action.resumePlaybackAfterLoad)
        assertFalse(action.persistShouldResumePlayback)
        assertEquals(12_345L, action.persistPositionMs)
    }

    @Test
    fun `play during pending load requests target autoplay without resuming stale media`() {
        val action = resolvePendingPlayAction(pendingLoadActive = true)

        assertTrue(action.resumePlaybackRequested)
    }

    @Test
    fun `older pending request is rejected after consecutive skip`() {
        assertFalse(
            shouldApplyResolvedMedia(
                requestGeneration = 10L,
                currentRequestGeneration = 11L
            )
        )
        assertTrue(
            shouldApplyResolvedMedia(
                requestGeneration = 11L,
                currentRequestGeneration = 11L
            )
        )
    }

    @Test
    fun `stale resolved media cannot apply success side effects`() {
        val accepted = shouldApplyResolvedMediaSideEffects(
            requestGeneration = 10L,
            currentRequestGeneration = 11L,
            requestActive = true
        )

        assertFalse(accepted)
    }

    @Test
    fun `current active resolved media can apply success side effects`() {
        val accepted = shouldApplyResolvedMediaSideEffects(
            requestGeneration = 11L,
            currentRequestGeneration = 11L,
            requestActive = true
        )

        assertTrue(accepted)
    }

    @Test
    fun `inactive resolved media cannot apply success side effects`() {
        val accepted = shouldApplyResolvedMediaSideEffects(
            requestGeneration = 11L,
            currentRequestGeneration = 11L,
            requestActive = false
        )

        assertFalse(accepted)
    }

    @Test
    fun `pending load persistence uses exposed target position`() {
        val position = resolvePendingMediaLoadPosition(
            pendingLoadActive = true,
            requestedPositionMs = 0L,
            livePlayerPositionMs = 45_000L
        )

        assertEquals(0L, position)
    }

    @Test
    fun `media session snapshot during pending load uses pending position`() {
        val position = resolvePendingMediaLoadPosition(
            pendingLoadActive = true,
            requestedPositionMs = 0L,
            livePlayerPositionMs = 45_000L
        )

        assertEquals(0L, position)
    }

    @Test
    fun `listen together pending local track load uses gated position`() {
        val position = resolvePendingMediaLoadPosition(
            pendingLoadActive = true,
            requestedPositionMs = 0L,
            livePlayerPositionMs = 45_000L
        )

        assertEquals(0L, position)
    }
}
