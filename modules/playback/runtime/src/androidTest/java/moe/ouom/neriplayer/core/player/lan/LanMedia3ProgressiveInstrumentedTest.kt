@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lan

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DefaultDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.player.lan.relay.LanRelayOrigin
import moe.ouom.neriplayer.data.model.lan.LanAction
import moe.ouom.neriplayer.lanltw.media.LanMediaServer
import moe.ouom.neriplayer.lanltw.media.LanMediaStreamInput
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanMedia3ProgressiveInstrumentedTest {
    @Test(timeout = 35_000L)
    fun startsPlayingBeforeTheFullStreamAndSeeksByRange() = exerciseSeek(releaseInitialRead = true)

    @Test(timeout = 35_000L)
    fun seekCancelsAStalledPreviousRequestAndOpensARange() = exerciseSeek(releaseInitialRead = false)

    @Test(timeout = 35_000L)
    fun relayKeepsAnEndSeekPausedAndFinishesAnEndSeekWhilePlaying() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File.createTempFile("lan-endpoint-", ".wav", context.cacheDir)
        val server = LanMediaServer()
        val id = UUID.randomUUID().toString()
        val ended = AtomicInteger()
        try {
            writeWav(wav, ENDPOINT_WAV_SIZE)
            LanRelayOrigin.open(DefaultDataSource.Factory(context), Uri.fromFile(wav), "audio/wav").use { origin ->
                server.start(30_000, true)
                val token = server.grant("endpoint-test")
                server.publishStream(id, origin.mime, origin.byteLength, origin.window::open)
                val url = "http://127.0.0.1:${server.listeningPort}/media/$id".toHttpUrl()
                withContext(Dispatchers.Main) {
                    val audio = LanMedia3AudioEngine(context, false, { _, _, _ -> }, {}, { ended.incrementAndGet() }, {})
                    try {
                        assertTrue("first load at a longer catalog endpoint", audio.prepare(id, url, token, origin.mime, 15_000L))
                        assertTrue("first end seek ${audio.diagnostics()}", audio.positionSeconds in 9.7..10.0)
                        assertFalse(audio.playing)
                        assertEquals(0, ended.get())

                        assertTrue(audio.prepare(id, url, token, origin.mime, 0))
                        val pauseAt = SystemClock.elapsedRealtime() + 150L
                        assertTrue(audio.schedule(LanAction("PAUSE", "local:fixture", 10.0), pauseAt.toDouble(), 0.0, 0))
                        withTimeout(5_000L) { while (!audio.ready(id)) delay(25) }
                        assertTrue("paused at the end ${audio.diagnostics()}", audio.positionSeconds in 9.7..10.0)
                        assertFalse(audio.playing)
                        assertEquals(0, ended.get())

                        assertTrue(audio.prepare(id, url, token, origin.mime, 0))
                        audio.play()
                        withTimeout(5_000L) { while (!audio.playing || audio.positionSeconds < 0.2) delay(25) }
                        assertTrue(audio.prepare(id, url, token, origin.mime, 15_000L))
                        val playAt = SystemClock.elapsedRealtime() + 300L
                        assertTrue(audio.schedule(LanAction("PLAY", "local:fixture", 15.0), playAt.toDouble(), 0.0, 0))
                        withTimeout(5_000L) { while (ended.get() < 1) delay(25) }
                        assertTrue("finished at the end ${audio.diagnostics()}", audio.positionSeconds in 9.7..10.0)
                        assertFalse(audio.playing)
                        assertEquals(1, ended.get())
                    } finally { audio.release() }
                }
            }
        } finally {
            server.stop()
            wav.delete()
        }
    }

    private fun exerciseSeek(releaseInitialRead: Boolean) = runBlocking {
        val server = LanMediaServer()
        val resource = UUID.randomUUID().toString()
        val initialReadLimit = 1_280L * 1024
        val rangeOffset = AtomicLong()
        val unblockInitialRead = CountDownLatch(1)
        val initialReadStalled = CountDownLatch(1)
        val header = wavHeader()
        val totalLength = header.size + WAV_DATA_SIZE.toLong()
        try {
            server.start(30_000, true)
            val token = server.grant("loopback-test")
            server.publishStream(resource, "audio/wav", totalLength) { offset, length ->
                if (offset > initialReadLimit) rangeOffset.set(offset)
                val limit = offset + (length ?: (totalLength - offset))
                val readGate = unblockInitialRead.takeIf { offset == 0L }
                LanMediaStreamInput(wavBytes(header, offset, limit, initialReadLimit, readGate, initialReadStalled), limit - offset)
            }
            val url = "http://127.0.0.1:${server.listeningPort}/media/$resource".toHttpUrl()
            withContext(Dispatchers.Main) {
                val audio = LanMedia3AudioEngine(InstrumentationRegistry.getInstrumentation().targetContext,
                    handleAudioFocus = false, onState = { _, _, _ -> }, onFormat = {}, onEnded = {}, onError = {})
                try {
                    assertTrue("initial PCM reached READY", audio.prepare(resource, url, token, "audio/wav", 0))
                    assertTrue("ready did not require the server to release the rest of the file", unblockInitialRead.count > 0)
                    val deadline = SystemClock.elapsedRealtime() + 500L
                    assertTrue(audio.schedule(LanAction("PLAY", "local:fixture", 0.0), deadline.toDouble(), 0.0, 0))
                    withTimeout(5_000L) { while (!audio.playing || audio.positionSeconds < 0.3) delay(25) }
                    if (!releaseInitialRead) withTimeout(4_000L) {
                        while (initialReadStalled.count > 0) delay(25)
                    }

                    audio.cancelSchedule()
                    if (releaseInitialRead) unblockInitialRead.countDown()
                    audio.seek(90_000L)
                    val seekReady = withTimeoutOrNull(8_000L) {
                        while (rangeOffset.get() <= initialReadLimit || !audio.ready(resource)) delay(25)
                        true
                    }
                    assertTrue("ranged seek did not reach READY, offset=${rangeOffset.get()} ${audio.diagnostics()}", seekReady == true)
                    assertTrue("seek reached the unbuffered section", audio.positionSeconds >= 89.9)
                    if (!releaseInitialRead) assertTrue("seek must not wait for the old connection", unblockInitialRead.count > 0)
                } finally {
                    unblockInitialRead.countDown()
                    audio.release()
                }
            }
        } finally {
            unblockInitialRead.countDown()
            server.stop()
        }
    }

    private fun wavBytes(header: ByteArray, offset: Long, limit: Long, initialReadLimit: Long,
        readGate: CountDownLatch?, initialReadStalled: CountDownLatch): InputStream = object : InputStream() {
        private var cursor = offset

        override fun read(destination: ByteArray, start: Int, length: Int): Int {
            if (length == 0) return 0
            if (cursor >= limit) return -1
            if (readGate != null && cursor >= initialReadLimit) {
                initialReadStalled.countDown()
                if (!readGate.await(30, TimeUnit.SECONDS)) return -1
            }
            val end = if (readGate != null && readGate.count > 0) minOf(limit, initialReadLimit) else limit
            val count = minOf(length.toLong(), end - cursor).toInt()
            destination.fill(0, start, start + count)
            if (cursor < header.size) {
                val headerStart = cursor.toInt()
                header.copyInto(destination, start, headerStart, minOf(headerStart + count, header.size))
            }
            cursor += count
            return count
        }

        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) == 1) byte[0].toInt() and 0xFF else -1
        }
    }

    private fun writeWav(file: File, size: Int) {
        file.outputStream().buffered().use { output ->
            output.write(wavHeader(size))
            val chunk = ByteArray(32 * 1024)
            var remaining = size
            while (remaining > 0) {
                val count = minOf(chunk.size, remaining)
                output.write(chunk, 0, count)
                remaining -= count
            }
        }
    }

    private fun wavHeader(dataSize: Int = WAV_DATA_SIZE): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(WAV_SAMPLE_RATE).putInt(WAV_SAMPLE_RATE * 2)
        .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize).array()

    private companion object {
        const val WAV_SAMPLE_RATE = 48_000
        const val WAV_DATA_SIZE = WAV_SAMPLE_RATE * 2 * 120
        const val ENDPOINT_WAV_SIZE = WAV_SAMPLE_RATE * 2 * 10
    }
}
