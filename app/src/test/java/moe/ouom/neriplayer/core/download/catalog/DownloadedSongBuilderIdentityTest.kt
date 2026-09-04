package moe.ouom.neriplayer.core.download.catalog

import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSongBuilderIdentityTest {
    @Test
    fun `fallback downloaded song id is a stable wide digest not a 32 bit hash`() {
        val reference = "content://downloads/song-a.m4a"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(reference.toByteArray(Charsets.UTF_8))
        val expected = (ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long and Long.MAX_VALUE)
            .coerceAtLeast(1L)

        val id = fallbackDownloadedSongId(reference)

        assertEquals(expected, id)
        assertEquals(id, fallbackDownloadedSongId(reference))
        assertNotEquals(id, fallbackDownloadedSongId("content://downloads/song-b.m4a"))
        assertTrue(id > Int.MAX_VALUE.toLong())
    }
}
