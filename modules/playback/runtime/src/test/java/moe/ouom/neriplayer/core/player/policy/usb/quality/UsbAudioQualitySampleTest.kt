package moe.ouom.neriplayer.core.player.policy.usb.quality

import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveAudioQualityRecoveryState
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRuntimeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioQualitySampleTest {
    @Test
    fun `quality recovery requires an active player PCM handle`() {
        val active = UsbExclusiveRuntimeMetrics(source = "player_pcm", running = true)
        assertTrue(active.isActiveUsbQualityOutput(15))
        assertFalse(active.isActiveUsbQualityOutput(0))
        assertFalse(active.copy(source = "generated").isActiveUsbQualityOutput(15))
        assertFalse(active.copy(running = false).isActiveUsbQualityOutput(15))
        assertFalse(active.copy(running = null).isActiveUsbQualityOutput(15))
    }

    @Test
    fun `every decreasing runtime counter invalidates the quality baseline`() {
        val previous = UsbExclusiveAudioQualityRecoveryState(
            completedTransfers = 1, isoPacketErrors = 1, isoPacketErrorTransfers = 1,
            isoPacketErrorScore = 1, playerSignalBytes = 1, playerDroppedBytes = 1,
            playerUnderrunBytes = 1, playerZeroFillBytes = 1
        )
        val decreased = listOf(
            previous.copy(completedTransfers = 0), previous.copy(isoPacketErrors = 0),
            previous.copy(isoPacketErrorTransfers = 0), previous.copy(isoPacketErrorScore = 0),
            previous.copy(playerSignalBytes = 0), previous.copy(playerDroppedBytes = 0),
            previous.copy(playerUnderrunBytes = 0), previous.copy(playerZeroFillBytes = 0)
        )
        assertFalse(previous.hasCounterResetSince(previous))
        assertTrue(previous.isSameRuntimeCounterSampleAs(previous))
        decreased.forEach { sample ->
            assertTrue(sample.hasCounterResetSince(previous))
            assertFalse(sample.isSameRuntimeCounterSampleAs(previous))
        }
        assertTrue(previous.copy(handle = 9, consecutivePlayerDropTicks = 2).isSameRuntimeCounterSampleAs(previous))
    }

    @Test
    fun `missing counters preserve legacy defaults`() {
        assertEquals(UsbExclusiveAudioQualityRecoveryState(handle = 7), UsbExclusiveRuntimeMetrics().toQualityState(7))
    }

    @Test
    fun `unknown or invalid audio format cannot invent a large quality gap`() {
        val format = UsbExclusiveRuntimeMetrics(sampleRate = 96_000, channelCount = 2, subslotBytes = 4)
        assertEquals(30_720L, usbQualityGapBytes(format, 40))
        listOf(
            format.copy(sampleRate = null), format.copy(sampleRate = 0), format.copy(sampleRate = -1),
            format.copy(channelCount = null), format.copy(channelCount = 0), format.copy(subslotBytes = null)
        ).forEach { assertEquals(Long.MAX_VALUE, usbQualityGapBytes(it, 40)) }
    }

    @Test
    fun `recent valid peak wins and unknown peaks fall back without treating NaN as sound`() {
        val metrics = UsbExclusiveRuntimeMetrics(outputPeak = 0.5f)
        assertEquals(0.2f, metrics.copy(lastOutputPeak = 0.2f).bestOutputPeak())
        assertEquals(0.5f, metrics.copy(lastOutputPeak = Float.NaN).bestOutputPeak())
        assertEquals(0.5f, metrics.bestOutputPeak())
        assertNull(UsbExclusiveRuntimeMetrics(outputPeak = Float.NaN).bestOutputPeak())
        assertNull(UsbExclusiveRuntimeMetrics().bestOutputPeak())
    }
}
