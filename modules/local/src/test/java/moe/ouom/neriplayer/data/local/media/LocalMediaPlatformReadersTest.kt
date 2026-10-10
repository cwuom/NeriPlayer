package moe.ouom.neriplayer.data.local.media

import android.database.Cursor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock

class LocalMediaPlatformReadersTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `optional cursor strings are null for missing or null columns`() {
        val cursor = mock(Cursor::class.java)
        doReturn(-1).`when`(cursor).getColumnIndex("missing")
        doReturn(1).`when`(cursor).getColumnIndex("empty")
        doReturn(true).`when`(cursor).isNull(1)
        doReturn(2).`when`(cursor).getColumnIndex("title")
        doReturn(false).`when`(cursor).isNull(2)
        doReturn("Title").`when`(cursor).getString(2)

        assertNull(cursor.getOptionalString("missing"))
        assertNull(cursor.getOptionalString("empty"))
        assertEquals("Title", cursor.getOptionalString("title"))
    }

    @Test
    fun `optional cursor longs are null for missing or null columns`() {
        val cursor = mock(Cursor::class.java)
        doReturn(-1).`when`(cursor).getColumnIndex("missing")
        doReturn(3).`when`(cursor).getColumnIndex("empty")
        doReturn(true).`when`(cursor).isNull(3)
        doReturn(4).`when`(cursor).getColumnIndex("duration")
        doReturn(false).`when`(cursor).isNull(4)
        doReturn(215_000L).`when`(cursor).getLong(4)

        assertNull(cursor.getOptionalLong("missing"))
        assertNull(cursor.getOptionalLong("empty"))
        assertEquals(215_000L, cursor.getOptionalLong("duration"))
    }

    @Test
    fun `optional media format ints ignore missing and mistyped keys`() {
        val format = mock(MediaFormat::class.java)
        doReturn(false).`when`(format).containsKey("missing")
        doReturn(true).`when`(format).containsKey(MediaFormat.KEY_SAMPLE_RATE)
        doReturn(44_100).`when`(format).getInteger(MediaFormat.KEY_SAMPLE_RATE)
        doReturn(true).`when`(format).containsKey(MediaFormat.KEY_MIME)
        doThrow(ClassCastException("not an int")).`when`(format).getInteger(MediaFormat.KEY_MIME)

        assertNull(format.getOptionalInt("missing"))
        assertEquals(44_100, format.getOptionalInt(MediaFormat.KEY_SAMPLE_RATE))
        assertNull(format.getOptionalInt(MediaFormat.KEY_MIME))
    }

    @Test
    fun `optional media format strings ignore missing and mistyped keys`() {
        val format = mock(MediaFormat::class.java)
        doReturn(false).`when`(format).containsKey("missing")
        doReturn(true).`when`(format).containsKey(MediaFormat.KEY_MIME)
        doReturn("audio/flac").`when`(format).getString(MediaFormat.KEY_MIME)
        doReturn(true).`when`(format).containsKey(MediaFormat.KEY_SAMPLE_RATE)
        doThrow(ClassCastException("not a string")).`when`(format).getString(MediaFormat.KEY_SAMPLE_RATE)

        assertNull(format.getOptionalString("missing"))
        assertEquals("audio/flac", format.getOptionalString(MediaFormat.KEY_MIME))
        assertNull(format.getOptionalString(MediaFormat.KEY_SAMPLE_RATE))
    }

    @Test
    fun `retriever metadata is trimmed and blank values are dropped`() {
        val retriever = mock(MediaMetadataRetriever::class.java)
        doReturn(" Title ").`when`(retriever).extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        doReturn("   ").`when`(retriever).extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        doReturn(null).`when`(retriever).extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)

        assertEquals("Title", retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
        assertNull(retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
        assertNull(retriever.extractNonBlankMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
    }

    @Test
    fun `neri stable keys are read from dedicated tags before comments`() {
        assertEquals(
            "key-1",
            tags("neri_stable_key" to " key-1 ", "COMMENT" to """{"stableKey":"comment-key"}""").readNeriSourceStableKey()
        )
        assertEquals("key-2", tags("NERI STABLE KEY" to "key-2").readNeriSourceStableKey())
    }

    @Test
    fun `neri stable keys fall back to json comments`() {
        assertEquals("key-3", tags("comment" to """{"stableKey":" key-3 "}""").readNeriSourceStableKey())
        assertNull(tags("COMMENT" to """{"stableKey":"   "}""").readNeriSourceStableKey())
        assertNull(tags("COMMENT" to "not json").readNeriSourceStableKey())
        assertNull(tags("TITLE" to "Song").readNeriSourceStableKey())
        assertNull((null as Map<String, Array<String>>?).readNeriSourceStableKey())
    }

    @Test
    fun `chunk bytes are bounded by the chunk and the readable file range`() {
        val file = file(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))

        RandomAccessFile(file, "r").use { raf ->
            assertArrayEquals(ByteArray(0), raf.readChunkBytes(0L, 10L))
            assertArrayEquals(byteArrayOf(0, 1, 2, 3), raf.readChunkBytes(4L, 10L))
            raf.seek(8L)
            assertArrayEquals(byteArrayOf(8, 9), raf.readChunkBytes(20L, 10L))
        }
    }

    @Test
    fun `chunk bytes are absent past the readable range`() {
        val file = file(byteArrayOf(0, 1, 2, 3))

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(4L)
            assertNull(raf.readChunkBytes(4L, 4L))
            assertNull(raf.readChunkBytes(4L, 12L))
        }
    }

    @Test
    fun `ascii ranges must lie inside the array`() {
        val bytes = "RIFF....WAVE".toByteArray(Charsets.US_ASCII)

        assertEquals("WAVE", bytes.readAscii(8, 4))
        assertNull(bytes.readAscii(-1, 4))
        assertNull(bytes.readAscii(0, 0))
        assertNull(bytes.readAscii(10, 4))
    }

    private fun tags(vararg entries: Pair<String, String>): Map<String, Array<String>> =
        entries.associate { (key, value) -> key to arrayOf(value) }

    private fun file(bytes: ByteArray): File = temporaryFolder.newFile().apply { writeBytes(bytes) }
}
