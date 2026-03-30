package moe.ouom.neriplayer.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerPlaybackStartPlanTest {

    @Test
    fun `fade in plan starts muted`() {
        val plan = resolvePlaybackStartPlan(
            shouldFadeIn = true,
            fadeDurationMs = 500L
        )

        assertTrue(plan.useFadeIn)
        assertEquals(500L, plan.fadeDurationMs)
        assertEquals(0f, plan.initialVolume, 0.0001f)
    }

    @Test
    fun `disabled fade in keeps full volume`() {
        val plan = resolvePlaybackStartPlan(
            shouldFadeIn = false,
            fadeDurationMs = 500L
        )

        assertFalse(plan.useFadeIn)
        assertEquals(500L, plan.fadeDurationMs)
        assertEquals(1f, plan.initialVolume, 0.0001f)
    }

    @Test
    fun `non positive duration disables fade in`() {
        val plan = resolvePlaybackStartPlan(
            shouldFadeIn = true,
            fadeDurationMs = -120L
        )

        assertFalse(plan.useFadeIn)
        assertEquals(0L, plan.fadeDurationMs)
        assertEquals(1f, plan.initialVolume, 0.0001f)
    }

    @Test
    fun `restored playback protection forces fade when user fade is disabled`() {
        val plan = resolveManagedPlaybackStartPlan(
            playbackFadeInEnabled = false,
            playbackFadeInDurationMs = 0L,
            playbackCrossfadeInDurationMs = 300L,
            forceStartupProtectionFade = true
        )

        assertTrue(plan.useFadeIn)
        assertEquals(RESTORED_PLAYBACK_PROTECTION_FADE_DURATION_MS, plan.fadeDurationMs)
        assertEquals(0f, plan.initialVolume, 0.0001f)
    }

    @Test
    fun `restored playback protection keeps longer user fade duration`() {
        val plan = resolveManagedPlaybackStartPlan(
            playbackFadeInEnabled = true,
            playbackFadeInDurationMs = 1600L,
            playbackCrossfadeInDurationMs = 300L,
            forceStartupProtectionFade = true
        )

        assertTrue(plan.useFadeIn)
        assertEquals(1600L, plan.fadeDurationMs)
        assertEquals(0f, plan.initialVolume, 0.0001f)
    }

    @Test
    fun `manual cold resume forces startup protection fade`() {
        val shouldProtect = shouldForceStartupProtectionFadeOnManualResume(
            isPlayerPrepared = false,
            resumePositionMs = 48_000L,
            currentMediaUrlResolvedAtMs = 0L
        )

        assertTrue(shouldProtect)
    }

    @Test
    fun `prepared player does not need manual startup protection fade`() {
        val shouldProtect = shouldForceStartupProtectionFadeOnManualResume(
            isPlayerPrepared = true,
            resumePositionMs = 48_000L,
            currentMediaUrlResolvedAtMs = 0L
        )

        assertFalse(shouldProtect)
    }

    @Test
    fun `freshly resolved media skips manual startup protection fade`() {
        val shouldProtect = shouldForceStartupProtectionFadeOnManualResume(
            isPlayerPrepared = false,
            resumePositionMs = 48_000L,
            currentMediaUrlResolvedAtMs = 1L
        )

        assertFalse(shouldProtect)
    }

    @Test
    fun `resume requested keeps playback service eligible for foreground`() {
        val shouldRunInForeground = shouldRunPlaybackServiceInForeground(
            hasCurrentSong = true,
            resumePlaybackRequested = true,
            playJobActive = false,
            pendingPauseJobActive = false,
            playWhenReady = false,
            isPlaying = false,
            playerPlaybackState = 0
        )

        assertTrue(shouldRunInForeground)
    }

    @Test
    fun `foreground service policy requires current song`() {
        val shouldRunInForeground = shouldRunPlaybackServiceInForeground(
            hasCurrentSong = false,
            resumePlaybackRequested = true,
            playJobActive = true,
            pendingPauseJobActive = true,
            playWhenReady = true,
            isPlaying = true,
            playerPlaybackState = 0
        )

        assertFalse(shouldRunInForeground)
    }
}
