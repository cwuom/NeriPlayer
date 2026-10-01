package moe.ouom.neriplayer.api.sync.webdav

import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavTransportSafetyTest {
    @Test
    fun `cancellation escapes result handling`() {
        val client = api(Interceptor { throw CancellationException("cancelled") })
        assertThrows(CancellationException::class.java) {
            client.getFileContentStrict("https://example.test/sync")
        }
    }

    @Test
    fun `unknown length response stops reading at payload limit`() {
        val body = CountingBody(12 * 1024 * 1024 + 100_000)
        val client = api(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("ok").body(body).build()
        })
        val result = client.getFileContentStrict("https://example.test/sync")
        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(body.bytesRead < body.totalBytes)
        assertEquals(true, body.closed)
    }

    private fun api(interceptor: Interceptor) = WebDavApiClient(
        username = "user", password = "pass",
        client = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        authFailureMessage = "unauthorized"
    )

    private class CountingBody(val totalBytes: Int) : ResponseBody() {
        var bytesRead = 0
        var closed = false
        private val input = object : java.io.InputStream() {
            override fun read(): Int = if (bytesRead++ < totalBytes) 0 else -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = minOf(length, totalBytes - bytesRead)
                if (count == 0) return -1
                bytes.fill(0, offset, offset + count)
                bytesRead += count
                return count
            }
            override fun close() { closed = true }
        }
        private val buffered = input.source().buffer()
        override fun contentType() = null
        override fun contentLength() = -1L
        override fun source(): BufferedSource = buffered
    }
}
