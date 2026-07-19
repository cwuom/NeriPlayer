package moe.ouom.neriplayer.core.api.youtube

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubeResponseBodyReaderTest {

    @Test
    fun `readTextWithLimit accepts response at byte limit`() {
        val body = "12345678".toResponseBody("text/plain".toMediaType())

        assertEquals("12345678", body.readTextWithLimit(maxBytes = 8L))
    }

    @Test
    fun `readTextWithLimit rejects declared oversized response before reading`() {
        val body = "123456789".toResponseBody("text/plain".toMediaType())

        assertThrows(YouTubeResponseTooLargeException::class.java) {
            body.readTextWithLimit(maxBytes = 8L)
        }
    }

    @Test
    fun `readTextWithLimit stops unknown length response at limit`() {
        val body = unknownLengthBody("123456789")

        assertThrows(YouTubeResponseTooLargeException::class.java) {
            body.readTextWithLimit(maxBytes = 8L)
        }
    }

    @Test
    fun `readErrorPreviewWithLimit reports oversized body without allocating it`() {
        val body = unknownLengthBody("123456789")

        assertEquals(
            "<response body exceeds 8 bytes>",
            body.readErrorPreviewWithLimit(maxBytes = 8L)
        )
    }

    private fun unknownLengthBody(content: String): ResponseBody {
        return object : ResponseBody() {
            private val buffer = Buffer().writeUtf8(content)

            override fun contentType() = "text/plain".toMediaType()

            override fun contentLength(): Long = -1L

            override fun source(): BufferedSource = buffer
        }
    }
}
