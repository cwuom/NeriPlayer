package moe.ouom.neriplayer.core.player.resolver.netease

import android.net.Uri
import java.io.IOException
import moe.ouom.neriplayer.core.player.engine.datasource.mockHttpUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class NeteaseFlacRangeResponseTest {

    private val flacUri = mockHttpUri("https://m701.music.126.net/20260101/abc/song.flac")

    @Test
    fun `only http netease flac streams use resumable ranges`() {
        assertTrue(shouldUseNeteaseFlacResumableRange(flacUri))
        assertTrue(shouldUseNeteaseFlacResumableRange(mockHttpUri("http://music.126.net/song.FLAC")))

        assertFalse(shouldUseNeteaseFlacResumableRange(mockHttpUri("https://m701.music.126.net/song.mp3")))
        assertFalse(shouldUseNeteaseFlacResumableRange(mockHttpUri("https://music.126.net.evil.example/song.flac")))
        assertFalse(shouldUseNeteaseFlacResumableRange(mockHttpUri("ftp://m701.music.126.net/song.flac")))
        assertFalse(shouldUseNeteaseFlacResumableRange(mock(Uri::class.java)))
        assertFalse(shouldUseNeteaseFlacResumableRange(partialUri(scheme = "https", host = null)))
        assertFalse(shouldUseNeteaseFlacResumableRange(partialUri(scheme = "https", host = "music.126.net")))
    }

    @Test
    fun `missing or generic content types are rewritten to flac`() {
        val generic = listOf(
            mapOf("Content-Type" to listOf("audio/mpeg; charset=binary"), "ETag" to listOf("v1")),
            mapOf("content-type" to listOf("application/octet-stream"), "ETag" to listOf("v1")),
            mapOf("Content-Type" to emptyList(), "ETag" to listOf("v1")),
            mapOf("Content-Type" to listOf(""), "ETag" to listOf("v1")),
            mapOf("ETag" to listOf("v1"))
        )

        for (headers in generic) {
            assertEquals(
                mapOf("ETag" to listOf("v1"), "Content-Type" to listOf("audio/flac")),
                normalizeNeteaseFlacResponseContentType(flacUri, headers)
            )
        }
    }

    @Test
    fun `flac, foreign and non netease responses keep their headers`() {
        val flac = mapOf("Content-Type" to listOf("audio/flac; charset=binary"))
        val html = mapOf("Content-Type" to listOf("text/html"))
        val generic = mapOf("Content-Type" to listOf("application/octet-stream"))

        assertSame(flac, normalizeNeteaseFlacResponseContentType(flacUri, flac))
        assertSame(html, normalizeNeteaseFlacResponseContentType(flacUri, html))
        assertSame(
            generic,
            normalizeNeteaseFlacResponseContentType(mockHttpUri("https://cdn.example.com/song.flac"), generic)
        )
    }

    @Test
    fun `partial responses must start at the requested offset inside the total`() {
        validateNeteaseFlacRangeResponse(flacUri, 206, contentRange("bytes 0-99/1000"), 0L)
        validateNeteaseFlacRangeResponse(flacUri, 206, contentRange("bytes 100-199/*"), 100L)
        validateNeteaseFlacRangeResponse(flacUri, 200, emptyMap(), 0L)
        validateNeteaseFlacRangeResponse(
            mockHttpUri("https://cdn.example.com/song.flac"),
            206,
            emptyMap(),
            0L
        )

        assertRangeRejected("missing Content-Range", emptyMap(), 0L)
        assertRangeRejected("missing Content-Range", contentRange(" "), 0L)
        assertRangeRejected("malformed Content-Range", contentRange("bytes=0-99"), 0L)
        assertRangeRejected("invalid Content-Range", contentRange("bytes 100-99/1000"), 100L)
        assertRangeRejected("offset mismatch", contentRange("bytes 50-99/1000"), 0L)
        assertRangeRejected("exceeds total length", contentRange("bytes 0-1000/1000"), 0L)
    }

    private fun assertRangeRejected(
        reason: String,
        headers: Map<String, List<String>>,
        requestedStart: Long
    ) {
        val error = assertThrows(IOException::class.java) {
            validateNeteaseFlacRangeResponse(flacUri, 206, headers, requestedStart)
        }
        assertTrue(error.message, error.message.orEmpty().contains(reason))
    }

    private fun contentRange(value: String) = mapOf(
        "Content-Length" to listOf("100"),
        "content-range" to listOf(value)
    )

    private fun partialUri(scheme: String?, host: String?): Uri = mock(Uri::class.java).also {
        `when`(it.scheme).thenReturn(scheme)
        `when`(it.host).thenReturn(host)
    }
}
