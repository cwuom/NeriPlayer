package moe.ouom.neriplayer.core.player.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeNormalizationAudioProcessorTest {
    @Test
    fun `pcm analysis returns rms peak and sample count`() {
        val buffer = pcm16Buffer(16_384, -16_384, 0, 8_192)

        val stats = analyzePcm16(buffer)

        assertEquals(4, stats.sampleCount)
        assertEquals(0.5f, stats.peak, 0.0001f)
        assertEquals(0.375f, stats.rms, 0.0001f)
    }

    @Test
    fun `quiet tracks are boosted by no more than six decibels`() {
        val normalizer = Pcm16VolumeNormalizer()

        val gain = normalizer.observeAndResolveTargetGain(
            Pcm16LevelStats(rms = 0.01f, peak = 0.02f, sampleCount = 48_000)
        )

        assertEquals(1.9952623f, gain, 0.0001f)
    }

    @Test
    fun `loud tracks are attenuated toward target rms`() {
        val normalizer = Pcm16VolumeNormalizer()

        val gain = normalizer.observeAndResolveTargetGain(
            Pcm16LevelStats(rms = 0.5f, peak = 0.7f, sampleCount = 48_000)
        )

        assertEquals(0.251785f, gain, 0.0001f)
    }

    @Test
    fun `peak ceiling limits boost before clipping`() {
        val normalizer = Pcm16VolumeNormalizer()

        val gain = normalizer.observeAndResolveTargetGain(
            Pcm16LevelStats(rms = 0.08f, peak = 0.8f, sampleCount = 48_000)
        )

        assertEquals(0.9929103f, gain, 0.0001f)
    }

    @Test
    fun `actual output respects peak ceiling after quiet boost`() {
        val normalizer = Pcm16VolumeNormalizer()
        val quietInput = repeatedPcm16Buffer(sample = 320, count = 2_000)
        normalizer.process(
            inputBuffer = quietInput,
            outputBuffer = ByteBuffer.allocate(quietInput.capacity()).order(ByteOrder.LITTLE_ENDIAN),
            sampleRate = 100,
            channelCount = 1
        )
        val loudInput = repeatedPcm16Buffer(sample = Short.MAX_VALUE.toInt(), count = 100)
        val loudOutput = ByteBuffer.allocate(loudInput.capacity()).order(ByteOrder.LITTLE_ENDIAN)

        normalizer.process(
            inputBuffer = loudInput,
            outputBuffer = loudOutput,
            sampleRate = 100,
            channelCount = 1
        )
        loudOutput.flip()

        val peakCeilingSample = (32_768f * 0.7943282f).roundToInt()
        assertTrue(maxAbsoluteSample(loudOutput) <= peakCeilingSample)
    }

    @Test
    fun `future peak does not duck the start of the next block`() {
        val normalizer = Pcm16VolumeNormalizer()
        val quietInput = repeatedPcm16Buffer(sample = 320, count = 20_000)
        normalizer.process(
            inputBuffer = quietInput,
            outputBuffer = ByteBuffer.allocate(quietInput.capacity()).order(ByteOrder.LITTLE_ENDIAN),
            sampleRate = 1_000,
            channelCount = 1
        )
        val transitionInput = ByteBuffer.allocate(200)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                repeat(99) { putShort(320) }
                putShort(Short.MAX_VALUE)
                flip()
            }
        val transitionOutput = ByteBuffer.allocate(transitionInput.capacity())
            .order(ByteOrder.LITTLE_ENDIAN)

        normalizer.process(
            inputBuffer = transitionInput,
            outputBuffer = transitionOutput,
            sampleRate = 1_000,
            channelCount = 1
        )
        transitionOutput.flip()

        assertTrue(abs(transitionOutput.short.toInt()) > 500)
        assertTrue(maxAbsoluteSample(transitionOutput) <= (32_768f * 0.7943282f).roundToInt())
    }

    @Test
    fun `silence keeps unity gain`() {
        val normalizer = Pcm16VolumeNormalizer()

        val gain = normalizer.observeAndResolveTargetGain(
            Pcm16LevelStats(rms = 0.0001f, peak = 0.0002f, sampleCount = 48_000)
        )

        assertEquals(1f, gain, 0.0001f)
    }

    @Test
    fun `smoothing factor stays within stable bounds`() {
        assertEquals(0f, smoothingFactor(0f, 1f), 0f)
        assertEquals(1f, smoothingFactor(1f, 0f), 0f)
        assertTrue(smoothingFactor(0.1f, 1f) in 0f..1f)
    }

    @Test
    fun `disabled processor keeps pcm bit exact`() {
        val processor = configuredProcessor(enabled = false)
        val input = directPcm16Buffer(1_024, -2_048, Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt())
        val expected = input.duplicate().order(ByteOrder.nativeOrder())

        processor.queueInput(input)
        val output = processor.getOutput()

        assertEquals(expected.remaining(), output.remaining())
        while (expected.hasRemaining()) {
            assertEquals(expected.get(), output.get())
        }
    }

    @Test
    fun `flush preserves current track gain estimate`() {
        val processor = configuredProcessor(enabled = true)
        val warmup = repeatedDirectPcm16Buffer(sample = 320, count = 2_000)
        processor.queueInput(warmup)
        processor.getOutput()
        processor.flush()
        val input = repeatedDirectPcm16Buffer(sample = 320, count = 100)

        processor.queueInput(input)
        val output = processor.getOutput()

        assertTrue(averageAbsoluteSample(output) > 500f)
    }

    @Test
    fun `new generation resets accumulated track gain`() {
        var state = PlaybackVolumeNormalizationSnapshot(enabled = true, generation = 1L)
        val processor = VolumeNormalizationAudioProcessor { state }
        processor.configure(AudioFormat(100, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.queueInput(repeatedDirectPcm16Buffer(sample = 320, count = 2_000))
        processor.getOutput()
        state = state.copy(generation = 2L)

        processor.queueInput(repeatedDirectPcm16Buffer(sample = 320, count = 10))
        val output = processor.getOutput()

        assertTrue(averageAbsoluteSample(output) < 400f)
    }

    private fun pcm16Buffer(vararg samples: Int): ByteBuffer {
        return ByteBuffer.allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                samples.forEach { putShort(it.toShort()) }
                flip()
            }
    }

    private fun repeatedPcm16Buffer(sample: Int, count: Int): ByteBuffer {
        return ByteBuffer.allocate(count * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                repeat(count) { putShort(sample.toShort()) }
                flip()
            }
    }

    private fun directPcm16Buffer(vararg samples: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(samples.size * 2)
            .order(ByteOrder.nativeOrder())
            .apply {
                samples.forEach { putShort(it.toShort()) }
                flip()
            }
    }

    private fun repeatedDirectPcm16Buffer(sample: Int, count: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(count * 2)
            .order(ByteOrder.nativeOrder())
            .apply {
                repeat(count) { putShort(sample.toShort()) }
                flip()
            }
    }

    private fun configuredProcessor(enabled: Boolean): VolumeNormalizationAudioProcessor {
        val processor = VolumeNormalizationAudioProcessor {
            PlaybackVolumeNormalizationSnapshot(enabled = enabled, generation = 1L)
        }
        processor.configure(AudioFormat(100, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        return processor
    }

    private fun maxAbsoluteSample(buffer: ByteBuffer): Int {
        val samples = buffer.duplicate().order(buffer.order())
        var peak = 0
        while (samples.remaining() >= 2) {
            peak = maxOf(peak, abs(samples.short.toInt()))
        }
        return peak
    }

    private fun averageAbsoluteSample(buffer: ByteBuffer): Float {
        val samples = buffer.duplicate().order(buffer.order())
        var sum = 0L
        var count = 0
        while (samples.remaining() >= 2) {
            sum += abs(samples.short.toInt())
            count++
        }
        return if (count == 0) 0f else sum.toFloat() / count
    }
}
