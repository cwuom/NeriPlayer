package moe.ouom.neriplayer.data.local.media

import com.kyant.taglib.Picture
import com.kyant.taglib.PropertyMap
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaEditableTagReadbackTest {
    @Test
    fun `blank expected tag values require the tag to be absent or empty`() {
        val tags = propertyMap(
            "EMPTY" to emptyArray(),
            "TITLE" to arrayOf("Title")
        ).withNullEntry("UNSET")

        assertTrue(LocalMediaSupport.hasExpectedTagValue(tags, "MISSING", " "))
        assertTrue(LocalMediaSupport.hasExpectedTagValue(tags, "EMPTY", ""))
        assertTrue(LocalMediaSupport.hasExpectedTagValue(tags, "UNSET", "\t"))
        assertFalse(LocalMediaSupport.hasExpectedTagValue(tags, "TITLE", " "))
    }

    @Test
    fun `expected tag values match any trimmed stored value`() {
        val tags = propertyMap(
            "ARTIST" to arrayOf(" Other ", " Artist "),
            "ALBUM" to arrayOf("Album"),
            "EMPTY" to emptyArray()
        )

        assertTrue(LocalMediaSupport.hasExpectedTagValue(tags, "ARTIST", "Artist "))
        assertFalse(LocalMediaSupport.hasExpectedTagValue(tags, "ALBUM", "Another album"))
        assertFalse(LocalMediaSupport.hasExpectedTagValue(tags, "EMPTY", "Album"))
        assertFalse(LocalMediaSupport.hasExpectedTagValue(tags, "MISSING", "Album"))
    }

    @Test
    fun `cleared lyrics require every standard lyric tag to be empty`() {
        assertTrue(
            LocalMediaSupport.hasExpectedStandardLyrics(
                propertyMap("TITLE" to arrayOf("Title"), "UNSYNCEDLYRICS" to emptyArray()),
                audioExtension = "mp3",
                expectedLyrics = null
            )
        )
        assertTrue(
            LocalMediaSupport.hasExpectedStandardLyrics(
                propertyMap().withNullEntry("LYRICS"),
                audioExtension = "flac",
                expectedLyrics = " "
            )
        )
        assertFalse(
            LocalMediaSupport.hasExpectedStandardLyrics(
                propertyMap("UNSYNCEDLYRICS" to arrayOf("[00:01.00]left over")),
                audioExtension = "MP3",
                expectedLyrics = ""
            )
        )
    }

    @Test
    fun `written lyrics are accepted from any standard lyric tag of the container`() {
        val lyrics = "[00:01.00]line"

        assertTrue(
            LocalMediaSupport.hasExpectedStandardLyrics(
                propertyMap("DESCRIPTION" to arrayOf(" $lyrics ")),
                audioExtension = "m4a",
                expectedLyrics = lyrics
            )
        )
        assertFalse(
            LocalMediaSupport.hasExpectedStandardLyrics(
                propertyMap("DESCRIPTION" to arrayOf(lyrics)),
                audioExtension = "flac",
                expectedLyrics = lyrics
            )
        )
    }

    @Test
    fun `property maps are equivalent only with the same keys and values`() {
        val left = propertyMap("TITLE" to arrayOf("Title"), "ARTIST" to arrayOf("A", "B"))

        assertTrue(
            LocalMediaSupport.propertyMapsEquivalent(
                left,
                propertyMap("ARTIST" to arrayOf("A", "B"), "TITLE" to arrayOf("Title"))
            )
        )
        assertFalse(LocalMediaSupport.propertyMapsEquivalent(left, propertyMap("TITLE" to arrayOf("Title"))))
        assertFalse(
            LocalMediaSupport.propertyMapsEquivalent(
                left,
                propertyMap("TITLE" to arrayOf("Title"), "ARTIST" to arrayOf("B", "A"))
            )
        )
        assertFalse(
            LocalMediaSupport.propertyMapsEquivalent(
                left,
                propertyMap("TITLE" to arrayOf("Title"), "ALBUM" to arrayOf("A", "B"))
            )
        )
    }

    @Test
    fun `cover lists in role aware containers compare every picture field`() {
        val front = picture(byteArrayOf(1), description = "", type = "Front Cover", mime = "image/jpeg")

        assertTrue(
            LocalMediaSupport.editableCoverPictureListsEquivalent(
                arrayOf(front),
                arrayOf(picture(byteArrayOf(1), description = "", type = "front cover", mime = "IMAGE/JPEG")),
                audioExtension = "flac"
            )
        )
        assertFalse(
            LocalMediaSupport.editableCoverPictureListsEquivalent(
                arrayOf(front),
                arrayOf(picture(byteArrayOf(1), description = "cover", type = "Front Cover", mime = "image/jpeg")),
                audioExtension = "flac"
            )
        )
        assertFalse(
            LocalMediaSupport.editableCoverPictureListsEquivalent(
                arrayOf(front),
                arrayOf(picture(byteArrayOf(1), description = "", type = "Back Cover", mime = "image/jpeg")),
                audioExtension = "mp3"
            )
        )
        assertFalse(
            LocalMediaSupport.editableCoverPictureListsEquivalent(
                arrayOf(front),
                arrayOf(picture(byteArrayOf(1), description = "", type = "Front Cover", mime = "image/png")),
                audioExtension = "mp3"
            )
        )
    }

    @Test
    fun `cover lists in roleless containers compare only image bytes`() {
        val stored = picture(byteArrayOf(1, 2), description = "", type = "Other", mime = "image/png")
        val written = picture(byteArrayOf(1, 2), description = "cover", type = "Front Cover", mime = "image/jpeg")

        assertTrue(LocalMediaSupport.editableCoverPictureListsEquivalent(arrayOf(stored), arrayOf(written), " M4A "))
        assertFalse(
            LocalMediaSupport.editableCoverPictureListsEquivalent(
                arrayOf(stored),
                arrayOf(picture(byteArrayOf(3), description = "", type = "Other", mime = "image/png")),
                "mp4"
            )
        )
        assertFalse(
            LocalMediaSupport.editableCoverPictureListsEquivalent(arrayOf(stored), arrayOf(stored, written), "mp4")
        )
    }

    @Test
    fun `editable metadata keeps an explicit source stable key`() {
        val song = song(sourceStableKey = "  local:abc  ")

        assertEquals("local:abc", LocalMediaSupport.editableMetadataSourceStableKey(song))
    }

    @Test
    fun `editable metadata falls back to the song identity without a source key`() {
        listOf(null, "   ").forEach { sourceKey ->
            val song = song(sourceStableKey = sourceKey)

            assertEquals(song.stableKey(), LocalMediaSupport.editableMetadataSourceStableKey(song))
        }
    }

    @Test
    fun `cover mime aliases normalize to canonical types and extensions`() {
        assertEquals("image/jpeg", LocalMediaSupport.normalizeEditableCoverMimeType("IMAGE/JPG"))
        assertEquals("image/jpeg", LocalMediaSupport.normalizeEditableCoverMimeType("image/pjpeg"))
        assertEquals("image/bmp", LocalMediaSupport.normalizeEditableCoverMimeType("image/x-ms-bmp"))
        assertEquals("image/webp", LocalMediaSupport.normalizeEditableCoverMimeType("Image/WebP"))

        assertEquals("png", LocalMediaSupport.coverExtensionForMimeType("image/PNG"))
        assertEquals("webp", LocalMediaSupport.coverExtensionForMimeType("image/webp"))
        assertEquals("gif", LocalMediaSupport.coverExtensionForMimeType("image/gif"))
        assertEquals("bmp", LocalMediaSupport.coverExtensionForMimeType("image/x-ms-bmp"))
        assertEquals("jpg", LocalMediaSupport.coverExtensionForMimeType("image/pjpeg"))
        assertEquals("jpg", LocalMediaSupport.coverExtensionForMimeType("image/heic"))
    }

    @Test
    fun `one of several tag keys may carry the expected value`() {
        val tags = propertyMap("ALBUMARTIST" to arrayOf(" Band "), "EMPTY" to emptyArray())
        val keys = listOf("ALBUM ARTIST", "ALBUMARTIST")

        assertTrue(LocalMediaSupport.hasExpectedOneOfTagValues(tags, keys, "Band "))
        assertFalse(LocalMediaSupport.hasExpectedOneOfTagValues(tags, keys, "Other"))
        assertFalse(LocalMediaSupport.hasExpectedOneOfTagValues(tags, keys, " "))
        assertTrue(LocalMediaSupport.hasExpectedOneOfTagValues(tags, listOf("EMPTY", "MISSING"), "\t"))
    }

    @Test
    fun `an unspecified tag value is only checked for absence on request`() {
        val tags = propertyMap("COMMENT" to arrayOf("kept")).withNullEntry("UNSET")

        assertTrue(LocalMediaSupport.hasExpectedOneOfTagValues(tags, listOf("COMMENT"), null))
        assertFalse(LocalMediaSupport.hasExpectedOneOfTagValues(tags, listOf("COMMENT"), null, verifyMissing = true))
        assertTrue(LocalMediaSupport.hasExpectedOneOfTagValues(tags, listOf("UNSET", "MISSING"), null, verifyMissing = true))
    }

    @Test
    fun `cover bytes are identified by their magic numbers`() {
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        fun ascii(value: String, size: Int = value.length) = value.toByteArray(Charsets.US_ASCII).copyOf(size)
        val webp = ascii("RIFF", 12).also { "WEBP".toByteArray(Charsets.US_ASCII).copyInto(it, 8) }

        assertEquals("image/jpeg", LocalMediaSupport.detectEditableCoverMimeType(bytes(0xFF, 0xD8, 0xFF)))
        assertEquals("image/png", LocalMediaSupport.detectEditableCoverMimeType(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals("image/gif", LocalMediaSupport.detectEditableCoverMimeType(ascii("GIF87a")))
        assertEquals("image/gif", LocalMediaSupport.detectEditableCoverMimeType(ascii("GIF89a")))
        assertEquals("image/bmp", LocalMediaSupport.detectEditableCoverMimeType(ascii("BM")))
        assertEquals("image/webp", LocalMediaSupport.detectEditableCoverMimeType(webp))
    }

    @Test
    fun `truncated or unknown cover bytes have no detected type`() {
        val riffWave = "RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray(Charsets.US_ASCII)

        assertNull(LocalMediaSupport.detectEditableCoverMimeType(ByteArray(0)))
        assertNull(LocalMediaSupport.detectEditableCoverMimeType(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertNull(LocalMediaSupport.detectEditableCoverMimeType(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertNull(LocalMediaSupport.detectEditableCoverMimeType("GIF88a".toByteArray(Charsets.US_ASCII)))
        assertNull(LocalMediaSupport.detectEditableCoverMimeType(riffWave))
    }

    private fun propertyMap(vararg entries: Pair<String, Array<String>>): PropertyMap = hashMapOf(*entries)

    // TagLib 通过 JNI 构造 HashMap, 键存在但值为 null 时也应视为未写入
    @Suppress("UNCHECKED_CAST")
    private fun PropertyMap.withNullEntry(key: String): PropertyMap = apply {
        (this as HashMap<String, Array<String>?>)[key] = null
    }

    private fun picture(data: ByteArray, description: String, type: String, mime: String) = Picture(
        data = data,
        description = description,
        pictureType = type,
        mimeType = mime
    )

    private fun song(sourceStableKey: String?) = SongItem(
        id = 42L,
        name = "Title",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null,
        mediaUri = "content://media/external/audio/media/42",
        sourceStableKey = sourceStableKey
    )
}
