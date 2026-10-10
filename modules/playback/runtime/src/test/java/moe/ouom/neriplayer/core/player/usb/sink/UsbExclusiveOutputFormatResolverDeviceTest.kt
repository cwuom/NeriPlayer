package moe.ouom.neriplayer.core.player.usb.sink

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.C
import moe.ouom.neriplayer.data.model.settings.usb.UsbExclusivePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class UsbExclusiveOutputFormatResolverDeviceTest {
    private val headsetDescription = "rate=44100 channels=2 bits=16 subslot=2 " +
        "rateMode=follow_source bitMode=auto policy=closest_supported"

    @Test
    fun `resolution fails without an audio manager or usb sink`() {
        val noAudioManager = mock(Context::class.java)
        val bluetoothOnly = contextWith(
            output(id = 1, type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, productName = "Buds"),
            output(id = 2, type = AudioDeviceInfo.TYPE_USB_DEVICE, productName = "Mic", isSink = false)
        )

        assertEquals("audio_manager_unavailable", resolve(noAudioManager).error)
        assertEquals("no_selected_system_usb_audio_output", resolve(bluetoothOnly).error)
    }

    @Test
    fun `explicit device keys select the exactly matching usb output`() {
        val context = contextWith(
            output(id = 1, type = AudioDeviceInfo.TYPE_USB_DEVICE, productName = "FiiO K7", sampleRates = intArrayOf(96_000)),
            output(id = 2, type = AudioDeviceInfo.TYPE_USB_HEADSET, productName = "USB Headset", sampleRates = intArrayOf(48_000)),
            output(id = 3, type = AudioDeviceInfo.TYPE_USB_ACCESSORY, productName = "FiiO K7", sampleRates = intArrayOf(192_000)),
            output(id = 4, type = AudioDeviceInfo.TYPE_USB_DEVICE, productName = null, sampleRates = intArrayOf(32_000))
        )

        val headset = resolve(context, "usb:1:2:usb_headset").format!!
        val dac = resolve(context, "usb:9:9:fiio_k7").format!!

        assertEquals(headsetDescription, headset.description)
        assertEquals(listOf(48_000), headset.alternativeSampleRates)
        assertEquals(listOf(96_000), dac.alternativeSampleRates)
        assertEquals("ambiguous_multiple_system_usb_audio_outputs", resolve(context, "auto").error)
        assertEquals("ambiguous_multiple_system_usb_audio_outputs", resolve(context, "  ").error)
        listOf("usb:1:2:missing", "usb:1:2", "hid:1:2:fiio_k7", "usb:1:2: ").forEach { key ->
            assertEquals(key, "no_selected_system_usb_audio_output", resolve(context, key).error)
        }
    }

    @Test
    fun `pcm encodings map to source bit depth and sample width`() {
        val expected = mapOf(
            C.ENCODING_PCM_8BIT to (8 to 1),
            C.ENCODING_PCM_16BIT to (16 to 2),
            C.ENCODING_PCM_16BIT_BIG_ENDIAN to (16 to 2),
            C.ENCODING_PCM_24BIT to (24 to 3),
            C.ENCODING_PCM_24BIT_BIG_ENDIAN to (24 to 3),
            C.ENCODING_PCM_32BIT to (32 to 4),
            C.ENCODING_PCM_32BIT_BIG_ENDIAN to (32 to 4),
            C.ENCODING_PCM_FLOAT to (32 to 4)
        )

        expected.forEach { (encoding, depthAndWidth) ->
            assertEquals(
                "encoding=$encoding",
                depthAndWidth,
                UsbExclusiveOutputFormatResolver.sourceBitDepthForEncoding(encoding) to
                    UsbExclusiveOutputFormatResolver.pcmBytesPerSampleForEncoding(encoding)
            )
        }
        assertNull(UsbExclusiveOutputFormatResolver.sourceBitDepthForEncoding(C.ENCODING_INVALID))
        assertNull(UsbExclusiveOutputFormatResolver.pcmBytesPerSampleForEncoding(C.ENCODING_INVALID))
    }

    @Test
    fun `compatible sample rates prefer the source rate family`() {
        assertEquals(
            listOf(44_100, 88_200, 48_000, 32_000),
            UsbExclusiveOutputFormatResolver.nativeSampleRateCandidates(
                preferences = UsbExclusivePreferences(),
                inputSampleRate = 44_100,
                reportedSampleRates = listOf(32_000, 48_000, 0, 88_200)
            )
        )
    }

    @Test
    fun `output descriptions need every numeric field`() {
        val valid = "rate=96000 channels=2 bits=24 subslot=4 rateMode=auto"
        val parsed = UsbExclusiveOutputFormatResolver.outputFormatFromDescription(valid, bufferDurationMs = 250)!!

        assertEquals(ResolvedUsbOutputFormat(96_000, 2, 24, 4, 250, valid), parsed)
        listOf(
            "channels=2 bits=24 subslot=4",
            "rate=fast channels=2 bits=24 subslot=4",
            "rate=96000 bits=24 subslot=4",
            "rate=96000 channels=two bits=24 subslot=4",
            "rate=96000 channels=2 subslot=4",
            "rate=96000 channels=2 bits=deep subslot=4",
            "rate=96000 channels=2 bits=24",
            "rate=96000 channels=2 bits=24 subslot=wide"
        ).forEach { description ->
            assertNull(description, UsbExclusiveOutputFormatResolver.outputFormatFromDescription(description, 250))
        }
    }

    @Test
    fun `equivalent outputs must agree on every native format field`() {
        val current = "rate=96000 channels=2 bits=24 subslot=4 rateMode=follow_source"

        assertTrue(UsbExclusiveOutputFormatResolver.canReuseEquivalentOutput(current, current))
        assertTrue(
            UsbExclusiveOutputFormatResolver.canReuseEquivalentOutput(
                current,
                "rate=96000 channels=2 bits=24 subslot=4 rateMode=96000"
            )
        )
        listOf(
            "rate=48000 channels=2 bits=24 subslot=4",
            "rate=96000 channels=1 bits=24 subslot=4",
            "rate=96000 channels=2 bits=32 subslot=4",
            "rate=96000 channels=2 bits=24 subslot=3",
            "garbage"
        ).forEach { preferred ->
            assertFalse(preferred, UsbExclusiveOutputFormatResolver.canReuseEquivalentOutput(current, preferred))
        }
        assertFalse(UsbExclusiveOutputFormatResolver.canReuseEquivalentOutput("garbage", current))
    }

    private fun resolve(context: Context, deviceKey: String? = null) = UsbExclusiveOutputFormatResolver.resolve(
        context = context,
        inputSampleRate = 44_100,
        inputChannelCount = 2,
        inputEncoding = C.ENCODING_PCM_16BIT,
        resolvedDeviceKey = deviceKey
    )

    private fun contextWith(vararg outputs: AudioDeviceInfo): Context {
        val audioManager = mock(AudioManager::class.java)
        `when`(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(arrayOf(*outputs))
        return mock(Context::class.java).also {
            `when`(it.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        }
    }

    private fun output(
        id: Int,
        type: Int,
        productName: String?,
        isSink: Boolean = true,
        sampleRates: IntArray = intArrayOf(44_100)
    ): AudioDeviceInfo = mock(AudioDeviceInfo::class.java).also {
        `when`(it.id).thenReturn(id)
        `when`(it.type).thenReturn(type)
        `when`(it.isSink).thenReturn(isSink)
        `when`(it.productName).thenReturn(productName)
        `when`(it.sampleRates).thenReturn(sampleRates)
        `when`(it.channelCounts).thenReturn(intArrayOf(2))
    }
}
