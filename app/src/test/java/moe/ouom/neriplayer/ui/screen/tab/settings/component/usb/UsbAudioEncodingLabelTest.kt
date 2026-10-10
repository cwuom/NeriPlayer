package moe.ouom.neriplayer.ui.screen.tab.settings.component.usb

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbAudioEncodingLabelTest {

    @Test
    fun `PCM encodings get readable labels`() {
        assertEquals("PCM 8-bit", AudioFormat.ENCODING_PCM_8BIT.audioEncodingLabel())
        assertEquals("PCM 16-bit", AudioFormat.ENCODING_PCM_16BIT.audioEncodingLabel())
        assertEquals("PCM Float", AudioFormat.ENCODING_PCM_FLOAT.audioEncodingLabel())
        assertEquals("PCM 24-bit", AudioFormat.ENCODING_PCM_24BIT_PACKED.audioEncodingLabel())
        assertEquals("PCM 32-bit", AudioFormat.ENCODING_PCM_32BIT.audioEncodingLabel())
    }

    @Test
    fun `other encodings fall back to the raw encoding id`() {
        assertEquals("encoding=5", AudioFormat.ENCODING_AC3.audioEncodingLabel())
        assertEquals("encoding=-1", (-1).audioEncodingLabel())
    }
}
