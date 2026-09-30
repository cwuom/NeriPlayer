package moe.ouom.neriplayer.core.player.audio.processing

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class StereoBalanceAudioProcessorTest {
    @Test
    fun `centered pcm16 passes samples and trailing bytes unchanged`() {
        val processor = processor(C.ENCODING_PCM_16BIT, 0f)
        val input = ByteBuffer.allocateDirect(5).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1234).putShort(-2345).put(0x7f)
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(1234, output.short.toInt())
        assertEquals(-2345, output.short.toInt())
        assertEquals(0x7f, output.get().toInt())
        assertEquals(0, input.remaining())
    }

    @Test
    fun `pcm16 balance attenuates one channel and preserves trailing bytes`() {
        val processor = processor(C.ENCODING_PCM_16BIT, -0.5f)
        val input = ByteBuffer.allocateDirect(5).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(2000).putShort(-2000).put(0x7f)
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(2000, output.short.toInt())
        assertEquals(-1000, output.short.toInt())
        assertEquals(0x7f, output.get().toInt())
    }

    @Test
    fun `float balance reads little endian without losing buffer order or trailing bytes`() {
        val processor = processor(C.ENCODING_PCM_FLOAT, 0.5f)
        val input = ByteBuffer.allocateDirect(9).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.8f).putFloat(-0.6f).put(0x7f)
        input.flip()
        input.order(ByteOrder.BIG_ENDIAN)

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(0.4f, output.float, 0.0001f)
        assertEquals(-0.6f, output.float, 0.0001f)
        assertEquals(0x7f, output.get().toInt())
        assertEquals(ByteOrder.BIG_ENDIAN, input.order())
    }

    @Test
    fun `unsupported channel counts and encodings bypass processing`() {
        val processor = StereoBalanceAudioProcessor { 0f }
        assertEquals(AudioFormat.NOT_SET, processor.configure(AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)))
        assertEquals(AudioFormat.NOT_SET, processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_24BIT)))
    }

    private fun processor(encoding: Int, balance: Float): StereoBalanceAudioProcessor =
        StereoBalanceAudioProcessor { balance }.apply {
            configure(AudioFormat(48_000, 2, encoding))
            flush(StreamMetadata.DEFAULT)
        }

    @Test
    fun `center balance keeps both channels at full gain`() {
        val gains = stereoBalanceGains(0f)

        assertEquals(1f, gains.left, 0.0001f)
        assertEquals(1f, gains.right, 0.0001f)
    }

    @Test
    fun `left balance attenuates right channel only`() {
        val gains = stereoBalanceGains(-0.4f)

        assertEquals(1f, gains.left, 0.0001f)
        assertEquals(0.6f, gains.right, 0.0001f)
    }

    @Test
    fun `right balance attenuates left channel only`() {
        val gains = stereoBalanceGains(0.75f)

        assertEquals(0.25f, gains.left, 0.0001f)
        assertEquals(1f, gains.right, 0.0001f)
    }
}
