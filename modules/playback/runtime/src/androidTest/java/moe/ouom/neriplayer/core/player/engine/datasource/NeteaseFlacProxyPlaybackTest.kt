@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine.datasource

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.player.engine.ReactiveRenderersFactory
import moe.ouom.neriplayer.core.player.lifecycle.buildAudioLoadControl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeteaseFlacProxyPlaybackTest {
    @Test(timeout = 100_000)
    fun highBitrateFlacPlaysContinuouslyThroughADelayedProxy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        // 高码率测试曲重复 128 帧伪随机 PCM，让分块耗时稳定，也方便压缩进测试包
        // 原始字节取 Python 的 random.Random(0).randbytes(768) * 10500，得到 14 秒双声道样本
        // ffmpeg -f s24le -ar 96000 -ac 2 -i input.pcm -c:a flac -compression_level 0 -ch_mode indep
        // 接上 -frame_size 4096 -bitexact -metadata_header_padding 0 sample.flac，然后 gzip 压缩
        // 使用 .gzip 后缀，避免 Android 打包工具把 .gz 资产自动解压和改名
        val flac = context.assets.open("range-proxy-96k.flac.gzip").use { asset ->
            GZIPInputStream(asset).use { it.readBytes() }
        }
        val policy = defaultResumableChunkedHttpRangePolicy
        val baseline = play(flac, policy.copy(initialChunkSizeOverride = { 1024L * 1024 }))
        val current = play(flac, policy)
        Log.i("NERI-RangeProxyTest", "bytes=${flac.size}, legacy=$baseline, current=$current")
        assertEquals(8, baseline.requests)
        assertTrue("expected starvation in the control: $baseline", baseline.rebuffers > 0)
        assertEquals(1, current.requests)
        assertEquals(0, current.rebuffers)
    }

    private suspend fun play(flac: ByteArray, policy: ResumableChunkedHttpRangePolicy): Playback {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return DelayedRangeProxy(flac).use { proxy ->
            val http = OkHttpClient.Builder()
                .proxy(proxy.route)
                .addInterceptor { chain ->
                    // 测试包只允许回环明文流量，数据源仍使用网易云媒体 URI 选择 Range 策略
                    val localRequest = chain.request().newBuilder().url("http://127.0.0.1/sample.flac").build()
                    chain.proceed(localRequest)
                }
                .build()
            try {
                withContext(Dispatchers.Main) {
                    val upstream = OkHttpDataSource.Factory(http)
                    val sources = DataSource.Factory {
                        ResumableChunkedHttpDataSource(upstream, { it }, policy)
                    }
                    val mediaSources = DefaultMediaSourceFactory(
                        sources,
                        DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
                    )
                    val renderers = ReactiveRenderersFactory(context)
                        .setEnableAudioFloatOutput(false)
                        .setEnableDecoderFallback(true)
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                    val player = ExoPlayer.Builder(context, renderers)
                        .setMediaSourceFactory(mediaSources)
                        .setLoadControl(buildAudioLoadControl())
                        .experimentalSetDynamicSchedulingEnabled(true)
                        .build()
                    try {
                        val end = CompletableDeferred<Unit>()
                        var ready = false
                        var rebuffers = 0
                        var underruns = 0
                        player.addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                when (playbackState) {
                                    Player.STATE_READY -> ready = true
                                    Player.STATE_BUFFERING -> if (ready) rebuffers++
                                    Player.STATE_ENDED -> end.complete(Unit)
                                }
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                end.completeExceptionally(error)
                            }
                        })
                        player.addAnalyticsListener(object : AnalyticsListener {
                            override fun onAudioUnderrun(
                                eventTime: AnalyticsListener.EventTime,
                                bufferSize: Int,
                                bufferSizeMs: Long,
                                elapsedSinceLastFeedMs: Long
                            ) {
                                underruns++
                            }
                        })
                        player.volume = 0f
                        player.setMediaItem(MediaItem.fromUri("http://fixture.music.126.net/sample.flac"))
                        player.prepare()
                        player.play()
                        withTimeout(55_000) { end.await() }
                        assertTrue("track must play to the end", player.currentPosition >= 13_900)
                        Playback(proxy.requests.get(), rebuffers, underruns)
                    } finally {
                        player.release()
                    }
                }
            } finally {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
        }
    }

    private data class Playback(val requests: Int, val rebuffers: Int, val underruns: Int)

    private class DelayedRangeProxy(private val data: ByteArray) : Closeable {
        private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val route = Proxy(Proxy.Type.HTTP, listener.localSocketAddress)
        val requests = AtomicInteger()
        private val worker = Executors.newSingleThreadExecutor()
        private val running = worker.submit {
            try {
                while (!listener.isClosed) listener.accept().use(::serve)
            } catch (error: SocketException) {
                if (!listener.isClosed) throw error
            }
        }

        private fun serve(socket: Socket) {
            socket.soTimeout = 5_000
            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            check(input.readLine() == "GET http://127.0.0.1/sample.flac HTTP/1.1")
            val headers = input.lineSequence().takeWhile(String::isNotEmpty).toList()
            val range = headers.single { it.startsWith("Range: bytes=", ignoreCase = true) }
                .substringAfter('=')
            val start = range.substringBefore('-').toInt()
            val end = minOf(range.substringAfter('-').toInt(), data.lastIndex)
            val count = end - start + 1
            requests.incrementAndGet()
            // 每个响应的固定等待时间超过旧分块可以播放的约 1.82 秒
            Thread.sleep(3_200)
            val output = socket.getOutputStream()
            val response = "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Type: audio/flac\r\n" +
                "Content-Range: bytes $start-$end/${data.size}\r\n" +
                "Content-Length: $count\r\nConnection: close\r\n\r\n"
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.write(data, start, count)
        }

        override fun close() {
            listener.close()
            try {
                running.get(8, TimeUnit.SECONDS)
            } finally {
                worker.shutdownNow()
            }
        }
    }
}
