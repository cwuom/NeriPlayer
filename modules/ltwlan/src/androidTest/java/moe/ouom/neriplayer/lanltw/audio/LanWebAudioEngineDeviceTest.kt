package moe.ouom.neriplayer.lanltw.audio

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.model.lan.LanAction
import moe.ouom.neriplayer.data.model.lan.LanMessage
import moe.ouom.neriplayer.lanltw.media.LanReadableMedia
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanWebAudioEngineDeviceTest {
    @Test
    fun rejectsLateEndOfTrackPlanAndPlaysNextValidPlan() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = silentPcmWave()
        val media = LanReadableMedia(wav.size.toLong()) { wav.inputStream() }
        withContext(Dispatchers.Main) {
            val states = MutableStateFlow(LanAudioState())
            val ended = CompletableDeferred<Unit>()
            var failure: String? = null
            val engine = LanWebAudioEngine(
                context,
                onState = { states.value = it },
                onEnded = { ended.complete(Unit) },
                onFailure = { failure = it }
            )
            try {
                assertTrue(withTimeout(25_000) {
                    engine.load(TRACK_KEY, media, position = 0.0, expectedDurationMs = DURATION_SECONDS * 1000L)
                })
                assertTrue(engine.schedule(playPlan(revision = 1, position = 0.0, delayMs = 600), 0.0, 0))
                withTimeout(5_000) { states.first { it.playing } }

                val expired = playPlan(revision = 2, position = DURATION_SECONDS - 0.1, delayMs = -500)
                assertFalse(engine.schedule(expired, 0.0, 0))
                withTimeout(5_000) { states.first { !it.playing } }
                assertFalse(ended.isCompleted)

                val valid = playPlan(revision = 3, position = DURATION_SECONDS - 1.0, delayMs = 600)
                assertTrue(engine.schedule(valid, 0.0, 0))
                withTimeout(8_000) {
                    ended.await()
                    states.first { !it.playing && it.positionSeconds >= DURATION_SECONDS - 0.05 }
                }
                assertNull(failure)
            } finally {
                engine.destroy()
            }
            assertFalse(engine.ready(TRACK_KEY))
        }
    }

    private fun playPlan(revision: Long, position: Double, delayMs: Long) = LanMessage(
        type = "SCHEDULED_ACTION",
        scheduledAction = LanAction("PLAY", TRACK_KEY, position),
        serverTimeToExecute = SystemClock.elapsedRealtimeNanos() / 1_000_000.0 + delayMs,
        revision = revision
    )

    private fun silentPcmWave(): ByteArray {
        val sampleRate = 16_000
        val frameBytes = Short.SIZE_BYTES
        val dataBytes = DURATION_SECONDS * sampleRate * frameBytes
        return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(capacity() - 8)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * frameBytes)
            putShort(frameBytes.toShort())
            putShort(Short.SIZE_BITS.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
    }

    private companion object {
        const val TRACK_KEY = "instrumentation-silence"
        const val DURATION_SECONDS = 3
    }
}
