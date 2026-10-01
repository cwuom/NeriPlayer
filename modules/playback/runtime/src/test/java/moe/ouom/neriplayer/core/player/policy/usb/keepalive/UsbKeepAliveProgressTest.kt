package moe.ouom.neriplayer.core.player.policy.usb.keepalive

import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveKeepAliveProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbKeepAliveProgressTest {
    private val baseline = UsbKeepAliveCounters(15, 1, 0, 0, Float.NaN)

    @Test
    fun `invalid handles and unknown frame counters cannot share a progress baseline`() {
        assertTrue(hasSameUsbKeepAliveBaseline(baseline, baseline))
        assertFalse(hasSameUsbKeepAliveBaseline(baseline.copy(handle = 0), baseline))
        assertFalse(hasSameUsbKeepAliveBaseline(baseline, baseline.copy(handle = 0)))
        assertFalse(hasSameUsbKeepAliveBaseline(baseline, baseline.copy(handle = 16)))
        assertFalse(hasSameUsbKeepAliveBaseline(baseline.copy(completedFrames = -1), baseline))
    }

    @Test
    fun `unknown peaks do not convert zero fill into proof of silent playback`() {
        val previous = baseline.copy(outputPeak = 0f)
        val current = previous.copy(completedFrames = 2, zeroFillBytes = 1, outputPeak = Float.NaN)
        val queue = UsbKeepAliveQueue(0, 0, -1)
        assertEquals(UsbExclusiveKeepAliveProgress.ADVANCED, classifyAdvancedUsbKeepAliveProgress(previous, current, queue))
        assertEquals(UsbExclusiveKeepAliveProgress.FAKE_PROGRESS,
            classifyAdvancedUsbKeepAliveProgress(previous, current.copy(outputPeak = 0f), queue))
        assertEquals(UsbExclusiveKeepAliveProgress.ADVANCED,
            classifyAdvancedUsbKeepAliveProgress(previous, current.copy(outputPeak = 0.2f), queue))
    }

    @Test
    fun `only a valid drained PCM queue permits severe starvation recovery`() {
        val current = baseline.copy(completedFrames = 2, zeroFillBytes = 3_000)
        val queue = UsbKeepAliveQueue(1_000, 4, 0)
        assertEquals(UsbExclusiveKeepAliveProgress.PCM_STARVATION, classifyAdvancedUsbKeepAliveProgress(baseline, current, queue))
        listOf(queue.copy(sampleRate = 0), queue.copy(frameBytes = 0), queue.copy(pcmLevelBytes = -1), queue.copy(pcmLevelBytes = 404))
            .forEach { invalid ->
                assertEquals(UsbExclusiveKeepAliveProgress.ADVANCED, classifyAdvancedUsbKeepAliveProgress(baseline, current, invalid))
            }
    }
}
