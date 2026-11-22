@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NeteasePlaybackRangeProxyTest {
    private val server = MockWebServer()
    private lateinit var client: OkHttpClient
    private lateinit var source: ResumableChunkedHttpDataSource
    private val uri = Uri.parse("http://m801.music.126.net/music/song.flac")

    @Before fun start() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        server.start(loopback, 0)
        client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(loopback, server.port)))
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        source = ResumableChunkedHttpDataSource(OkHttpDataSource.Factory(client), { it })
    }

    @After fun stop() {
        source.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        server.close()
    }

    @Test fun cappedPartialResponsesContinueUntilTheProxyConfirmsTheExactEnd() {
        server.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 4-6/*").body("efg").build())
        server.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 7-9/*").body("hij").build())
        server.enqueue(unsatisfiedRange("bytes */10"))

        assertEquals(C.LENGTH_UNSET.toLong(), source.open(httpGet(uri, position = 4)))
        assertEquals("efghij", source.readToEnd().decodeToString())
        assertEquals(listOf("4", "7", "10"), List(3) { nextRange().substringAfter('=').substringBefore('-') })
        assertEquals(3, server.requestCount)
    }

    @Test fun aBoundedReadAtTheExactEndNeverConsumesThe416ErrorBody() {
        server.enqueue(unsatisfiedRange("bytes */10"))

        source.open(httpGet(uri, position = 10, length = 5))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(5), 0, 5))
        assertEquals(1, server.requestCount)
    }

    @Test fun a416ForAnOffsetPastTheEndIsAnError() {
        repeat(2) { server.enqueue(unsatisfiedRange("bytes */10")) }
        val error = assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            source.open(httpGet(uri, position = 11, length = 5))
        }
        assertEquals(416, error.responseCode)
    }

    @Test fun aChunked200IgnoringRangesStreamsToItsNaturalEndAfterSeek() {
        repeat(2) {
            server.enqueue(MockResponse.Builder().chunkedBody("abcdefghijklmnop", 4).build())
        }
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(httpGet(uri, position = 4)))
        assertEquals("efghijklmnop", source.readToEnd().decodeToString())
        assertEquals("bytes=4-8388611", nextRange())
        assertEquals(1, server.requestCount)
    }

    @Test fun aLater200ContinuesFromTheEndOfThePrevious206() {
        server.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 4-6/*").body("efg").build())
        repeat(2) { server.enqueue(MockResponse.Builder().body("abcdefghij").build()) }

        assertEquals(C.LENGTH_UNSET.toLong(), source.open(httpGet(uri, position = 4)))
        assertEquals("efghij", source.readToEnd().decodeToString())
        assertEquals(listOf("4", "7"), List(2) { nextRange().substringAfter('=').substringBefore('-') })
        assertEquals(2, server.requestCount)
    }

    @Test fun anIgnoredRangePastTheEndReportsTheMedia3PositionError() {
        server.enqueue(MockResponse.Builder().chunkedBody("abc", 1).build())
        val error = assertThrows(IOException::class.java) { source.open(httpGet(uri, position = 4)) }
        assertTrue(DataSourceException.isCausedByPositionOutOfRange(error))
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellingWhileSkippingAnIgnoredRangeStopsTheRequest() {
        val bodyStarted = CountDownLatch(1)
        val observed = client.newBuilder().eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) { bodyStarted.countDown() }
        }).build()
        source = ResumableChunkedHttpDataSource(OkHttpDataSource.Factory(observed), { it })
        server.enqueue(MockResponse.Builder().body("x".repeat(1000)).throttleBody(1, 10, TimeUnit.MILLISECONDS).build())
        val task = FutureTask {
            runCatching { source.open(httpGet(uri, position = 900)) }.exceptionOrNull()
        }
        val loader = Thread(task)
        loader.start()
        try {
            assertTrue(bodyStarted.await(5, TimeUnit.SECONDS))
            loader.interrupt()
            val error = task.get(5, TimeUnit.SECONDS)
            assertTrue(generateSequence(error) { it.cause }.any { it is InterruptedIOException })
            assertEquals(1, server.requestCount)
        } finally {
            loader.interrupt()
            loader.join(5_000)
        }
    }

    private fun nextRange() = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)).headers.values("Range").single()

    private fun unsatisfiedRange(range: String) = MockResponse.Builder()
        .code(416).addHeader("Content-Range", range).body("Range unavailable").build()
}
