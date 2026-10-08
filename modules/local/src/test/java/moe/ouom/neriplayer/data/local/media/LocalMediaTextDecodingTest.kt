package moe.ouom.neriplayer.data.local.media

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMediaTextDecodingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `decoded text score weighs every character class`() {
        with(LocalMediaSupport) {
            assertEquals(800, scoreDecodedText(""))
            assertEquals(1006, scoreDecodedText("ab1"))
            assertEquals(1008, scoreDecodedText("歌词"))
            assertEquals(1008, scoreDecodedText("\u3400\uFAFF"))
            assertEquals(1000, scoreDecodedText("é１"))
            assertEquals(800, scoreDecodedText("\uFFFD"))
            assertEquals(760, scoreDecodedText("\u0000"))
            assertEquals(960, scoreDecodedText("\u0001\n\r\t"))
            assertEquals(1022, scoreDecodedText("[x]"))
            assertEquals(1002, scoreDecodedText("[x"))
        }
    }

    @Test
    fun `byte order marks select charset and skip their length`() {
        with(LocalMediaSupport) {
            assertEquals(
                StandardCharsets.UTF_8 to 3,
                detectBomCharset(bytes(0xEF, 0xBB, 0xBF, 0x41))
            )
            assertEquals(StandardCharsets.UTF_16LE to 2, detectBomCharset(bytes(0xFF, 0xFE)))
            assertEquals(StandardCharsets.UTF_16BE to 2, detectBomCharset(bytes(0xFE, 0xFF, 0x00)))
            assertNull(detectBomCharset(bytes(0xEF, 0xBB)))
            assertNull(detectBomCharset(bytes(0xFE)))
            assertNull(detectBomCharset(ByteArray(0)))
            assertNull(detectBomCharset("Artist".toByteArray()))
        }
    }

    @Test
    fun `container text drops padding and honours byte order marks`() {
        with(LocalMediaSupport) {
            assertNull(ByteArray(0).decodeContainerText())
            assertNull(bytes(0x00, 0x20, 0x00).decodeContainerText())
            assertEquals(
                "标题",
                (bytes(0xEF, 0xBB, 0xBF) + "标题".toByteArray() + bytes(0x00, 0x00)).decodeContainerText()
            )
            assertEquals(
                "Ab",
                (bytes(0xFE, 0xFF) + "Ab".toByteArray(StandardCharsets.UTF_16BE)).decodeContainerText()
            )
            assertNull((bytes(0xFF, 0xFE) + "\u3000".toByteArray(StandardCharsets.UTF_16LE)).decodeContainerText())
        }
    }

    @Test
    fun `container text without byte order mark picks best scoring charset`() {
        with(LocalMediaSupport) {
            assertEquals("Artist", ("Artist".toByteArray() + bytes(0x00, 0x00)).decodeContainerText())
            assertEquals("Song1", "Song1".toByteArray().decodeContainerText())
            assertEquals("é", "é".toByteArray(StandardCharsets.ISO_8859_1).decodeContainerText())
            assertEquals("歌", "歌".toByteArray().decodeContainerText())
        }
    }

    @Test
    fun `first tag value matches keys case insensitively and skips unusable values`() {
        val properties = mapOf(
            "title" to arrayOf("\uFEFF Song \u0000"),
            "ARTIST" to arrayOf(" \u0000 "),
            "Album" to emptyArray(),
            "GENRE" to arrayOf("Rock", "Pop")
        )

        assertNull((null as Map<String, Array<String>>?).readFirstValue("TITLE"))
        assertEquals("Song", properties.readFirstValue("TITLE"))
        assertEquals("Rock", properties.readFirstValue("MISSING", "ARTIST", "ALBUM", "genre"))
        assertNull(properties.readFirstValue("ARTIST", "ALBUM"))
    }

    @Test
    fun `direct file path accepts existing file uris and absolute paths only`() {
        val existing = temporaryFolder.newFile("song.flac")
        val missing = File(temporaryFolder.root, "missing.flac")

        with(LocalMediaSupport) {
            assertEquals(existing.absolutePath, directFilePath(Uri.fromFile(existing)))
            assertEquals(existing.absolutePath, directFilePath(Uri.parse("FILE://${existing.absolutePath}")))
            assertEquals(existing.absolutePath, directFilePath(Uri.parse(existing.absolutePath)))
            assertNull(directFilePath(Uri.fromFile(missing)))
            assertNull(directFilePath(Uri.parse(missing.absolutePath)))
            assertNull(directFilePath(Uri.parse("relative/song.flac")))
            assertNull(directFilePath(Uri.parse("content://media${existing.absolutePath}")))
            assertNull(directFilePath(Uri.parse("file:")))
        }
    }

    @Test
    fun `readable local title skips placeholders uris and bare file names`() {
        val source = Uri.parse("content://media/external/audio/track.mp3")

        with(LocalMediaSupport) {
            assertEquals(
                "Real Title",
                pickReadableLocalTitle(
                    source,
                    "Fallback",
                    null,
                    "   ",
                    " Unknown ",
                    "content://media/external/audio/1",
                    "FILE:///sdcard/track.mp3",
                    "track.mp3",
                    " Real Title "
                )
            )
            assertEquals("track.mp3", pickReadableLocalTitle(source, "track.mp3", "track.mp3"))
            assertNull(pickReadableLocalTitle(source, "Fallback", null, "<unknown>"))
        }
    }

    @Test
    fun `container metadata counts only non blank text or numeric values`() {
        with(LocalMediaSupport) {
            assertFalse(LocalMediaSupport.ContainerMetadata().hasAnyValue())
            assertFalse(LocalMediaSupport.ContainerMetadata(title = " ", artist = "").hasAnyValue())
            assertTrue(LocalMediaSupport.ContainerMetadata(genre = "Jazz").hasAnyValue())
            assertTrue(LocalMediaSupport.ContainerMetadata(discNumber = 2).hasAnyValue())
            assertTrue(LocalMediaSupport.ContainerMetadata(year = 1999).hasAnyValue())
        }
    }

    @Test
    fun `tag lib metadata compares cover bytes by content`() {
        val metadata = LocalMediaSupport.TagLibMetadata(
            title = "Song",
            artist = "Artist",
            year = 2020,
            durationMs = 1_000L,
            lyrics = "[00:00.00]line",
            coverBytes = byteArrayOf(1, 2, 3),
            sourceStableKey = "stable"
        )
        val sameContent = metadata.copy(coverBytes = byteArrayOf(1, 2, 3))

        assertEquals(metadata, sameContent)
        assertEquals(metadata.hashCode(), sameContent.hashCode())
        assertEquals(metadata.copy(coverBytes = null), metadata.copy(coverBytes = null))
        assertNotEquals(metadata, metadata.copy(coverBytes = byteArrayOf(1, 2)))
        assertNotEquals(metadata, metadata.copy(coverBytes = null))
        assertNotEquals(metadata.copy(coverBytes = null), metadata)
        assertNotEquals(metadata, metadata.copy(sourceStableKey = "other"))
        assertNotEquals(metadata, metadata.copy(channelCount = 2))
        assertNotEquals(metadata, metadata.copy(romanizedLyrics = "romaji"))
        assertFalse(metadata.equals("Song"))
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
