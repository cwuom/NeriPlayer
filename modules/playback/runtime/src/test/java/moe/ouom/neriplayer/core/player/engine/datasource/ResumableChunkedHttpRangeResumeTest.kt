@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine.datasource

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import moe.ouom.neriplayer.core.player.resolver.netease.normalizeNeteaseFlacResponseContentType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumableChunkedHttpRangeResumeTest {

    private val plainPolicy = ResumableChunkedHttpRangePolicy(shouldUse = { false })
    private val chunkedPolicy = ResumableChunkedHttpRangePolicy(shouldUse = { true })
    private val payload = ByteArray(300) { (it % 251).toByte() }

    @Test
    fun `plain request opens once and exposes the upstream response`() {
        val uri = mockHttpUri("https://cdn.example.com/song.mp3")
        val server = ScriptedHttpServer {
            ScriptedHttpResponse(
                code = 200,
                headers = mapOf("Content-Type" to listOf("audio/mpeg")),
                body = payload.copyOf(5)
            )
        }
        val source = ResumableChunkedHttpDataSource(server, { it }, plainPolicy)
        source.clearRequestProperty("X-Missing")
        source.setRequestProperty("X-Trace", "abc")

        assertEquals(5L, source.open(httpGet(uri)))

        assertEquals(200, source.responseCode)
        assertEquals(listOf("audio/mpeg"), source.responseHeaders["Content-Type"])
        assertSame(uri, source.uri)
        assertEquals("abc", server.requests.single().httpRequestHeaders["X-Trace"])
        assertArrayEquals(payload.copyOf(5), source.readToEnd())

        source.clearRequestProperty("x-trace")
        assertEquals(listOf("x-trace"), server.connections.single().clearedProperties)
        source.close()
        source.open(httpGet(uri))

        assertFalse(server.requests.last().httpRequestHeaders.containsKey("X-Trace"))
        assertEquals(1, server.connections.first().closeCount)
    }

    @Test
    fun `plain open failure closes the connection and keeps the original error`() {
        val failure = IOException("connection reset")
        val server = ScriptedHttpServer { ScriptedHttpResponse(failure = failure) }
        val source = ResumableChunkedHttpDataSource(server, { it }, plainPolicy)

        val thrown = assertThrows(IOException::class.java) {
            source.open(httpGet(mockHttpUri("https://cdn.example.com/song.mp3")))
        }

        assertSame(failure, thrown)
        assertEquals(1, server.connections.single().closeCount)
    }

    @Test
    fun `short range response resumes from the exact next offset`() {
        val body = payload.copyOf(200)
        val server = ScriptedHttpServer { spec ->
            val start = spec.position.toInt()
            val end = minOf(start + 100, body.size)
            ScriptedHttpResponse(
                code = 206,
                headers = mapOf("Content-Range" to listOf("bytes $start-${end - 1}/200")),
                body = body.copyOfRange(start, end)
            )
        }
        val source = ResumableChunkedHttpDataSource(server, { it }, chunkedPolicy)

        assertEquals(200L, source.open(httpGet(mockHttpUri("https://cdn.example.com/a.flac"), length = 200)))
        assertArrayEquals(body, source.readToEnd())

        assertEquals(listOf(0L, 100L), server.requests.map { it.position })
        assertEquals(listOf(200L, 100L), server.requests.map { it.length })
    }

    @Test
    fun `unbounded chunked request adopts the advertised total length`() {
        val body = payload.copyOf(100)
        val server = ScriptedHttpServer {
            ScriptedHttpResponse(
                code = 206,
                headers = mapOf("Content-Range" to listOf("bytes 0-99/100")),
                body = body
            )
        }
        val source = ResumableChunkedHttpDataSource(server, { it }, chunkedPolicy)

        assertEquals(100L, source.open(httpGet(mockHttpUri("https://cdn.example.com/a.flac"))))
        assertArrayEquals(body, source.readToEnd())
        assertEquals(1024L * 1024L, server.requests.single().length)
    }

    @Test
    fun `netease flac seek reads nine MiB with only two range round trips`() {
        val mib = 1024 * 1024
        val start = 317
        val body = ByteArray(start + 9 * mib) { (it % 251).toByte() }
        val server = ScriptedHttpServer { request -> rangeResponse(request.httpRange(), body) }
        val source = ResumableChunkedHttpDataSource(server, { it })
        val uri = mockHttpUri("https://m801.music.126.net/music/song.flac")

        assertEquals(9L * mib, source.open(httpGet(uri, position = start.toLong())))
        assertArrayEquals(body.copyOfRange(start, body.size), source.readToEnd(64 * 1024))
        val ranges = server.requests.map { it.httpRange() }
        assertEquals(listOf(start.toLong(), start + 8L * mib), ranges.map { it.position })
        assertEquals(listOf(8L * mib, mib.toLong()), ranges.map { it.length })
        source.close()
    }

    @Test
    fun `netease flac remembers proxy range limit until the source is reopened`() {
        val mib = 1024 * 1024
        val body = ByteArray(9 * mib) { (it % 251).toByte() }
        var limit = 2L * mib
        val server = ScriptedHttpServer { request ->
            val spec = request.httpRange()
            if (spec.length > limit) ScriptedHttpResponse(failure = rangeFailure(416, spec))
            else rangeResponse(spec, body)
        }
        val source = ResumableChunkedHttpDataSource(server, { it })
        val spec = httpGet(mockHttpUri("https://m801.music.126.net/music/song.flac"))

        source.open(spec)
        assertArrayEquals(body, source.readToEnd(64 * 1024))
        assertEquals(listOf(8, 4, 2, 2, 2, 2, 1).map { it.toLong() * mib }, server.requests.map { it.httpRange().length })
        source.close()

        limit = 8L * mib
        source.open(spec)
        assertEquals(limit, server.requests.last().httpRange().length)
        source.close()
    }

    private fun DataSpec.httpRange(): DataSpec {
        val (start, end) = httpRequestHeaders.getValue("Range").removePrefix("bytes=").split('-').map(String::toLong)
        return buildUpon().setPosition(start).setLength(end - start + 1).build()
    }

    private fun rangeResponse(spec: DataSpec, body: ByteArray): ScriptedHttpResponse {
        val start = spec.position.toInt()
        val end = minOf(start + spec.length.toInt(), body.size)
        return ScriptedHttpResponse(
            code = 206,
            headers = mapOf("Content-Range" to listOf("bytes $start-${end - 1}/${body.size}")),
            body = body.copyOfRange(start, end)
        )
    }

    @Test
    fun `range not satisfiable at the advertised end completes the read`() {
        val body = payload.copyOf(200)
        val server = ScriptedHttpServer { spec ->
            if (spec.position >= 200L) {
                ScriptedHttpResponse(failure = rangeFailure(416, spec))
            } else {
                ScriptedHttpResponse(
                    code = 206,
                    headers = mapOf("Content-Range" to listOf("bytes 0-199/200")),
                    body = body
                )
            }
        }
        val source = ResumableChunkedHttpDataSource(server, { it }, chunkedPolicy)

        source.open(httpGet(mockHttpUri("https://cdn.example.com/a.flac"), length = 300))

        assertArrayEquals(body, source.readToEnd())
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
        assertTrue(server.requests.drop(1).all { it.position == 200L })
    }

    @Test
    fun `range not satisfiable before the advertised end is surfaced`() {
        val failure = resumeFailure(contentRange = "bytes 0-99/300", nextCode = 416)

        assertEquals(416, failure.responseCode)
    }

    @Test
    fun `range not satisfiable without a known total is surfaced`() {
        val failure = resumeFailure(contentRange = "bytes 0-99/*", nextCode = 416)

        assertEquals(416, failure.responseCode)
    }

    @Test
    fun `server error while resuming is surfaced without retrying smaller ranges`() {
        val failure = resumeFailure(contentRange = "bytes 0-99/300", nextCode = 503)

        assertEquals(503, failure.responseCode)
    }

    @Test
    fun `extractor sees the normalized netease flac content type`() {
        val uri = mockHttpUri("https://m701.music.126.net/20260101/abc/song.flac")
        val policy = ResumableChunkedHttpRangePolicy(
            shouldUse = { false },
            normalizeResponseHeaders = ::normalizeNeteaseFlacResponseContentType
        )
        val cases = listOf(
            mapOf("Content-Length" to listOf("10"), "Content-Type" to listOf("application/octet-stream")),
            mapOf("Content-Length" to listOf("10")),
            mapOf("Content-Length" to listOf("10"), "content-type" to listOf(" "))
        )

        for (upstreamHeaders in cases) {
            val server = ScriptedHttpServer {
                ScriptedHttpResponse(headers = upstreamHeaders, body = payload.copyOf(10))
            }
            val source = ResumableChunkedHttpDataSource(server, { it }, policy)

            source.open(httpGet(uri))

            assertEquals(
                mapOf("Content-Length" to listOf("10"), "Content-Type" to listOf("audio/flac")),
                source.responseHeaders
            )
        }
    }

    @Test
    fun `already flac content type is passed through untouched`() {
        val upstreamHeaders = mapOf("Content-Type" to listOf("audio/flac"))
        val server = ScriptedHttpServer { ScriptedHttpResponse(headers = upstreamHeaders) }
        val source = ResumableChunkedHttpDataSource(
            server,
            { it },
            ResumableChunkedHttpRangePolicy(
                shouldUse = { false },
                normalizeResponseHeaders = ::normalizeNeteaseFlacResponseContentType
            )
        )

        source.open(httpGet(mockHttpUri("https://m701.music.126.net/song.flac")))

        assertSame(upstreamHeaders, source.responseHeaders)
    }

    private fun resumeFailure(
        contentRange: String,
        nextCode: Int
    ): HttpDataSource.InvalidResponseCodeException {
        val server = ScriptedHttpServer { spec ->
            if (spec.position == 0L) {
                ScriptedHttpResponse(
                    code = 206,
                    headers = mapOf("Content-Range" to listOf(contentRange)),
                    body = payload.copyOf(100)
                )
            } else {
                ScriptedHttpResponse(failure = rangeFailure(nextCode, spec))
            }
        }
        val source = ResumableChunkedHttpDataSource(server, { it }, chunkedPolicy)
        source.open(httpGet(mockHttpUri("https://cdn.example.com/a.flac"), length = 300))
        assertEquals(100, source.read(ByteArray(100), 0, 100))

        val failure = assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            source.read(ByteArray(100), 0, 100)
        }
        if (nextCode != 416) {
            assertEquals(listOf(0L, 100L), server.requests.map { it.position })
        }
        return failure
    }
}
