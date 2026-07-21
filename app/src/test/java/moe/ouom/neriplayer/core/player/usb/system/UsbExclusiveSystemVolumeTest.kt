package moe.ouom.neriplayer.core.player.usb.system

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbExclusiveSystemVolumeTest {

    @Test
    fun `system media volume uses exponential gain`() {
        assertEquals(0f, usbExclusiveSystemVolumeGain(0f), 0.0001f)
        assertEquals(0.25f, usbExclusiveSystemVolumeGain(0.5f), 0.0001f)
        assertEquals(1f, usbExclusiveSystemVolumeGain(1f), 0.0001f)
    }

    @Test
    fun `native volume combines player and system gain safely`() {
        assertEquals(0.125f, usbExclusiveEffectiveNativeVolume(0.5f, 0.5f), 0.0001f)
        assertEquals(0f, usbExclusiveEffectiveNativeVolume(1f, -1f), 0.0001f)
        assertEquals(1f, usbExclusiveEffectiveNativeVolume(2f, 2f), 0.0001f)
    }

    @Test
    fun `float conversion preserves signal for native realtime gain`() {
        assertEquals(0.75f, usbExclusiveFloatSampleForNativePipeline(0.75f), 0.0001f)
        assertEquals(1f, usbExclusiveFloatSampleForNativePipeline(2f), 0.0001f)
        assertEquals(-1f, usbExclusiveFloatSampleForNativePipeline(-2f), 0.0001f)
        assertEquals(0f, usbExclusiveFloatSampleForNativePipeline(Float.NaN), 0.0001f)
    }
}
