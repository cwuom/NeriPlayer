package moe.ouom.neriplayer.core.player.policy.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveForegroundRecoveryPolicyTest {

    @Test
    fun `deferred runtime refresh retries only within its bounded budget`() {
        assertTrue(
            shouldRetryUsbExclusiveDeferredRuntimeRefresh(
                runtimeReportValid = false,
                runtimeReportInvalidReason = "native_refresh_deferred",
                retryAttempt = 0
            )
        )
        assertTrue(
            shouldRetryUsbExclusiveDeferredRuntimeRefresh(
                runtimeReportValid = false,
                runtimeReportInvalidReason = "native_refresh_deferred",
                retryAttempt = USB_EXCLUSIVE_DEFERRED_RUNTIME_REFRESH_MAX_RETRIES - 1
            )
        )
        assertFalse(
            shouldRetryUsbExclusiveDeferredRuntimeRefresh(
                runtimeReportValid = false,
                runtimeReportInvalidReason = "native_refresh_deferred",
                retryAttempt = USB_EXCLUSIVE_DEFERRED_RUNTIME_REFRESH_MAX_RETRIES
            )
        )
    }

    @Test
    fun `non deferred invalid runtime refresh is not retried`() {
        assertFalse(
            shouldRetryUsbExclusiveDeferredRuntimeRefresh(
                runtimeReportValid = false,
                runtimeReportInvalidReason = "runtime_report_v2_invalid",
                retryAttempt = 0
            )
        )
        assertFalse(
            shouldRetryUsbExclusiveDeferredRuntimeRefresh(
                runtimeReportValid = true,
                runtimeReportInvalidReason = "native_refresh_deferred",
                retryAttempt = 0
            )
        )
    }

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
    fun `active native path requests playback recovery only when transport is inactive`() {
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
