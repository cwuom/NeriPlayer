package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_16
import java.nio.charset.StandardCharsets.UTF_16BE
import java.nio.charset.StandardCharsets.UTF_8

class LocalMediaTagValueDecodingTest {
    @Test
    fun `id3 text frames decode with the charset named by their encoding byte`() {
        assertEquals("Caf\u00e9", decode(frame(0, "Caf\u00e9".toByteArray(ISO_8859_1))))
        assertEquals("歌名", decode(frame(1, "歌名".toByteArray(UTF_16))))
        assertEquals("夜曲", decode(frame(2, "夜曲".toByteArray(UTF_16BE))))
        assertEquals("\u00dcn\u00efcode", decode(frame(3, "\uFEFF\u00dcn\u00efcode\u0000".toByteArray(UTF_8))))
        assertEquals("Title", decode(frame(7, " Title\u0000".toByteArray(ISO_8859_1))))
    }

    @Test
    fun `id3 text frames without visible text decode to null`() {
        assertNull(decode(ByteArray(0)))
        assertNull(decode(frame(3, ByteArray(0))))
        assertNull(decode(frame(3, "\u0000 \u0000".toByteArray(UTF_8))))
    }

    @Test
    fun `indexed metadata keeps the number before the total`() {
        assertEquals(3, LocalMediaSupport.parseIndexedMetadata("3/12"))
        assertEquals(7, LocalMediaSupport.parseIndexedMetadata(" 7 "))
        assertNull(LocalMediaSupport.parseIndexedMetadata("track/12"))
        assertNull(LocalMediaSupport.parseIndexedMetadata(null))
    }

    private fun decode(frameData: ByteArray) = LocalMediaSupport.decodeId3TextFrame(frameData)

    private fun frame(encoding: Int, payload: ByteArray) = byteArrayOf(encoding.toByte()) + payload
}
