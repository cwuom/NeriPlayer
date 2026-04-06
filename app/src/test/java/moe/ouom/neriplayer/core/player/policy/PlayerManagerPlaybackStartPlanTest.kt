package moe.ouom.neriplayer.core.player.policy

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
    fun `continuation plan resumes fade in from current volume during fade out cancel`() {
        val plan = resolvePlaybackContinuationStartPlan(
            plan = PlaybackStartPlan(
                useFadeIn = true,
                fadeDurationMs = 1000L,
                initialVolume = 0f
            ),
            currentVolume = 0.4f
        )

        assertTrue(plan.useFadeIn)
        assertEquals(600L, plan.fadeDurationMs)
        assertEquals(0.4f, plan.initialVolume, 0.0001f)
    }

    @Test
    fun `continuation plan skips extra fade when volume already recovered`() {
        val plan = resolvePlaybackContinuationStartPlan(
            plan = PlaybackStartPlan(
                useFadeIn = true,
                fadeDurationMs = 1000L,
                initialVolume = 0f
            ),
            currentVolume = 1f
        )

        assertTrue(plan.useFadeIn)
        assertEquals(0L, plan.fadeDurationMs)
        assertEquals(1f, plan.initialVolume, 0.0001f)
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
    fun `manual resume decision keeps saved progress and enables startup protection on cold resume`() {
        val decision = resolveManualResumePlaybackDecision(
            keepLastPlaybackProgressEnabled = true,
            restoredResumePositionMs = 48_000L,
            persistedPlaybackPositionMs = 7_925L,
            isPlayerPrepared = false,
            currentMediaUrlResolvedAtMs = 0L
        )

        assertEquals(48_000L, decision.resumePositionMs)
        assertTrue(decision.forceStartupProtectionFade)
    }

    @Test
    fun `manual resume decision disables startup protection when progress restore is off`() {
        val decision = resolveManualResumePlaybackDecision(
            keepLastPlaybackProgressEnabled = false,
            restoredResumePositionMs = 48_000L,
            persistedPlaybackPositionMs = 7_925L,
            isPlayerPrepared = false,
            currentMediaUrlResolvedAtMs = 0L
        )

        assertEquals(0L, decision.resumePositionMs)
        assertFalse(decision.forceStartupProtectionFade)
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

    @Test
    fun `app bootstrap restores paused queue for external controls`() {
        val shouldBootstrap = shouldBootstrapPlaybackServiceOnAppLaunch(
            hasCurrentSong = true,
            playJobActive = false,
            pendingPauseJobActive = false,
            playWhenReady = false,
            isPlaying = false,
            playerPlaybackState = 0
        )

        assertTrue(shouldBootstrap)
    }

    @Test
    fun `app bootstrap still resumes live buffering session`() {
        val shouldBootstrap = shouldBootstrapPlaybackServiceOnAppLaunch(
            hasCurrentSong = true,
            playJobActive = false,
            pendingPauseJobActive = false,
            playWhenReady = false,
            isPlaying = false,
            playerPlaybackState = androidx.media3.common.Player.STATE_BUFFERING
        )

        assertTrue(shouldBootstrap)
    }

    @Test
    fun `local playback commands sync playback service after resume sensitive actions`() {
        assertTrue(shouldSyncPlaybackServiceForLocalPlaybackCommand("PLAY"))
        assertTrue(shouldSyncPlaybackServiceForLocalPlaybackCommand("PLAY_PLAYLIST"))
        assertTrue(shouldSyncPlaybackServiceForLocalPlaybackCommand("PLAY_FROM_QUEUE"))
        assertTrue(shouldSyncPlaybackServiceForLocalPlaybackCommand("NEXT"))
        assertTrue(shouldSyncPlaybackServiceForLocalPlaybackCommand("PREVIOUS"))
    }

    @Test
    fun `non resume commands do not eagerly sync playback service`() {
        assertFalse(shouldSyncPlaybackServiceForLocalPlaybackCommand("PAUSE"))
        assertFalse(shouldSyncPlaybackServiceForLocalPlaybackCommand("SEEK"))
        assertFalse(shouldSyncPlaybackServiceForLocalPlaybackCommand("UNKNOWN"))
    }
}
