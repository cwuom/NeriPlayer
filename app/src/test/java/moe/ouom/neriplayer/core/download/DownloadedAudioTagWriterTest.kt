package moe.ouom.neriplayer.core.download

import com.kyant.taglib.Picture
import com.kyant.taglib.PropertyMap
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriter as MetadataDownloadedAudioTagWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.data.model.SongItem

class DownloadedAudioTagWriterTest {

    @Test
    fun `embedded album name strips netease source prefix`() {
        assertEquals(
            "十一月的萧邦",
            DownloadedAudioTagWriter.normalizeEmbeddedAlbumName("Netease十一月的萧邦")
        )
    }

    @Test
    fun `embedded album name removes source only markers`() {
        assertNull(DownloadedAudioTagWriter.normalizeEmbeddedAlbumName("Netease"))
        assertNull(DownloadedAudioTagWriter.normalizeEmbeddedAlbumName("Bilibili"))
        assertNull(DownloadedAudioTagWriter.normalizeEmbeddedAlbumName("Bilibili|12345"))
    }

    @Test
    fun `embedded album name keeps regular album`() {
        assertEquals(
            "The Book",
            DownloadedAudioTagWriter.normalizeEmbeddedAlbumName(" The Book ")
        )
    }

    @Test
    fun `standardized lyric embedding converts netease word lyric to lrc`() {
        val rawLyric = """
            [12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记
            [16050,1200]<16050,300,0>你<16350,300,0>好
        """.trimIndent()

        val converted = DownloadedAudioTagWriter.normalizeLyricForEmbedding(
            lyric = rawLyric,
            enabled = true
        )

        assertEquals(
            """
                [00:12.58]难以忘记
                [00:16.05]你好
            """.trimIndent(),
            converted
        )
    }

    @Test
    fun `standardized lyric embedding keeps normal lrc and metadata lines`() {
        val lrc = """
            [ar:Artist]
            [00:12.58]already synced
            plain line
        """.trimIndent()

        assertEquals(
            lrc,
            DownloadedAudioTagWriter.normalizeLyricForEmbedding(
                lyric = lrc,
                enabled = true
            )
        )
    }

    @Test
    fun `standardized lyric embedding preserves raw lyric when disabled`() {
        val wordLyric = "[12580,3470](12580,250,0)难(12830,300,0)忘"

        assertEquals(
            wordLyric,
            DownloadedAudioTagWriter.normalizeLyricForEmbedding(
                lyric = wordLyric,
                enabled = false
            )
        )
    }

    @Test
    fun `embedded translation is exposed through standard and app lyric fields`() {
        val propertyMap: PropertyMap = hashMapOf()

        MetadataDownloadedAudioTagWriter.applyEmbeddedLyricValues(
            propertyMap = propertyMap,
            audioExtension = "mp3",
            lyrics = "[00:01.00]hello",
            translatedLyrics = "[00:01.00]你好"
        )

        val externalLyrics = "[00:01.00]hello\n[00:01.00]你好"
        assertArrayEquals(arrayOf(externalLyrics), propertyMap["LYRICS"])
        assertArrayEquals(arrayOf(externalLyrics), propertyMap["UNSYNCEDLYRICS"])
        assertArrayEquals(arrayOf("[00:01.00]你好"), propertyMap["LYRICS:TRANSLATION"])
        assertArrayEquals(arrayOf("[00:01.00]hello"), propertyMap["NERI_LYRICS_ORIGINAL"])
        assertArrayEquals(arrayOf("[00:01.00]你好"), propertyMap["NERI_LYRICS_TRANSLATED"])
    }

    @Test
    fun `m4a lyric embedding mirrors bilingual content into description`() {
        val propertyMap: PropertyMap = hashMapOf()

        MetadataDownloadedAudioTagWriter.applyEmbeddedLyricValues(
            propertyMap = propertyMap,
            audioExtension = "m4a",
            lyrics = "[00:01.00]hello",
            translatedLyrics = "[00:01.00]你好"
        )

        val externalLyrics = "[00:01.00]hello\n[00:01.00]你好"
        assertArrayEquals(arrayOf(externalLyrics), propertyMap["LYRICS"])
        assertArrayEquals(arrayOf(externalLyrics), propertyMap["DESCRIPTION"])
        assertArrayEquals(arrayOf("[00:01.00]你好"), propertyMap["LYRICS:TRANSLATION"])
    }

    @Test
    fun `required embedded metadata accepts matching title and artist`() {
        val song = testSong(name = "Song", artist = "Artist")
        val propertyMap = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Artist")
        )

