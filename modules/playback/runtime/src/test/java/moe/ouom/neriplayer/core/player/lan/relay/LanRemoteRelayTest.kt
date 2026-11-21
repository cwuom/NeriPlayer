@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lan.relay

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import moe.ouom.neriplayer.core.player.engine.datasource.ResumableChunkedHttpDataSource
import moe.ouom.neriplayer.core.player.engine.datasource.ResumableChunkedHttpRangePolicy
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LanRemoteRelayTest {
    @get:Rule val temp = TemporaryFolder()
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val invalidated = AtomicInteger()
    private val bytes = ByteArray(4 * 65536) { (it % 199).toByte() }
    private val offset = 2 * 65536
    private val tag = "\"track-v1\""

    @Before fun start() {
        ShadowStatFs.registerStats(temp.root, 100_000, 90_000, 80_000)
        server.start(InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), 0)
    }
    @After fun stop() {
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun response(tag: String?, at: Int = 0, total: Int = bytes.size): MockResponse {
        val out = MockResponse.Builder().body(Buffer().write(bytes, at, bytes.size - at)).addHeader("Content-Type", "audio/mpeg")
        if (tag != null) out.addHeader("ETag", tag)
        if (at != 0) out.code(206).addHeader("Content-Range", "bytes $at-${bytes.lastIndex}/$total")
        return out.build()
    }
    private fun open(chunked: Boolean = false) = runBlocking {
        LanRelayOrigin.openRemote({ calls ->
            val upstream = OkHttpDataSource.Factory(calls)
            if (!chunked) upstream else DataSource.Factory {
                ResumableChunkedHttpDataSource(upstream, { it }, ResumableChunkedHttpRangePolicy({ true }))
            }
        }, client,
            Uri.parse(server.url("/track").toString()), temp.root) { invalidated.incrementAndGet() }
    }
    private fun read(origin: LanRelayOrigin, at: Int) =
        origin.window.open(at.toLong(), 8).stream.use { it.readBytes() }

    @Test fun verifiedRangeUsesIfMatchAndAcceptsAFullResponseFromAnOriginIgnoringRange() {
        for (reply in listOf(response(tag, offset), response(tag))) {
            server.enqueue(response(tag))
            server.enqueue(reply)
            open().use { origin ->
                assertArrayEquals(bytes.copyOfRange(offset, offset + 8), read(origin, offset))
            }
            assertNull(server.takeRequest(5, TimeUnit.SECONDS)?.headers?.get("Range"))
            val seek = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("bytes=$offset-", seek.headers["Range"])
            assertEquals(tag, seek.headers["If-Match"])
        }
        assertEquals(0, invalidated.get())
    }

    @Test fun expiredTokenChangedEtagAndIncorrectRangeNeverEnterThePublishedWindow() {
        val invalidResponses = listOf(MockResponse(code = 403), response("\"track-v2\"", offset),
            response(tag, offset, bytes.size + 1), response(tag, offset - 1), response(null, offset))
        for ((index, bad) in invalidResponses.withIndex()) {
            server.enqueue(response(tag))
            server.enqueue(bad)
            server.enqueue(bad)
            open().use { origin ->
                assertArrayEquals(bytes.copyOfRange(0, 8), read(origin, 0))
                assertThrows(IOException::class.java) { read(origin, offset) }
                assertThrows(IOException::class.java) { read(origin, 3 * 65536) }
                assertArrayEquals(bytes.copyOfRange(0, 8), read(origin, 0))
                assertEquals(index + 1, invalidated.get())
            }
        }
    }

    @Test fun weakOrMissingValidatorsServeForwardAndBackwardSeeksFromOneResponse() {
        for (weak in listOf("W/$tag", null)) {
            server.enqueue(response(weak))
            val previousRequests = server.requestCount
            open().use { origin ->
                for (at in listOf(offset, 0, 3 * 65536)) {
                    assertArrayEquals(bytes.copyOfRange(at, at + 8), read(origin, at))
                }
                assertEquals(previousRequests + 1, server.requestCount)
            }
        }
        assertEquals(0, invalidated.get())
    }

    @Test fun aNewResponseInsideAnExistingProviderStreamMustMatchBeforeAnyChangedBytesAreReturned() {
        val cut = 8192
        server.enqueue(MockResponse.Builder().code(206).addHeader("ETag", tag)
            .addHeader("Content-Range", "bytes 0-${cut - 1}/${bytes.size}").body(Buffer().write(bytes, 0, cut)).build())
        server.enqueue(response("\"changed\"", cut))
        open(chunked = true).use { origin ->
            assertThrows(IOException::class.java) { read(origin, 0) }
            assertEquals(1, invalidated.get())
        }
    }

    @Test fun anUnvalidatedPartialResponseCannotBePublishedAsACompleteSong() {
        val partial = MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 0-31/${bytes.size}")
            .body(Buffer().write(bytes, 0, 32)).build()
        server.enqueue(partial)
        server.enqueue(partial)
        assertThrows(IOException::class.java) { open() }
        server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("bytes=0-", server.takeRequest(5, TimeUnit.SECONDS)?.headers?.get("Range"))
    }

    @Test fun aRepeatedRangeAfterAShortBodyCannotSupplyBytesForTheNextOffset() {
        val cut = 32768
        val response = MockResponse.Builder().code(206).addHeader("ETag", tag)
            .addHeader("Content-Range", "bytes 0-131071/${bytes.size}").body(Buffer().write(bytes, 0, cut)).build()
        server.enqueue(response)
        server.enqueue(response)
        open(chunked = true).use { origin ->
            assertThrows(IOException::class.java) { read(origin, 0) }
            assertEquals(1, invalidated.get())
        }
        server.takeRequest(5, TimeUnit.SECONDS)
        assertTrue(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).headers["Range"].orEmpty().startsWith("bytes=$cut-"))
    }

    @Test fun aPartialResponseWithUnknownTotalCannotAdvertiseTheChunkLengthAsTheSongLength() {
        server.enqueue(MockResponse.Builder().code(206).addHeader("ETag", tag).addHeader("Content-Range", "bytes 0-31/*")
            .body(Buffer().write(bytes, 0, 32)).build())
        assertThrows(IOException::class.java) { open() }
    }
}
