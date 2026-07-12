@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player

import androidx.media3.common.C
import moe.ouom.neriplayer.core.player.effects.AudioReactive
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
