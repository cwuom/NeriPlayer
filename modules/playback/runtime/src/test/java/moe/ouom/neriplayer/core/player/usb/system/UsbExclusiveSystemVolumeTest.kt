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
    fun `bit perfect volume keeps native gain at unity`() {
        assertEquals(
            1f,
            usbExclusiveEffectiveNativeVolume(
                playerVolume = 0.2f,
                systemVolumeFraction = 0.1f,
                bitPerfect = true
            ),
            0.0001f
        )
    }

    @Test
    fun `bit perfect still honors an explicit player mute`() {
        assertEquals(0f, usbExclusiveEffectiveNativeVolume(0f, 1f, bitPerfect = true), 0f)
        assertEquals(0f, usbExclusiveEffectiveNativeVolume(-1f, 1f, bitPerfect = true), 0f)
    }

    @Test
    fun `float conversion preserves signal for native realtime gain`() {
        assertEquals(0.75f, usbExclusiveFloatSampleForNativePipeline(0.75f), 0.0001f)
        assertEquals(1f, usbExclusiveFloatSampleForNativePipeline(2f), 0.0001f)
        assertEquals(-1f, usbExclusiveFloatSampleForNativePipeline(-2f), 0.0001f)
        assertEquals(0f, usbExclusiveFloatSampleForNativePipeline(Float.NaN), 0.0001f)
    }

    @Test
    fun `float samples decoded from integer PCM convert back bit exactly`() {
        for (value in listOf(-32768, -16385, -1, 0, 1, 16384, 20000, 32766, 32767)) {
            assertEquals(value, usbExclusiveFloatToPcmInt(value / 32768f, 16))
        }
        for (value in listOf(-8_388_608, -4_194_305, 1, 4_194_304, 8_388_606, 8_388_607)) {
            assertEquals(value, usbExclusiveFloatToPcmInt(value / 8_388_608f, 24))
        }
        assertEquals(20000 shl 16, usbExclusiveFloatToPcmInt(20000 / 32768f, 32))
    }

    @Test
    fun `float to integer conversion clamps full scale and invalid samples`() {
        assertEquals(32767, usbExclusiveFloatToPcmInt(1f, 16))
        assertEquals(-32768, usbExclusiveFloatToPcmInt(-2f, 16))
        assertEquals(Int.MAX_VALUE, usbExclusiveFloatToPcmInt(1f, 32))
        assertEquals(Int.MIN_VALUE, usbExclusiveFloatToPcmInt(-1f, 32))
        assertEquals(0, usbExclusiveFloatToPcmInt(Float.NaN, 24))
    }
}
