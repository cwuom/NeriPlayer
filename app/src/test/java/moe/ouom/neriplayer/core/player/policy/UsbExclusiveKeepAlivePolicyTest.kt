package moe.ouom.neriplayer.core.player.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveKeepAlivePolicyTest {

    @Test
    fun `counter reset on reused handle establishes a new baseline`() {
        val decision = evaluateUsbExclusiveKeepAliveProgress(
            previousHandle = 6L,
            currentHandle = 6L,
            previousCompletedFrames = 10_784_256L,
            currentCompletedFrames = 479_232L,
            previousStallTicks = 0,
            recoveryTicks = 1
        )

        assertEquals(UsbExclusiveKeepAliveProgress.COUNTER_RESET, decision.progress)
        assertEquals(0, decision.stallTicks)
        assertFalse(decision.shouldRecover)
    }

    @Test
    fun `new native handle establishes a new baseline`() {
        val decision = evaluateUsbExclusiveKeepAliveProgress(
            previousHandle = 6L,
            currentHandle = 7L,
            previousCompletedFrames = 500_000L,
            currentCompletedFrames = 0L,
            previousStallTicks = 0,
            recoveryTicks = 1
        )

        assertEquals(UsbExclusiveKeepAliveProgress.BASELINE, decision.progress)
        assertFalse(decision.shouldRecover)
    }

    @Test
    fun `unchanged frame counter reaches recovery threshold`() {
        val decision = evaluateUsbExclusiveKeepAliveProgress(
            previousHandle = 7L,
            currentHandle = 7L,
            previousCompletedFrames = 500_000L,
            currentCompletedFrames = 500_000L,
            previousStallTicks = 0,
            recoveryTicks = 1
        )

        assertEquals(UsbExclusiveKeepAliveProgress.STALLED, decision.progress)
        assertTrue(decision.shouldRecover)
    }

    @Test
    fun `advanced frame counter clears prior stall ticks`() {
        val decision = evaluateUsbExclusiveKeepAliveProgress(
            previousHandle = 7L,
            currentHandle = 7L,
            previousCompletedFrames = 500_000L,
            currentCompletedFrames = 500_768L,
            previousStallTicks = 1,
            recoveryTicks = 2
        )

        assertEquals(UsbExclusiveKeepAliveProgress.ADVANCED, decision.progress)
        assertEquals(0, decision.stallTicks)
        assertFalse(decision.shouldRecover)
    }

    @Test
    fun `zero fill progress without signal advance is treated as fake progress`() {
        val decision = evaluateUsbExclusiveKeepAliveProgress(
            previousHandle = 7L,
            currentHandle = 7L,
            previousCompletedFrames = 500_000L,
            currentCompletedFrames = 500_768L,
            previousSignalBytes = 1_024L,
            currentSignalBytes = 1_024L,
            previousZeroFillBytes = 0L,
            currentZeroFillBytes = 4_096L,
            previousOutputPeak = 0f,
            currentOutputPeak = 0f,
            previousStallTicks = 0,
            recoveryTicks = 1
        )

        assertEquals(UsbExclusiveKeepAliveProgress.FAKE_PROGRESS, decision.progress)
        assertTrue(decision.shouldRecover)
    }
}
