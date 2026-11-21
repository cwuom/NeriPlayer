package moe.ouom.neriplayer.lanltw.media

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LanProgressiveMediaServerTest {
    private val server = LanMediaServer()
    private val client = OkHttpClient()
    private val id = UUID.randomUUID().toString()
    private val token = server.grant("member")
    private val opened = ConcurrentLinkedQueue<Pair<Long, Long?>>()
    private val content = "0123456789".toByteArray()

    @After fun close() {
        server.stop()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test fun rangeRequestsSeekAndBoundTheSource() {
        publish(totalLength = 10)

        request(range = "bytes=3-6").use {
            assertEquals(206, it.code)
            assertEquals("bytes", it.header("Accept-Ranges"))
            assertEquals("bytes 3-6/10", it.header("Content-Range"))
            assertEquals("4", it.header("Content-Length"))
            assertEquals("3456", it.body.string())
        }
        request(range = "bytes=8-").use {
            assertEquals(206, it.code)
            assertEquals("89", it.body.string())
        }
        request(range = "bytes=-3").use {
            assertEquals(206, it.code)
            assertEquals("789", it.body.string())
        }
        request(range = "bytes=10-").use {
            assertEquals(416, it.code)
            assertEquals("bytes */10", it.header("Content-Range"))
        }
        request(method = "HEAD", range = "bytes=1-2").use {
            assertEquals(200, it.code)
            assertEquals("10", it.header("Content-Length"))
        }
        assertEquals(listOf(3L to 4L, 8L to 2L, 7L to 3L), opened.toList())
    }

    @Test fun unknownLengthCanStreamBeforeSizeIsKnown() {
        publish(totalLength = null)

        request().use {
            assertEquals(200, it.code)
            assertEquals("chunked", it.header("Transfer-Encoding"))
            assertNull(it.header("Content-Length"))
            assertEquals("0123456789", it.body.string())
        }
        request(method = "HEAD").use {
            assertEquals(200, it.code)
            assertNull(it.header("Content-Length"))
        }
        request(range = "bytes=5-").use {
            assertEquals(200, it.code)
            assertNull(it.header("Content-Range"))
            assertEquals("0123456789", it.body.string())
        }
        assertEquals(listOf(0L to null, 5L to null, 0L to null), opened.toList())
    }

    @Test fun unknownResourceSupportsRangeWhenSourceReportsRemainingLength() {
        publish(totalLength = null, reportLength = true)

        request(range = "bytes=4-8").use {
            assertEquals(206, it.code)
            assertEquals("bytes 4-8/10", it.header("Content-Range"))
            assertEquals("45678", it.body.string())
        }
        assertEquals(listOf(4L to null), opened.toList())
    }

    @Test fun firstChunkReachesMemberBeforeSourceFinishes() {
        val release = CountDownLatch(1)
        server.publishStream(id, "audio/mpeg", null) { _, _ ->
            LanMediaStreamInput(object : InputStream() {
                private var chunks = 0
                override fun read(): Int = error("expected a bulk read")

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    when (chunks++) {
                        0 -> bytes[offset] = 41
                        1 -> {
                            check(release.await(5, TimeUnit.SECONDS))
                            bytes[offset] = 42
                        }
                        else -> return -1
                    }
                    return 1
                }
            }, null)
        }
        server.start(5000, true)
        request().use {
            val body = it.body.byteStream()
            try {
                assertEquals(200, it.code)
                assertEquals(41, body.read())
            } finally {
                release.countDown()
            }
            assertEquals(42, body.read())
            assertEquals(-1, body.read())
        }
    }

    @Test fun revokingMemberDuringSourceOpenDiscardsTheOpenedSource() = runBlocking {
        val entering = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicInteger()
        server.publishStream(id, "audio/mpeg", 1) { _, _ ->
            entering.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            LanMediaStreamInput(object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() { closed.incrementAndGet() }
            }, 1)
        }
        server.start(5000, true)

        val opening = async(Dispatchers.IO) { request().use { it.code } }
        try {
            assertTrue(entering.await(5, TimeUnit.SECONDS))
            server.revoke("member")
            request().use { assertEquals(401, it.code) }
        } finally {
            release.countDown()
        }
        assertEquals(401, opening.await())
        assertEquals(1, closed.get())
    }

    @Test fun retiredStreamRejectsNewReads() {
        publish(totalLength = 10)
        request(authorization = null).use { assertEquals(401, it.code) }
        request(authorization = token).use { assertEquals(401, it.code) }
        server.retire(id)
        request().use { assertEquals(404, it.code) }
        assertTrue(opened.isEmpty())
    }

    @Test fun retiringActiveStreamsClosesTheirSourcesAndFreesTransferSlots() {
        val reading = CountDownLatch(2)
        val closed = CountDownLatch(2)
        val ended = CountDownLatch(1)
        server.publishStream(id, "audio/mpeg", 1) { _, _ ->
            LanMediaStreamInput(object : InputStream() {
                override fun read(): Int {
                    reading.countDown()
                    check(ended.await(5, TimeUnit.SECONDS))
                    return 1
                }

                override fun close() { closed.countDown() }
            }, 1)
        }
        server.start(5000, true)
        request().use { first ->
            request().use { second ->
                try {
                    assertEquals(200, first.code)
                    assertEquals(200, second.code)
                    assertTrue(reading.await(5, TimeUnit.SECONDS))
                    request().use { assertEquals(503, it.code) }
                    server.retire(id)
                    assertTrue(closed.await(5, TimeUnit.SECONDS))
                } finally {
                    ended.countDown()
                }
                assertThrows(IOException::class.java) { first.body.bytes() }
                assertThrows(IOException::class.java) { second.body.bytes() }
            }
        }
        server.publishStream(id, "audio/mpeg", 1) { _, _ ->
            LanMediaStreamInput(ByteArrayInputStream(byteArrayOf(42)), 1)
        }
        request().use {
            assertEquals(200, it.code)
            assertEquals(42, it.body.byteStream().read())
        }
    }

    private fun publish(totalLength: Long?, reportLength: Boolean = totalLength != null) {
        server.publishStream(id, "audio/mpeg", totalLength) { offset, length ->
            opened += offset to length
            val start = offset.toInt()
            val size = minOf(length ?: Long.MAX_VALUE, content.size.toLong() - offset).toInt()
            LanMediaStreamInput(ByteArrayInputStream(content, start, size), size.toLong().takeIf { reportLength })
        }
        server.start(5000, true)
    }

    private fun request(range: String? = null, method: String = "GET", authorization: String? = "Bearer $token"): Response =
        client.newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}/media/$id").method(method, null)
            .apply {
                range?.let { header("Range", it) }
                authorization?.let { header("Authorization", it) }
            }.build()).execute()
}
