@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.audio.reactive

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioReactiveTest {

    @After
    fun resetReactiveState() {
        AudioReactive.resetForTest()
    }

    @Test
    fun `native effective volume scales reactive level`() {
        val fullLevel = reactiveLevelForVolume(effectiveVolume = 1f)
        val reducedLevel = reactiveLevelForVolume(effectiveVolume = 0.25f)

        assertTrue(fullLevel > 0.95f)
        assertTrue(reducedLevel in 0.45f..0.70f)
        assertTrue(reducedLevel < fullLevel)
    }

    @Test
    fun `zero native effective volume keeps loud pcm quiet`() {
        AudioReactive.teeSink.flush(44_100, 1, C.ENCODING_PCM_16BIT)
        AudioReactive.enabled = true

        AudioReactive.handlePcmBuffer(
            buffer = pcm16(Short.MAX_VALUE, Short.MAX_VALUE),
            effectiveVolume = 0f,
            nowNs = START_NS
        )

        assertEquals(0f, AudioReactive.level.value, 0.0001f)
        assertEquals(0f, AudioReactive.beat.value, 0.0001f)
    }

    @Test
    fun `beat decay follows elapsed time instead of buffer count`() {
        val shortElapsedBeat = beatAfterSilence(elapsedNs = FRAME_NS)
        val longElapsedBeat = beatAfterSilence(elapsedNs = FRAME_NS * 5)

        assertTrue(shortElapsedBeat > 0.85f)
        assertTrue(longElapsedBeat > 0f)
        assertTrue(longElapsedBeat < shortElapsedBeat * 0.75f)
    }

    @Test
    fun `disabled reactive ignores incoming pcm`() {
        AudioReactive.teeSink.flush(44_100, 1, C.ENCODING_PCM_16BIT)
        AudioReactive.enabled = false

        AudioReactive.handlePcmBuffer(
            buffer = pcm16(Short.MAX_VALUE, Short.MAX_VALUE),
            effectiveVolume = 1f,
            nowNs = START_NS
        )

        assertEquals(0f, AudioReactive.level.value, 0.0001f)
        assertEquals(0f, AudioReactive.beat.value, 0.0001f)
    }

    @Test
    fun `all supported encodings measure equivalent levels without consuming input`() {
        val formats = listOf(
            C.ENCODING_PCM_8BIT to ByteBuffer.wrap(byteArrayOf(192.toByte(), 64)),
            C.ENCODING_PCM_16BIT to halfLevelPcm16(ByteOrder.LITTLE_ENDIAN),
            C.ENCODING_PCM_16BIT_BIG_ENDIAN to halfLevelPcm16(ByteOrder.BIG_ENDIAN),
            C.ENCODING_PCM_24BIT to ByteBuffer.wrap(byteArrayOf(0, 0, 64, 0, 0, 192.toByte())),
            C.ENCODING_PCM_24BIT_BIG_ENDIAN to ByteBuffer.wrap(byteArrayOf(64, 0, 0, 192.toByte(), 0, 0)),
            C.ENCODING_PCM_32BIT to halfLevelPcm32(ByteOrder.LITTLE_ENDIAN),
            C.ENCODING_PCM_32BIT_BIG_ENDIAN to halfLevelPcm32(ByteOrder.BIG_ENDIAN),
            C.ENCODING_PCM_FLOAT to ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
                putFloat(0.5f)
                putFloat(-0.5f)
                flip()
            },
            C.ENCODING_INVALID to halfLevelPcm16(ByteOrder.LITTLE_ENDIAN)
        )
        formats.forEach { (encoding, buffer) ->
            AudioReactive.resetForTest()
            AudioReactive.teeSink.flush(44_100, 1, encoding)
            AudioReactive.enabled = true
            val inputOrder = buffer.order()
            AudioReactive.handlePcmBuffer(buffer, 1f, START_NS)
            assertEquals(0.7871068f, AudioReactive.level.value, 0.0001f)
            assertEquals(0, buffer.position())
            assertEquals(inputOrder, buffer.order())
        }
    }

    @Test
    fun `float reactive treats non finite samples as silence`() {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(Float.NaN)
            putFloat(Float.POSITIVE_INFINITY)
            flip()
        }
        AudioReactive.teeSink.flush(44_100, 1, C.ENCODING_PCM_FLOAT)
        AudioReactive.enabled = true

        AudioReactive.handlePcmBuffer(buffer, 1f, START_NS)

        assertEquals(0f, AudioReactive.level.value, 0f)
        assertEquals(0f, AudioReactive.beat.value, 0f)
        assertEquals(0, buffer.position())
    }

    private fun halfLevelPcm16(order: ByteOrder): ByteBuffer = ByteBuffer.allocate(4).order(order).apply {
        putShort(16_384)
        putShort(-16_384)
        flip()
    }

    private fun halfLevelPcm32(order: ByteOrder): ByteBuffer = ByteBuffer.allocate(8).order(order).apply {
        putInt(1_073_741_824)
        putInt(-1_073_741_824)
        flip()
    }

    private fun reactiveLevelForVolume(effectiveVolume: Float): Float {
        AudioReactive.resetForTest()
        AudioReactive.teeSink.flush(44_100, 1, C.ENCODING_PCM_16BIT)
        AudioReactive.enabled = true
        AudioReactive.handlePcmBuffer(
            buffer = pcm16(Short.MAX_VALUE, Short.MAX_VALUE),
            effectiveVolume = effectiveVolume,
            nowNs = START_NS
        )
        return AudioReactive.level.value
    }

    private fun beatAfterSilence(elapsedNs: Long): Float {
        AudioReactive.resetForTest()
        AudioReactive.teeSink.flush(44_100, 1, C.ENCODING_PCM_16BIT)
        AudioReactive.enabled = true
        AudioReactive.handlePcmBuffer(
            buffer = pcm16(Short.MAX_VALUE, Short.MAX_VALUE),
            effectiveVolume = 1f,
            nowNs = START_NS
        )
        AudioReactive.handlePcmBuffer(
            buffer = pcm16(0, 0),
            effectiveVolume = 1f,
            nowNs = START_NS + elapsedNs
        )
        return AudioReactive.beat.value
    }

    private fun pcm16(vararg samples: Short): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(buffer::putShort)
        buffer.flip()
        return buffer
    }

    private companion object {
        const val START_NS = 1_000_000_000L
        const val FRAME_NS = 16_666_667L
    }
}
