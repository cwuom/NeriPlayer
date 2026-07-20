package moe.ouom.neriplayer.core.player.policy.usb

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

    @Test
    fun `drained queue with severe zero fill recovers despite partial signal progress`() {
        val decision = evaluateUsbExclusiveKeepAliveProgress(
            previousHandle = 7L,
            currentHandle = 7L,
            previousCompletedFrames = 500_000L,
            currentCompletedFrames = 1_460_000L,
            previousSignalBytes = 2_048L,
            currentSignalBytes = 8_192L,
            previousZeroFillBytes = 0L,
            currentZeroFillBytes = 1_152_000L,
            outputSampleRate = 192_000,
            outputFrameBytes = 8,
            currentPcmLevelBytes = 153_600L,
            previousStallTicks = 0,
            recoveryTicks = 1
        )

        assertEquals(UsbExclusiveKeepAliveProgress.PCM_STARVATION, decision.progress)
        assertTrue(decision.shouldRecover)
    }

    @Test
    fun `severe historical zero fill does not recover after queue refills`() {
        val decision = evaluateUsbExclusiveKeepAliveProgress(
            previousHandle = 7L,
            currentHandle = 7L,
            previousCompletedFrames = 500_000L,
            currentCompletedFrames = 1_460_000L,
            previousSignalBytes = 2_048L,
            currentSignalBytes = 8_192L,
            previousZeroFillBytes = 0L,
            currentZeroFillBytes = 1_152_000L,
            outputSampleRate = 192_000,
            outputFrameBytes = 8,
            currentPcmLevelBytes = 1_152_000L,
            previousStallTicks = 0,
            recoveryTicks = 1
        )

        assertEquals(UsbExclusiveKeepAliveProgress.ADVANCED, decision.progress)
        assertFalse(decision.shouldRecover)
    }
}
