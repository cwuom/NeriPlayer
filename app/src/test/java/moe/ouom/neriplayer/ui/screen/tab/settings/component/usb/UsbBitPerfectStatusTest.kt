package moe.ouom.neriplayer.ui.screen.tab.settings.component.usb

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbBitPerfectStatusTest {
    private fun input(rate: Int, channels: Int = 2, encoding: Int = AudioFormat.ENCODING_PCM_16BIT) =
        "mime=audio/raw sampleRate=$rate channels=$channels encoding=$encoding"

    private fun output(rate: Int, channels: Int = 2, bits: Int = 24) =
        "rate=$rate channels=$channels bits=$bits subslot=4 rateMode=follow_source"

    @Test
    fun `status is only resolved once the native output format is known`() {
        assertNull(resolveUsbBitPerfectStatus(true, input(44_100), "none"))
        assertNull(resolveUsbBitPerfectStatus(true, "none", output(44_100)))
        assertEquals(UsbBitPerfectStatus.Off, resolveUsbBitPerfectStatus(false, input(44_100), output(48_000)))
    }

    @Test
    fun `matching rate with an equal or wider container is bit perfect`() {
        assertEquals(UsbBitPerfectStatus.Active, resolveUsbBitPerfectStatus(true, input(44_100), output(44_100)))
        assertEquals(
            UsbBitPerfectStatus.Active,
            resolveUsbBitPerfectStatus(true, input(96_000, encoding = AudioFormat.ENCODING_PCM_FLOAT), output(96_000, bits = 16))
        )
        assertEquals(
            UsbBitPerfectStatus.Active,
            resolveUsbBitPerfectStatus(true, input(48_000, channels = 1), output(48_000))
        )
    }

    @Test
    fun `fallbacks that alter samples are reported`() {
        assertEquals(
            UsbBitPerfectStatus.Resampled(44_100, 48_000),
            resolveUsbBitPerfectStatus(true, input(44_100), output(48_000))
        )
        assertEquals(
            UsbBitPerfectStatus.Truncated(24, 16),
            resolveUsbBitPerfectStatus(
                true,
                input(96_000, encoding = AudioFormat.ENCODING_PCM_24BIT_PACKED),
                output(96_000, bits = 16)
            )
        )
        assertEquals(
            UsbBitPerfectStatus.ChannelsMapped(6, 2),
            resolveUsbBitPerfectStatus(true, input(48_000, channels = 6), output(48_000))
        )
    }
}
