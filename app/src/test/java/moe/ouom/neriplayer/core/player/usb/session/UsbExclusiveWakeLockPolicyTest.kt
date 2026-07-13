package moe.ouom.neriplayer.core.player.usb.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveWakeLockPolicyTest {

    @Test
    fun `opened but paused player session does not hold wake lock`() {
        assertFalse(
            shouldHoldUsbExclusiveWakeLock(
                streaming = false,
                transitioning = false,
                transportCommandInFlight = false,
                nativeCloseInFlightCount = 0
            )
        )
    }

    @Test
    fun `active native stream holds wake lock`() {
        assertTrue(
            shouldHoldUsbExclusiveWakeLock(
                streaming = true,
                transitioning = false,
                transportCommandInFlight = false,
                nativeCloseInFlightCount = 0
            )
        )
    }

    @Test
    fun `native transition and close retain wake lock until resources settle`() {
        assertTrue(
            shouldHoldUsbExclusiveWakeLock(
                streaming = false,
                transitioning = true,
                transportCommandInFlight = false,
                nativeCloseInFlightCount = 0
            )
        )
        assertTrue(
            shouldHoldUsbExclusiveWakeLock(
                streaming = false,
                transitioning = false,
                transportCommandInFlight = false,
                nativeCloseInFlightCount = 1
            )
        )
    }

    @Test
    fun `native transport command retains wake lock before state publication`() {
        assertTrue(
            shouldHoldUsbExclusiveWakeLock(
                streaming = false,
                transitioning = false,
                transportCommandInFlight = true,
                nativeCloseInFlightCount = 0
            )
        )
    }

    @Test
    fun `runtime stop overrides stale streaming state`() {
        assertFalse(
            resolveUsbExclusiveStreamingState(
                hasNativeHandle = true,
                runtimeRunning = false,
                currentStreaming = true
            )
        )
        assertTrue(
            resolveUsbExclusiveStreamingState(
                hasNativeHandle = true,
                runtimeRunning = null,
                currentStreaming = true
            )
        )
        assertFalse(
            resolveUsbExclusiveStreamingState(
                hasNativeHandle = false,
                runtimeRunning = true,
                currentStreaming = true
            )
        )
    }

    @Test
    fun `fresh and flushed player sessions are not treated as paused`() {
        assertFalse(
            resolveUsbExclusivePausedState(
                hasActivePlayerSession = true,
                runtimePaused = false,
                currentPaused = false
            )
        )
    }

    @Test
    fun `runtime refresh preserves a real paused session`() {
        assertTrue(
            resolveUsbExclusivePausedState(
                hasActivePlayerSession = true,
                runtimePaused = true,
                currentPaused = true
            )
        )
        assertTrue(
            resolveUsbExclusivePausedState(
                hasActivePlayerSession = true,
                runtimePaused = null,
                currentPaused = true
            )
        )
        assertFalse(
            resolveUsbExclusivePausedState(
                hasActivePlayerSession = false,
                runtimePaused = true,
                currentPaused = true
            )
        )
    }
}