        assertTrue(DownloadedAudioTagWriter.hasRequiredEmbeddedMetadata(propertyMap, song))
    }

    @Test
    fun `required embedded metadata rejects missing title`() {
        val song = testSong(name = "Song", artist = "Artist")
        val propertyMap = hashMapOf(
            "ARTIST" to arrayOf("Artist")
        )

        assertFalse(DownloadedAudioTagWriter.hasRequiredEmbeddedMetadata(propertyMap, song))
    }

    @Test
    fun `required embedded metadata rejects wrong artist`() {
        val song = testSong(name = "Song", artist = "Artist")
        val propertyMap = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Other")
        )

        assertFalse(DownloadedAudioTagWriter.hasRequiredEmbeddedMetadata(propertyMap, song))
    }

    @Test
    fun `taglib backed containers support embedded tags`() {
        listOf(
            "netease - Artist - Song.mp3",
            "youtubeMusic - Artist - Song.m4a",
            "bilibili - Artist - Song.flac",
            "local - Artist - Song.ogg",
            "local - Artist - Song.WAV"
        ).forEach { fileName ->
            assertTrue(fileName, DownloadedAudioTagWriter.supportsEmbeddedTags(fileName))
        }
    }

    @Test
    fun `matroska family containers do not support embedded tags`() {
        listOf(
            "youtubeMusic - 陈芳语 - 爱你.webm",
            "youtubeMusic - Artist - Song.WEBM",
            "local - Artist - Song.mkv",
            "local - Artist - Song.mka",
            "stream - Artist - Song.ts",
            "stream - Artist - Song.m3u8"
        ).forEach { fileName ->
            assertFalse(fileName, DownloadedAudioTagWriter.supportsEmbeddedTags(fileName))
        }
    }

    @Test
    fun `extensionless file is not treated as taggable`() {
        assertFalse(DownloadedAudioTagWriter.supportsEmbeddedTags("youtubeMusic - Artist - Song"))
    }

    @Test
    fun `downloaded m4a cover replacement keeps exactly one covr picture`() {
        val replacement = Picture(
            data = byteArrayOf(9),
            description = "",
            pictureType = "Front Cover",
            mimeType = "image/jpeg"
        )

        val updated = MetadataDownloadedAudioTagWriter.replaceCoverPictures(
            existingPictures = arrayOf(
                Picture(byteArrayOf(1), "", "", "image/jpeg"),
                Picture(byteArrayOf(2), "", "", "image/png")
            ),
            replacementPicture = replacement,
            audioExtension = "m4a"
        )

        assertEquals(1, updated.size)
        assertArrayEquals(replacement.data, updated.single().data)
    }

    @Test
    fun `downloaded typed cover replacement retains back cover`() {
        val backCover = Picture(
            data = byteArrayOf(1),
            description = "back",
            pictureType = "Back Cover",
            mimeType = "image/png"
        )
        val replacement = Picture(
            data = byteArrayOf(2),
            description = "",
            pictureType = "Front Cover",
            mimeType = "image/jpeg"
        )

        val updated = MetadataDownloadedAudioTagWriter.replaceCoverPictures(
            existingPictures = arrayOf(
                backCover,
                Picture(byteArrayOf(3), "", "Front Cover", "image/jpeg")
            ),
            replacementPicture = replacement,
            audioExtension = "flac"
        )

        assertEquals(2, updated.size)
        assertArrayEquals(backCover.data, updated[0].data)
        assertArrayEquals(replacement.data, updated[1].data)
    }

    @Test
    fun `downloaded m4a recognizes roleless covr semantics`() {
        assertTrue(MetadataDownloadedAudioTagWriter.usesRolelessCoverPictures("m4a"))
        assertTrue(MetadataDownloadedAudioTagWriter.usesRolelessCoverPictures("M4B"))
        assertFalse(MetadataDownloadedAudioTagWriter.usesRolelessCoverPictures("mp3"))
    }

    @Test
    fun `m4a cover write restores properties after replacing covr`() {
        assertTrue(
            MetadataDownloadedAudioTagWriter.shouldRestorePropertyMapAfterCoverWrite(
                audioExtension = "m4a",
                writesCover = true
            )
        )
        assertFalse(
            MetadataDownloadedAudioTagWriter.shouldRestorePropertyMapAfterCoverWrite(
                audioExtension = "mp3",
                writesCover = true
            )
        )
        assertFalse(
            MetadataDownloadedAudioTagWriter.shouldRestorePropertyMapAfterCoverWrite(
                audioExtension = "m4a",
                writesCover = false
            )
        )
    }

    private fun testSong(
        name: String,
        artist: String
    ): SongItem = SongItem(
        id = 1L,
        name = name,
        artist = artist,
        album = "",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null
    )
}
