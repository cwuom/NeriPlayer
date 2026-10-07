package moe.ouom.neriplayer.common.io

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IOExtTest {

    @Test
    fun `limited reads return every byte up to the limit across buffer refills`() {
        val bytes = ByteArray(10) { it.toByte() }

        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readBytesLimited(maxBytes = 10, bufferSize = 3))
        assertArrayEquals(ByteArray(0), ByteArrayInputStream(ByteArray(0)).readBytesLimited(maxBytes = 0))
    }

    @Test
    fun `limited reads reject a stream as soon as it passes the limit`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(ByteArray(10)).readBytesLimited(maxBytes = 9, bufferSize = 4)
        }

        assertEquals("stream exceeds limit: 10 > 9", error.message)
    }

    @Test
    fun `limited reads need a non negative limit`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(ByteArray(1)).readBytesLimited(maxBytes = -1L)
        }

        assertEquals("maxBytes must be non-negative", error.message)
    }
}
