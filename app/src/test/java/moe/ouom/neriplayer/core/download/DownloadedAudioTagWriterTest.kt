package moe.ouom.neriplayer.core.download

import com.kyant.taglib.Picture
import com.kyant.taglib.PropertyMap
import java.io.File
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriter as MetadataDownloadedAudioTagWriter
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.download.storage.metadata.MAX_COVER_PIXELS
import moe.ouom.neriplayer.core.download.storage.metadata.isCoverPixelBudgetWithin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.data.model.SongItem

class DownloadedAudioTagWriterTest {
    @Test
    fun `download tag write does not repeat companion sidecar persistence`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/metadata/" +
                "DownloadedAudioTagWriter.kt"
        ).readText()
        val editableWrite = source.substringAfter("LocalMediaSupport.writeEditableMetadata(")
            .substringBefore("if (outcome != LocalMediaMetadataWriteOutcome.SUCCESS)")

        assertTrue(editableWrite.contains("persistCompanionSidecars = false"))
    }

    @Test
    fun `cover pixel budget uses long multiplication and rejects overflow`() {
        assertTrue(isCoverPixelBudgetWithin(4_000, 4_000))
        assertFalse(isCoverPixelBudgetWithin(4_001, 4_000))
        assertFalse(isCoverPixelBudgetWithin(Int.MAX_VALUE, Int.MAX_VALUE))
        assertTrue(isCoverPixelBudgetWithin(1, MAX_COVER_PIXELS.toInt()))
    }


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
    fun `embedded lyric sidecar is skipped when the download already has lyric text`() {
        assertFalse(
            MetadataDownloadedAudioTagWriter.shouldReadEmbeddedLyricReference(
                reference = "content://managed/song.lrc",
                fallback = "[00:01.00]ready"
            )
        )
        assertTrue(
            MetadataDownloadedAudioTagWriter.shouldReadEmbeddedLyricReference(
                reference = "content://managed/song.lrc",
                fallback = ""
            )
        )
        assertFalse(
            MetadataDownloadedAudioTagWriter.shouldReadEmbeddedLyricReference(
                reference = null,
                fallback = null
            )
        )
    }

    @Test
    fun `fresh sidecar lyric content wins over a stale downloaded song snapshot`() {
        assertEquals(
            "[00:01.00]fresh lyric",
            MetadataDownloadedAudioTagWriter.selectEmbeddedLyricContent(
                cachedContent = "[00:01.00]fresh lyric",
                resolvedContent = null,
                fallback = "[00:01.00]stale lyric"
            )
        )
    }

    @Test
    fun `resolved sidecar lyric is used when no fresh content was retained`() {
        assertEquals(
            "[00:01.00]restored lyric",
            MetadataDownloadedAudioTagWriter.selectEmbeddedLyricContent(
                cachedContent = "  ",
                resolvedContent = "[00:01.00]restored lyric",
                fallback = "[00:01.00]stale lyric"
            )
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
            translatedLyrics = "[00:01.00]你好",
            romanizedLyrics = "[00:01.00]ni hao"
        )

        val externalLyrics = "[00:01.00]hello\n[00:01.00]你好"
        assertArrayEquals(arrayOf(externalLyrics), propertyMap["LYRICS"])
        assertArrayEquals(arrayOf(externalLyrics), propertyMap["UNSYNCEDLYRICS"])
        assertArrayEquals(arrayOf("[00:01.00]你好"), propertyMap["LYRICS:TRANSLATION"])
        assertArrayEquals(arrayOf("[00:01.00]hello"), propertyMap["NERI_LYRICS_ORIGINAL"])
        assertArrayEquals(arrayOf("[00:01.00]你好"), propertyMap["NERI_LYRICS_TRANSLATED"])
        assertArrayEquals(arrayOf("[00:01.00]ni hao"), propertyMap["NERI_LYRICS_ROMANIZED"])
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
    fun `required embedded metadata accepts every requested lyric variant`() {
        val song = testSong(name = "Song", artist = "Artist").copy(
            matchedLyric = "[00:01.00]hello",
            matchedTranslatedLyric = "[00:01.00]你好",
            matchedRomanizedLyric = "[00:01.00]ni hao"
        )
        val propertyMap: PropertyMap = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Artist"),
            "LYRICS" to arrayOf("[00:01.00]hello"),
            "LYRICS:TRANSLATION" to arrayOf("[00:01.00]你好"),
            "NERI_LYRICS_ROMANIZED" to arrayOf("[00:01.00]ni hao")
        )
        val sidecars = AudioDownloadManager.DownloadedSidecarReferences(
            expectedLyric = true,
            expectedTranslatedLyric = true,
            expectedRomanizedLyric = true
        )

        assertTrue(
            MetadataDownloadedAudioTagWriter.hasRequiredEmbeddedMetadata(
                propertyMap = propertyMap,
                song = song,
                sidecarReferences = sidecars,
                audioExtension = "flac"
            )
        )
    }

    @Test
    fun `required embedded metadata rejects a missing requested lyric variant`() {
        val song = testSong(name = "Song", artist = "Artist").copy(
            matchedLyric = "[00:01.00]hello",
            matchedTranslatedLyric = "[00:01.00]你好"
        )
        val propertyMap: PropertyMap = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Artist"),
            "LYRICS" to arrayOf("[00:01.00]hello"),
            "LYRICS:TRANSLATION" to arrayOf("[00:01.00]你好")
        )
        val sidecars = AudioDownloadManager.DownloadedSidecarReferences(
            expectedLyric = true,
            expectedTranslatedLyric = true,
            expectedRomanizedLyric = true
        )

        assertFalse(
            MetadataDownloadedAudioTagWriter.hasRequiredEmbeddedMetadata(
                propertyMap = propertyMap,
                song = song,
                sidecarReferences = sidecars,
                audioExtension = "flac"
            )
        )
    }

    @Test
    fun `completed embedded metadata requires exact managed values`() {
        val expected: PropertyMap = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Artist"),
            "ALBUM" to arrayOf("Album"),
            "ALBUMARTIST" to arrayOf("Artist"),
            "TRACKNUMBER" to arrayOf("7"),
            "LYRICS" to arrayOf("[00:01.00]new lyric"),
            "LYRICS:TRANSLATION" to arrayOf("[00:01.00]新歌词"),
            "NERI_LYRICS_ORIGINAL" to arrayOf("[00:01.00]new lyric"),
            "NERI_LYRICS_TRANSLATED" to arrayOf("[00:01.00]新歌词"),
            "NERI_LYRICS_ROMANIZED" to arrayOf("[00:01.00]xin ge ci"),
            "NERI_STABLE_KEY" to arrayOf("stable"),
            "NERI_MEDIA_URI" to arrayOf("https://example.com/song"),
            "NERI_SOURCE" to arrayOf("NETEASE"),
            "COMMENT" to arrayOf("metadata")
        )
        val actual: PropertyMap = hashMapOf<String, Array<String>>().apply {
            expected.forEach { (key, values) -> put(key, values.copyOf()) }
        }

        assertTrue(
            MetadataDownloadedAudioTagWriter.hasExpectedEmbeddedPropertyValues(
                actual = actual,
                expected = expected,
                audioExtension = "flac"
            )
        )
        actual["LYRICS"] = arrayOf("[00:01.00]stale but non-empty lyric")
        assertFalse(
            MetadataDownloadedAudioTagWriter.hasExpectedEmbeddedPropertyValues(
                actual = actual,
                expected = expected,
                audioExtension = "flac"
            )
        )
    }

    @Test
    fun `completed embedded metadata verifies cleared managed values`() {
        val expected: PropertyMap = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Artist")
        )
        val actual: PropertyMap = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Artist"),
            "ALBUM" to arrayOf("stale album")
        )

        assertFalse(
            MetadataDownloadedAudioTagWriter.hasExpectedEmbeddedPropertyValues(
                actual = actual,
                expected = expected,
                audioExtension = "flac"
            )
        )
    }

    @Test
    fun `embedded metadata uses the custom display artist`() {
        val song = testSong(name = "Song", artist = "Source artist").copy(
            customArtist = "Edited artist"
        )
        val propertyMap: PropertyMap = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Edited artist")
        )

        assertTrue(DownloadedAudioTagWriter.hasRequiredEmbeddedMetadata(propertyMap, song))
    }

    @Test
    fun `embedded picture parsing is only requested when a cover reference exists`() {
        assertFalse(
            MetadataDownloadedAudioTagWriter.shouldLoadEmbeddedPictures(
                sidecarReferences = null
            )
        )
        assertFalse(
            MetadataDownloadedAudioTagWriter.shouldLoadEmbeddedPictures(
                sidecarReferences = AudioDownloadManager.DownloadedSidecarReferences(
                    coverReference = "   "
                )
            )
        )
        assertTrue(
            MetadataDownloadedAudioTagWriter.shouldLoadEmbeddedPictures(
                sidecarReferences = AudioDownloadManager.DownloadedSidecarReferences(
                    coverReference = "content://covers/song.jpg"
                )
            )
        )
    }

    @Test
    fun `roleless covr containers skip loading existing pictures`() {
        val sidecars = AudioDownloadManager.DownloadedSidecarReferences(
            coverReference = "content://covers/song.jpg"
        )

        assertFalse(
            MetadataDownloadedAudioTagWriter.shouldLoadEmbeddedPictures(
                sidecarReferences = sidecars,
                audioExtension = "m4a"
            )
        )
        assertFalse(
            MetadataDownloadedAudioTagWriter.shouldLoadEmbeddedPictures(
                sidecarReferences = sidecars,
                audioExtension = "M4B"
            )
        )
        assertTrue(
            MetadataDownloadedAudioTagWriter.shouldLoadEmbeddedPictures(
                sidecarReferences = sidecars,
                audioExtension = "flac"
            )
        )
        // 未知扩展名继续采用历史上的保守处理
        assertTrue(
            MetadataDownloadedAudioTagWriter.shouldLoadEmbeddedPictures(
                sidecarReferences = sidecars
            )
        )
    }

    @Test
    fun `unchanged verified tags do not require a second TagLib parse`() {
        val song = testSong(name = "Song", artist = "Artist")
        val propertyMap: PropertyMap = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Artist")
        )

        assertTrue(
            MetadataDownloadedAudioTagWriter.canSkipEmbeddedMetadataVerification(
                existingPropertyMap = propertyMap,
                propertyChanged = false,
                coverChanged = false,
                song = song
            )
        )
        assertFalse(
            MetadataDownloadedAudioTagWriter.canSkipEmbeddedMetadataVerification(
                existingPropertyMap = propertyMap,
                propertyChanged = true,
                coverChanged = false,
                song = song
            )
        )
        assertFalse(
            MetadataDownloadedAudioTagWriter.canSkipEmbeddedMetadataVerification(
                existingPropertyMap = null,
                propertyChanged = false,
                coverChanged = false,
                song = song
            )
        )
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
    fun `pending SAF audio keeps an internal writable reference`() {
        val entry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.mp3.npdl_pending.test.pending",
            reference = "content://provider/tree/audio/pending",
            mediaUri = "content://provider/tree/audio/pending",
            localFilePath = null,
            sizeBytes = 42L,
            lastModifiedMs = 1L
        )

        assertEquals("", entry.playbackUri)
        assertEquals(
            "content://provider/tree/audio/pending",
            MetadataDownloadedAudioTagWriter.writableDescriptorReference(entry)
        )
    }

    @Test
    fun `SAF writable reference falls back to reference when media uri is absent`() {
        val entry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.flac",
            reference = "content://provider/tree/audio/song",
            mediaUri = "",
            localFilePath = null,
            sizeBytes = 42L,
            lastModifiedMs = 1L
        )

        assertEquals(
            "content://provider/tree/audio/song",
            MetadataDownloadedAudioTagWriter.writableDescriptorReference(entry)
        )
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

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
