package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMediaId3ExtendedHeaderTest {
    @Test
    fun `v23 extended size excludes its own four bytes`() {
        val metadata = LocalMediaSupport.parseId3Metadata(tag(3, integer(6) + ByteArray(6)))

        assertEquals("title after header", metadata?.title)
        assertEquals("artist after header", metadata?.artist)
    }

    @Test
    fun `v23 crc extended header preserves first frame boundary`() {
        val metadata = LocalMediaSupport.parseId3Metadata(tag(3, integer(10) + byteArrayOf(0x80.toByte(), 0) + ByteArray(8)))

        assertEquals("title after header", metadata?.title)
        assertEquals("artist after header", metadata?.artist)
    }

    @Test
    fun `v24 extended size already includes its four bytes`() {
        val metadata = LocalMediaSupport.parseId3Metadata(tag(4, synchsafe(6) + byteArrayOf(1, 0)))

        assertEquals("title after header", metadata?.title)
        assertEquals("artist after header", metadata?.artist)
    }

    @Test
    fun `truncated extended header cannot be parsed as frames`() {
        assertNull(LocalMediaSupport.parseId3Metadata(tag(3, integer(10_000) + ByteArray(6))))
        assertNull(LocalMediaSupport.parseId3Metadata(tag(4, synchsafe(10_000) + byteArrayOf(1, 0))))
    }

    private fun tag(version: Int, extendedHeader: ByteArray): ByteArray {
        val body = extendedHeader + frame(version, "TIT2", "title after header") + frame(version, "TPE1", "artist after header")
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), version.toByte(), 0, 0x40) + synchsafe(body.size) + body
    }

    private fun frame(version: Int, name: String, text: String): ByteArray {
        val payload = byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
        return name.toByteArray(Charsets.ISO_8859_1) +
            (if (version == 4) synchsafe(payload.size) else integer(payload.size)) + byteArrayOf(0, 0) + payload
    }

    private fun integer(value: Int) = ByteArray(4) { index -> (value ushr (8 * (3 - index))).toByte() }

    private fun synchsafe(value: Int) = ByteArray(4) { index -> ((value ushr (7 * (3 - index))) and 0x7f).toByte() }
}
