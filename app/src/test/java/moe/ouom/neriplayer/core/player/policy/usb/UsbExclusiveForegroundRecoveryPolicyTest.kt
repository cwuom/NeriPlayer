package moe.ouom.neriplayer.core.player.policy.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveForegroundRecoveryPolicyTest {

    @Test
    fun `streaming native path probes progress after foreground resume`() {
        assertEquals(
            UsbExclusiveForegroundRecoveryAction.PROBE_PROGRESS,
            resolveUsbExclusiveForegroundRecoveryAction(
                nativePathActive = true,
                sinkPlaying = true,
                nativeOpened = true,
                nativeStreaming = true,
                nativePaused = false,
                nativeTransitioning = false
            )
        )
    }

    @Test
    fun `active sink with stopped native transport rebuilds playback`() {
        assertEquals(
            UsbExclusiveForegroundRecoveryAction.RECOVER_STOPPED_TRANSPORT,
            resolveUsbExclusiveForegroundRecoveryAction(
                nativePathActive = true,
                sinkPlaying = true,
                nativeOpened = true,
                nativeStreaming = false,
                nativePaused = false,
                nativeTransitioning = false
            )
        )
    }

    @Test
    fun `unopened native path with an active sink rebuilds playback`() {
        assertEquals(
            UsbExclusiveForegroundRecoveryAction.RECOVER_STOPPED_TRANSPORT,
            resolveUsbExclusiveForegroundRecoveryAction(
                nativePathActive = true,
                sinkPlaying = true,
                nativeOpened = false,
                nativeStreaming = false,
                nativePaused = false,
                nativeTransitioning = false
            )
        )
    }

    @Test
    fun `active native path restores a lost playback intent`() {
        assertTrue(
            shouldRestoreUsbExclusiveForegroundPlaybackIntent(
                action = UsbExclusiveForegroundRecoveryAction.PROBE_PROGRESS,
                transportActive = false
            )
        )
        assertTrue(
            shouldRestoreUsbExclusiveForegroundPlaybackIntent(
                action = UsbExclusiveForegroundRecoveryAction.RECOVER_STOPPED_TRANSPORT,
                transportActive = false
            )
        )
        assertFalse(
            shouldRestoreUsbExclusiveForegroundPlaybackIntent(
                action = UsbExclusiveForegroundRecoveryAction.PROBE_PROGRESS,
                transportActive = true
            )
        )
        assertFalse(
            shouldRestoreUsbExclusiveForegroundPlaybackIntent(
                action = UsbExclusiveForegroundRecoveryAction.NONE,
                transportActive = false
            )
        )
    }

    @Test
    fun `paused or transitioning native session is left untouched`() {
        assertEquals(
            UsbExclusiveForegroundRecoveryAction.NONE,
            resolveUsbExclusiveForegroundRecoveryAction(
                nativePathActive = true,
                sinkPlaying = true,
                nativeOpened = true,
                nativeStreaming = false,
                nativePaused = true,
                nativeTransitioning = false
            )
        )
        assertEquals(
            UsbExclusiveForegroundRecoveryAction.NONE,
            resolveUsbExclusiveForegroundRecoveryAction(
                nativePathActive = true,
                sinkPlaying = true,
                nativeOpened = true,
                nativeStreaming = true,
                nativePaused = false,
                nativeTransitioning = true
            )
        )
    }

    @Test
    fun `system fallback is not treated as a native foreground failure`() {
        assertEquals(
            UsbExclusiveForegroundRecoveryAction.NONE,
            resolveUsbExclusiveForegroundRecoveryAction(
                nativePathActive = false,
                sinkPlaying = true,
                nativeOpened = true,
                nativeStreaming = false,
                nativePaused = false,
                nativeTransitioning = false
            )
        )
    }
}
