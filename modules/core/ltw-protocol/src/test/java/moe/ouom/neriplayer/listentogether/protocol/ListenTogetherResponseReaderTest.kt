package moe.ouom.neriplayer.listentogether.protocol

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherResponseReaderTest {
    @Test
    fun `response accepts exact byte boundary and closes input`() {
        val input = RecordingInput(ByteArray(LISTEN_TOGETHER_MAX_HTTP_RESPONSE_BYTES) { 'x'.code.toByte() })
        assertEquals(LISTEN_TOGETHER_MAX_HTTP_RESPONSE_BYTES, readListenTogetherResponse(input, input.available().toLong()).length)
        assertTrue(input.closed)
    }

    @Test
    fun `oversized declared response is rejected before reading and closes input`() {
        val input = RecordingInput(byteArrayOf(1))
        val size = LISTEN_TOGETHER_MAX_HTTP_RESPONSE_BYTES + 1L
        val error = assertThrows(IOException::class.java) { readListenTogetherResponse(input, size) }
        assertEquals("ListenTogether response too large: $size bytes", error.message)
        assertEquals(1, input.available())
        assertTrue(input.closed)
    }

    @Test
    fun `unknown length response enforces actual bytes and closes input on rejection`() {
        val input = RecordingInput(ByteArray(LISTEN_TOGETHER_MAX_HTTP_RESPONSE_BYTES + 1))
        assertThrows(IOException::class.java) { readListenTogetherResponse(input, -1L) }
        assertTrue(input.closed)
    }

    @Test
    fun `underreported length cannot bypass actual response limit`() {
        val input = RecordingInput(ByteArray(LISTEN_TOGETHER_MAX_HTTP_RESPONSE_BYTES + 1))
        assertThrows(IOException::class.java) { readListenTogetherResponse(input, 0L) }
        assertTrue(input.closed)
    }

    @Test
    fun `response respects charset and accepts empty and unknown length bodies`() {
        val text = "一起听 música"
        val charset = Charsets.UTF_16LE
        val input = RecordingInput(text.toByteArray(charset))
        assertEquals(text, readListenTogetherResponse(input, -1L, charset))
        assertTrue(input.closed)
        assertEquals("", readListenTogetherResponse(RecordingInput(byteArrayOf()), 0L))
        assertEquals(text, readListenTogetherResponse(RecordingInput(text.toByteArray()), -1L))
    }

    private class RecordingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
}
