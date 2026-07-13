package moe.ouom.neriplayer.core.player.policy.command

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPauseVolumePolicyTest {

    @Test
    fun `fade out pause keeps muted tail until pause completes`() {
        val plan = resolvePauseVolumePlan(
            allowFadeOut = true,
            preserveMutedVolume = false,
            playbackFadeInEnabled = true,
            playbackFadeOutDurationMs = 500L,
            isPlayerInitialized = true
        )

        assertTrue(plan.shouldFadeOut)
        assertFalse(plan.resetVolumeBeforePause)
        assertTrue(plan.restoreVolumeAfterPause)
    }

    @Test
    fun `immediate pause restores full volume before pausing when hard mute is not required`() {
        val plan = resolvePauseVolumePlan(
            allowFadeOut = false,
            preserveMutedVolume = false,
            playbackFadeInEnabled = true,
            playbackFadeOutDurationMs = 500L,
            isPlayerInitialized = true
        )

        assertFalse(plan.shouldFadeOut)
        assertTrue(plan.resetVolumeBeforePause)
        assertFalse(plan.restoreVolumeAfterPause)
    }

    @Test
    fun `audio route loss keeps player muted through pause`() {
        val plan = resolvePauseVolumePlan(
            allowFadeOut = false,
            preserveMutedVolume = true,
            playbackFadeInEnabled = true,
            playbackFadeOutDurationMs = 500L,
            isPlayerInitialized = true
        )

        assertFalse(plan.shouldFadeOut)
        assertFalse(plan.resetVolumeBeforePause)
        assertFalse(plan.restoreVolumeAfterPause)
    }
}
