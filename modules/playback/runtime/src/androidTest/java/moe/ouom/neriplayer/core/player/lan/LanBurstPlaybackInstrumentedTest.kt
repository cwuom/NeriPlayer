@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lan

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.lanltw.media.LanMediaServer
import moe.ouom.neriplayer.lanltw.media.LanMediaStreamInput
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanBurstPlaybackInstrumentedTest {
    @Test(timeout = 45_000L)
    fun waitsForSustainedAudioAfterSourceStarvation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val flac = instrumentation.context.assets.open("lan-burst-96k.flac").use { it.readBytes() }
        val server = LanMediaServer()
        val media = UUID.randomUUID().toString()
        val openCount = AtomicInteger()
        val burst = CountDownLatch(1)
        val remainder = CountDownLatch(1)
        val burstDelivered = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val buffering = CompletableDeferred<Double>()
        val resumed = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        try {
            server.start(30_000, true)
            val token = server.grant("member")
            server.publishStream(media, "audio/flac", flac.size.toLong()) { offset, limit ->
                openCount.incrementAndGet()
                val size = (limit ?: (flac.size - offset)).toInt()
                val stream = object : ByteArrayInputStream(flac, offset.toInt(), size) {
                    override fun read(destination: ByteArray, start: Int, length: Int): Int {
                        if (length == 0 || pos >= count) return super.read(destination, start, length)
                        val end = when {
                            pos < FIRST_BURST_END -> FIRST_BURST_END
                            pos < SECOND_BURST_END -> {
                                check(burst.await(15, TimeUnit.SECONDS))
                                SECOND_BURST_END
                            }
                            else -> {
                                burstDelivered.complete(Unit)
                                check(remainder.await(15, TimeUnit.SECONDS))
                                count
                            }
                        }
                        return super.read(destination, start, minOf(length, end - pos))
                    }
                }
                LanMediaStreamInput(stream, size.toLong())
            }
            val url = "http://127.0.0.1:${server.listeningPort}/media/$media".toHttpUrl()
            withContext(Dispatchers.Main) {
                var lastStatus = Player.STATE_IDLE
                var stalls = 0
                val audio = LanMedia3AudioEngine(instrumentation.targetContext, false, { state, status, requested ->
                    if (state.playing) {
                        started.complete(Unit)
                        if (buffering.isCompleted) resumed.complete(Unit)
                    } else if (started.isCompleted && requested && status == Player.STATE_BUFFERING && status != lastStatus) {
                        stalls++
                        buffering.complete(state.positionSeconds)
                    }
                    lastStatus = status
                }, {}, { ended.complete(Unit) }, {})
                try {
                    assertTrue("initial burst must support startup", audio.prepare(media, url, token, "audio/flac", 0))
                    audio.play()
                    val pausedAt = withTimeout(10_000L) { buffering.await() }
                    assertTrue("media clock must advance before starvation: $pausedAt", pausedAt > 1.0)

                    burst.countDown()
                    withTimeout(5_000L) { burstDelivered.await() }
                    delay(4_000L)
                    assertFalse("short burst restarted a starved player: ${audio.diagnostics()}", resumed.isCompleted)

                    remainder.countDown()
                    withTimeout(8_000L) {
                        resumed.await()
                        while (!audio.playing || audio.positionSeconds < pausedAt + 1.0) delay(20)
                    }
                    withTimeout(15_000L) { ended.await() }
                    assertEquals("only the forced network outage may interrupt playback", 1, stalls)
                    assertEquals("playback must recover on its original stream", 1, openCount.get())
                } finally {
                    burst.countDown()
                    remainder.countDown()
                    audio.release()
                }
            }
        } finally {
            burst.countDown()
            remainder.countDown()
            server.stop()
        }
    }

    private companion object {
        // 14 秒 96 kHz 双声道用相同的 24 位样本，32 位随机种子从 1 开始，每帧先乘 1664525 再加 1013904223，保留低 32 位
        // 高 5 位为 0 时取样本 1，否则取 0。FFmpeg 以压缩级别 1 和 4096 个样本一帧编码，关闭元数据填充
        // ffprobe 给出第 72 和 132 帧的字节偏移，帧从 0 开始编号，首段和第二段分别长 3.072 秒和 2.56 秒
        const val FIRST_BURST_END = 40_454
        const val SECOND_BURST_END = 74_108
    }
}
