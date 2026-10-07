package moe.ouom.neriplayer.core.player.audio.effects

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsInactiveReason
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute
import moe.ouom.neriplayer.data.model.playback.effects.NeriDspParams
import moe.ouom.neriplayer.data.model.playback.effects.neutralDspParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioEffectsAudioSinkTest {
    private val runtime = FakeRuntime()
    private val engine = FakeEngine()
    private val delegate = RecordingSink()

    @Test
    fun `inactive effects pass the decoder buffer straight through`() {
        val sink = sink()
        val input = pcm(1, 2, 3, 4)

        assertTrue(sink.handleBuffer(input, 0L, 1))

        assertSame(input, delegate.received.single())
        assertEquals(0, engine.processCalls)
    }

    @Test
    fun `active effects hand processed data downstream and consume input only once it is drained`() {
        runtime.activate()
        delegate.acceptOnCall = 2
        val sink = sink()
        val input = pcm(1, 2, 3, 4, 5, 6, 7, 8)

        assertFalse(sink.handleBuffer(input, 0L, 1))
        assertEquals(0, input.position())
        assertTrue(sink.handleBuffer(input, 0L, 1))

        assertEquals(input.limit(), input.position())
        assertEquals(1, engine.processCalls)
        assertSame(delegate.received[0], delegate.received[1])
        assertArrayEquals(byteArrayOf(2, 3, 4, 5, 6, 7, 8, 9), delegate.contents.last())
        assertEquals(1f, engine.lastParams?.get(NeriDspParams.MASTER_ENABLED))
    }

    @Test
    fun `heap buffers are staged before reaching native code`() {
        runtime.activate()
        val sink = sink()
        val input = ByteBuffer.wrap(byteArrayOf(10, 20, 30, 40))

        assertTrue(sink.handleBuffer(input, 0L, 1))

        assertTrue(engine.lastInputWasDirect)
        assertArrayEquals(byteArrayOf(11, 21, 31, 41), delegate.contents.last())
    }

    @Test
    fun `bit perfect USB output is never processed`() {
        runtime.activate(usbNativeAllowed = false)
        val sink = sink(usbNative = true)
        val input = pcm(1, 2, 3, 4)

        assertTrue(sink.handleBuffer(input, 0L, 1))

        assertSame(input, delegate.received.single())
        assertEquals(0, engine.processCalls)
        assertEquals(AudioEffectsInactiveReason.USB_EXCLUSIVE, runtime.reasons.last())
    }

    @Test
    fun `USB output is processed when the user allows it`() {
        runtime.activate(usbNativeAllowed = true)
        val sink = sink(usbNative = true)

        assertTrue(sink.handleBuffer(pcm(1, 2, 3, 4), 0L, 1))

        assertEquals(1, engine.processCalls)
    }

    @Test
    fun `unsupported formats and missing native code fall back to passthrough`() {
        runtime.activate()
        val surround = sink(channelCount = 6)
        val input = pcm(1, 2, 3, 4)
        assertTrue(surround.handleBuffer(input, 0L, 1))
        assertSame(input, delegate.received.last())
        assertEquals(AudioEffectsInactiveReason.UNSUPPORTED_FORMAT, runtime.reasons.last())

        val unavailable = AudioEffectsAudioSink(delegate, engineFactory = { null }, runtime = runtime)
        unavailable.configure(format(), 0, null)
        val second = pcm(5, 6, 7, 8)
        assertTrue(unavailable.handleBuffer(second, 0L, 1))
        assertSame(second, delegate.received.last())
        assertEquals(AudioEffectsInactiveReason.NATIVE_UNAVAILABLE, runtime.reasons.last())
        assertFalse(runtime.nativeAvailable)
    }

    @Test
    fun `disabling keeps feeding native code until its fade out reports idle`() {
        runtime.activate()
        val sink = sink()
        sink.handleBuffer(pcm(1, 2, 3, 4), 0L, 1)
        runtime.deactivate()
        engine.idleAfterDisable = 2

        sink.handleBuffer(pcm(1, 2, 3, 4), 0L, 1)
        assertEquals(0f, engine.lastParams?.get(NeriDspParams.MASTER_ENABLED))
        sink.handleBuffer(pcm(1, 2, 3, 4), 0L, 1)
        val bypassed = pcm(9, 9, 9, 9)
        sink.handleBuffer(bypassed, 0L, 1)

        assertEquals(3, engine.processCalls)
        assertSame(bypassed, delegate.received.last())
    }

    @Test
    fun `flush resets the engine and drops the pending processed block`() {
        runtime.activate()
        delegate.acceptOnCall = Int.MAX_VALUE
        val sink = sink()
        val input = pcm(1, 2, 3, 4)
        assertFalse(sink.handleBuffer(input, 0L, 1))

        sink.flush()
        delegate.acceptOnCall = 0
        assertTrue(sink.handleBuffer(input, 0L, 1))

        assertEquals(1, engine.resets)
        assertEquals(2, engine.processCalls)
    }

    @Test
    fun `release frees the native engine`() {
        runtime.activate()
        val sink = sink()
        sink.handleBuffer(pcm(1, 2, 3, 4), 0L, 1)

        sink.reset()

        assertTrue(engine.released)
    }

    private fun sink(usbNative: Boolean = false, channelCount: Int = 2): AudioEffectsAudioSink =
        AudioEffectsAudioSink(
            delegate,
            usbNativeOutputActive = { usbNative },
            engineFactory = { engine },
            runtime = runtime
        ).also { it.configure(format(channelCount), 0, null) }

    private fun format(channelCount: Int = 2): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setChannelCount(channelCount)
        .setSampleRate(48_000)
        .build()

    private fun pcm(vararg bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes.size)
        .order(ByteOrder.nativeOrder())
        .apply {
            bytes.forEach { put(it.toByte()) }
            flip()
        }

    private class RecordingSink : ForwardingAudioSink(mock(AudioSink::class.java)) {
        val received = mutableListOf<ByteBuffer>()
        val contents = mutableListOf<ByteArray>()
        var acceptOnCall = 0
        private var calls = 0

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            calls += 1
            received += buffer
            contents += ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
            if (calls < acceptOnCall) return false
            buffer.position(buffer.limit())
            calls = 0
            return true
        }
    }

    private class FakeEngine : AudioEffectsDspEngine {
        var processCalls = 0
        var resets = 0
        var released = false
        var lastParams: FloatArray? = null
        var lastInputWasDirect = false
        var idleAfterDisable = Int.MAX_VALUE

        override fun configure(sampleRate: Int, channelCount: Int, encoding: Int): Boolean = true

        override fun setParams(params: FloatArray): Boolean {
            lastParams = params
            return true
        }

        override fun reset() {
            resets += 1
        }

        override fun process(input: ByteBuffer, inputOffset: Int, output: ByteBuffer, outputOffset: Int, size: Int): Int {
            processCalls += 1
            lastInputWasDirect = input.isDirect
            for (index in 0 until size) {
                output.put(outputOffset + index, (input.get(inputOffset + index) + 1).toByte())
            }
            val disabled = lastParams?.get(NeriDspParams.MASTER_ENABLED) == 0f
            if (disabled) idleAfterDisable -= 1
            return if (disabled && idleAfterDisable <= 0) AUDIO_EFFECTS_STATUS_IDLE else 0
        }

        override fun readStats(output: LongArray): Boolean = false

        override fun release() {
            released = true
        }
    }

    private class FakeRuntime : AudioEffectsSinkRuntime {
        private var current = snapshot(active = false, usbNativeAllowed = false, generation = 0L)
        val reasons = mutableListOf<AudioEffectsInactiveReason?>()
        var nativeAvailable = true

        fun activate(usbNativeAllowed: Boolean = false) {
            current = snapshot(active = true, usbNativeAllowed = usbNativeAllowed, generation = current.generation + 1)
        }

        fun deactivate() {
            current = snapshot(active = false, usbNativeAllowed = false, generation = current.generation + 1)
        }

        override fun snapshot(): AudioEffectsRuntimeSnapshot = current
        override fun publishEngineStats(stats: AudioEffectsEngineStats) = Unit
        override fun publishPathState(sinkReason: AudioEffectsInactiveReason?, nativeAvailable: Boolean) {
            reasons += sinkReason
            this.nativeAvailable = nativeAvailable
        }
        override fun elapsedRealtimeMs(): Long = 0L

        private fun snapshot(active: Boolean, usbNativeAllowed: Boolean, generation: Long) = AudioEffectsRuntimeSnapshot(
            generation = generation,
            active = active,
            params = neutralDspParams().also { it[NeriDspParams.MASTER_ENABLED] = if (active) 1f else 0f },
            usbNativeAllowed = usbNativeAllowed,
            route = AudioOutputRoute.WIRED,
            inactiveReason = if (active) null else AudioEffectsInactiveReason.DISABLED
        )
    }
}
