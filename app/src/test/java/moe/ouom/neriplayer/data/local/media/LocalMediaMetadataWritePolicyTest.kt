package moe.ouom.neriplayer.data.local.media

import com.kyant.taglib.Picture
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaMetadataWritePolicyTest {

    @Test
    fun `editable metadata update writes lyrics and translation while preserving unrelated tags`() {
        val original = hashMapOf(
            "TITLE" to arrayOf("Old title"),
            "ARTIST" to arrayOf("Old artist"),
            "ALBUM" to arrayOf("Album"),
            "LYRICS" to arrayOf("[00:00.00]lyrics")
        )

        val updated = LocalMediaSupport.applyEditableMetadata(
            propertyMap = original,
            title = "New title",
            artist = "New artist",
            lyrics = "[00:01.00]new lyrics",
            translatedLyrics = "[00:01.00]new translation",
            audioExtension = "mp3",
            writeLyrics = true
        )

        assertArrayEquals(arrayOf("New title"), updated["TITLE"])
        assertArrayEquals(arrayOf("New artist"), updated["ARTIST"])
        assertArrayEquals(arrayOf("Album"), updated["ALBUM"])
        val externalLyrics = """
            [00:01.00]new lyrics
            [00:01.00]new translation
        """.trimIndent()
        assertArrayEquals(arrayOf(externalLyrics), updated["LYRICS"])
        assertArrayEquals(arrayOf(externalLyrics), updated["UNSYNCEDLYRICS"])
        assertArrayEquals(arrayOf("[00:01.00]new lyrics"), updated["NERI_LYRICS_ORIGINAL"])
        assertArrayEquals(arrayOf("[00:01.00]new translation"), updated["LYRICS:TRANSLATION"])
        assertArrayEquals(arrayOf("[00:01.00]new translation"), updated["LYRICS_TRANSLATED"])
        assertArrayEquals(arrayOf("[00:01.00]new translation"), updated["NERI_LYRICS_TRANSLATED"])
    }

    @Test
    fun `missing lyric values preserve existing embedded lyrics`() {
        val original = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Artist"),
            "LYRICS" to arrayOf("[00:00.00]existing lyrics"),
            "NERI_LYRICS_TRANSLATED" to arrayOf("[00:00.00]existing translation")
        )

        val updated = LocalMediaSupport.applyEditableMetadata(
            propertyMap = original,
            title = "Song",
            artist = "Artist",
            lyrics = null,
            translatedLyrics = null,
            audioExtension = "flac"
        )

        assertArrayEquals(arrayOf("[00:00.00]existing lyrics"), updated["LYRICS"])
        assertArrayEquals(
            arrayOf("[00:00.00]existing translation"),
            updated["NERI_LYRICS_TRANSLATED"]
        )
    }

    @Test
    fun `metadata-only update preserves embedded lyrics despite transient matched lyrics`() {
        val original = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Artist"),
            "LYRICS" to arrayOf("[00:00.00]embedded lyrics"),
            "DESCRIPTION" to arrayOf("[00:00.00]embedded lyrics"),
            "NERI_LYRICS_ORIGINAL" to arrayOf("[00:00.00]embedded lyrics"),
            "LYRICS:TRANSLATION" to arrayOf("[00:00.00]embedded translation"),
            "NERI_LYRICS_TRANSLATED" to arrayOf("[00:00.00]embedded translation")
        )

        val updated = LocalMediaSupport.applyEditableMetadata(
            propertyMap = original,
            title = "Renamed song",
            artist = "Renamed artist",
            lyrics = "[00:01.00]transient matched lyrics",
            translatedLyrics = "[00:01.00]transient matched translation",
            audioExtension = "m4a",
            writeLyrics = false
        )

        assertArrayEquals(arrayOf("Renamed song"), updated["TITLE"])
        assertArrayEquals(arrayOf("Renamed artist"), updated["ARTIST"])
        assertArrayEquals(arrayOf("[00:00.00]embedded lyrics"), updated["LYRICS"])
        assertArrayEquals(arrayOf("[00:00.00]embedded lyrics"), updated["DESCRIPTION"])
        assertArrayEquals(
            arrayOf("[00:00.00]embedded lyrics"),
            updated["NERI_LYRICS_ORIGINAL"]
        )
        assertArrayEquals(
            arrayOf("[00:00.00]embedded translation"),
            updated["LYRICS:TRANSLATION"]
        )
        assertArrayEquals(
            arrayOf("[00:00.00]embedded translation"),
            updated["NERI_LYRICS_TRANSLATED"]
        )
    }

    @Test
    fun `verification includes requested lyrics and translation`() {
        val propertyMap = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Artist"),
            "UNSYNCEDLYRICS" to arrayOf(
                """
                    [00:01.00]lyrics
                    [00:01.00]translation
                """.trimIndent()
            ),
            "NERI_LYRICS_ORIGINAL" to arrayOf("[00:01.00]lyrics"),
            "LYRICS:TRANSLATION" to arrayOf("[00:01.00]translation"),
            "NERI_LYRICS_TRANSLATED" to arrayOf("[00:01.00]translation")
        )

        assertTrue(
            LocalMediaSupport.hasExpectedEditableMetadata(
                propertyMap = propertyMap,
                title = "Song",
                artist = "Artist",
                lyrics = "[00:01.00]lyrics",
                translatedLyrics = "[00:01.00]translation",
                audioExtension = "mp3"
            )
        )
        assertFalse(
            LocalMediaSupport.hasExpectedEditableMetadata(
                propertyMap = propertyMap,
                title = "Song",
                artist = "Artist",
                lyrics = "[00:01.00]lyrics",
                translatedLyrics = "[00:02.00]other translation",
                audioExtension = "mp3"
            )
        )
    }

    @Test
    fun `explicit lyric write clears a stale translation when restoring original lyrics`() {
        val original = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Artist"),
            "LYRICS" to arrayOf(
                "[00:01.00]new lyrics\n[00:01.00]old translation"
            ),
            "NERI_LYRICS_ORIGINAL" to arrayOf("[00:01.00]new lyrics"),
            "LYRICS:TRANSLATION" to arrayOf("[00:01.00]old translation"),
            "NERI_LYRICS_TRANSLATED" to arrayOf("[00:01.00]old translation")
        )

        val updated = LocalMediaSupport.applyEditableMetadata(
            propertyMap = original,
            title = "Song",
            artist = "Artist",
            lyrics = "[00:01.00]restored lyrics",
            translatedLyrics = null,
            audioExtension = "mp3",
            writeLyrics = true
        )

        assertArrayEquals(
            arrayOf("[00:01.00]restored lyrics"),
            updated["LYRICS"]
        )
        assertFalse(updated.containsKey("LYRICS:TRANSLATION"))
        assertFalse(updated.containsKey("LYRICS_TRANSLATED"))
        assertFalse(updated.containsKey("NERI_LYRICS_TRANSLATED"))
        assertTrue(
            LocalMediaSupport.hasExpectedEditableMetadata(
                propertyMap = updated,
                title = "Song",
                artist = "Artist",
                lyrics = "[00:01.00]restored lyrics",
                translatedLyrics = null,
                audioExtension = "mp3",
                verifyMissingLyrics = true
            )
        )
    }

    @Test
    fun `explicit lyric write removes all standard fields when both values are empty`() {
        val original = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Artist"),
            "LYRICS" to arrayOf("[00:01.00]lyrics"),
            "UNSYNCEDLYRICS" to arrayOf("[00:01.00]lyrics"),
            "NERI_LYRICS_ORIGINAL" to arrayOf("[00:01.00]lyrics"),
            "LYRICS:TRANSLATION" to arrayOf("[00:01.00]translation")
        )

        val updated = LocalMediaSupport.applyEditableMetadata(
            propertyMap = original,
            title = "Song",
            artist = "Artist",
            lyrics = null,
            translatedLyrics = null,
            audioExtension = "mp3",
            writeLyrics = true
        )

        assertFalse(updated.containsKey("LYRICS"))
        assertFalse(updated.containsKey("UNSYNCEDLYRICS"))
        assertFalse(updated.containsKey("NERI_LYRICS_ORIGINAL"))
        assertFalse(updated.containsKey("LYRICS:TRANSLATION"))
        assertTrue(
            LocalMediaSupport.hasExpectedEditableMetadata(
                propertyMap = updated,
                title = "Song",
                artist = "Artist",
                lyrics = null,
                translatedLyrics = null,
                audioExtension = "mp3",
                verifyStandardLyrics = true,
                verifyMissingLyrics = true
            )
        )
    }

    @Test
    fun `editable metadata keeps the remote source stable key`() {
        val sourceStableKey = "42|netease|"

        val updated = LocalMediaSupport.applyEditableMetadata(
            propertyMap = hashMapOf(
                "TITLE" to arrayOf("Old title"),
                "ARTIST" to arrayOf("Old artist")
            ),
            title = "Song",
            artist = "Artist",
            lyrics = null,
            translatedLyrics = null,
            audioExtension = "m4a",
            sourceStableKey = sourceStableKey
        )

        assertArrayEquals(arrayOf(sourceStableKey), updated["NERI_STABLE_KEY"])
        assertTrue(
            LocalMediaSupport.hasExpectedEditableMetadata(
                propertyMap = updated,
                title = "Song",
                artist = "Artist",
                lyrics = null,
                translatedLyrics = null,
                audioExtension = "m4a",
                sourceStableKey = sourceStableKey
            )
        )
    }

    @Test
    fun `cover verification detects the latest front cover after repeated replacements`() {
        val oldCover = Picture(
            data = byteArrayOf(1, 2, 3),
            description = "",
            pictureType = "Front Cover",
            mimeType = "image/jpeg"
        )
        val newCover = Picture(
            data = byteArrayOf(4, 5, 6),
            description = "",
            pictureType = "Front Cover",
            mimeType = "image/jpeg"
        )

        assertTrue(LocalMediaSupport.hasExpectedEditableCover(arrayOf(newCover), arrayOf(newCover)))
        assertFalse(LocalMediaSupport.hasExpectedEditableCover(arrayOf(oldCover), arrayOf(newCover)))
    }

    @Test
    fun `cover verification preserves unrelated pictures while replacing front cover`() {
        val expected = arrayOf(
            Picture(
                data = byteArrayOf(9),
                description = "back",
                pictureType = "Back Cover",
                mimeType = "image/png"
            ),
            Picture(
                data = byteArrayOf(8),
                description = "",
                pictureType = "Front Cover",
                mimeType = "image/jpeg"
            )
        )

        assertTrue(
            LocalMediaSupport.hasExpectedEditableCover(
                actualPictures = expected,
                expectedPictures = expected
            )
        )
    }

    @Test
    fun `cover verification accepts restoring a song without a front cover`() {
        val backCoverOnly = arrayOf(
            Picture(
                data = byteArrayOf(9),
                description = "back",
                pictureType = "Back Cover",
                mimeType = "image/png"
            )
        )

        assertTrue(
            LocalMediaSupport.hasExpectedEditableCover(
                actualPictures = backCoverOnly,
                expectedPictures = backCoverOnly
            )
        )
        assertFalse(
            LocalMediaSupport.hasExpectedEditableCover(
                actualPictures = backCoverOnly + Picture(
                    data = byteArrayOf(7),
                    description = "",
                    pictureType = "Front Cover",
                    mimeType = "image/jpeg"
                ),
                expectedPictures = backCoverOnly
            )
        )
    }

    @Test
    fun `m4a cover replacement treats roleless covr pictures as one cover`() {
        val oldCover = Picture(
            data = byteArrayOf(1, 2, 3),
            description = "",
            pictureType = "",
            mimeType = "image/jpeg"
        )
        val newCover = Picture(
            data = byteArrayOf(4, 5, 6),
            description = "",
            pictureType = "Front Cover",
            mimeType = "image/jpeg"
        )

        val updated = LocalMediaSupport.replaceEditableCoverPictures(
            existingPictures = arrayOf(oldCover),
            replacementPicture = newCover,
            audioExtension = "m4a"
        )

        assertEquals(1, updated.size)
        assertArrayEquals(newCover.data, updated.single().data)
        assertTrue(
            LocalMediaSupport.hasExpectedEditableCover(
                actualPictures = arrayOf(
                    newCover.copy(pictureType = "")
                ),
                expectedPictures = updated,
                audioExtension = "m4a"
            )
        )
        assertFalse(
            LocalMediaSupport.hasExpectedEditableCover(
                actualPictures = arrayOf(oldCover),
                expectedPictures = updated,
                audioExtension = "m4a"
            )
        )
    }

    @Test
    fun `m4a cover replacement removes every stale covr picture`() {
        val replacement = Picture(
            data = byteArrayOf(8),
            description = "",
            pictureType = "Front Cover",
            mimeType = "image/jpeg"
        )
        val updated = LocalMediaSupport.replaceEditableCoverPictures(
            existingPictures = arrayOf(
                Picture(byteArrayOf(1), "", "", "image/jpeg"),
                Picture(byteArrayOf(2), "", "", "image/png")
            ),
            replacementPicture = replacement,
            audioExtension = "mp4"
        )

        assertEquals(1, updated.size)
        assertArrayEquals(replacement.data, updated.single().data)
    }

    @Test
    fun `m4a cover clear removes every roleless covr picture`() {
        val cleared = LocalMediaSupport.replaceEditableCoverPictures(
            existingPictures = arrayOf(
                Picture(byteArrayOf(1), "", "", "image/jpeg"),
                Picture(byteArrayOf(2), "", "", "image/png")
            ),
            replacementPicture = null,
            audioExtension = "m4a"
        )

        assertTrue(cleared.isEmpty())
        assertTrue(
            LocalMediaSupport.hasExpectedEditableCover(
                actualPictures = cleared,
                expectedPictures = cleared,
                audioExtension = "m4a"
            )
        )
    }

    @Test
    fun `typed picture containers retain back cover while replacing front cover`() {
        val backCover = Picture(
            data = byteArrayOf(9),
            description = "back",
            pictureType = "Back Cover",
            mimeType = "image/png"
        )
        val replacement = Picture(
            data = byteArrayOf(8),
            description = "",
            pictureType = "Front Cover",
            mimeType = "image/jpeg"
        )
        val updated = LocalMediaSupport.replaceEditableCoverPictures(
            existingPictures = arrayOf(
                backCover,
                Picture(byteArrayOf(7), "", "Front Cover", "image/jpeg")
            ),
            replacementPicture = replacement,
            audioExtension = "flac"
        )

        assertEquals(2, updated.size)
        assertArrayEquals(backCover.data, updated[0].data)
        assertArrayEquals(replacement.data, updated[1].data)
    }

    @Test
    fun `m4a and mp4 use roleless cover semantics`() {
        assertTrue(LocalMediaSupport.usesRolelessEditableCoverPictures("m4a"))
        assertTrue(LocalMediaSupport.usesRolelessEditableCoverPictures("MP4"))
        assertFalse(LocalMediaSupport.usesRolelessEditableCoverPictures("flac"))
    }

    @Test
    fun `m4a cover writes restore the complete property map afterward`() {
        assertTrue(
            LocalMediaSupport.shouldRestoreEditablePropertiesAfterCoverWrite(
                audioExtension = "m4a",
                writesCover = true
            )
        )
        assertTrue(
            LocalMediaSupport.shouldRestoreEditablePropertiesAfterCoverWrite(
                audioExtension = "mp4",
                writesCover = true
            )
        )
        assertFalse(
            LocalMediaSupport.shouldRestoreEditablePropertiesAfterCoverWrite(
                audioExtension = "flac",
                writesCover = true
            )
        )
        assertFalse(
            LocalMediaSupport.shouldRestoreEditablePropertiesAfterCoverWrite(
                audioExtension = "m4a",
                writesCover = false
            )
        )
    }

    @Test
    fun `restoring a remote original cover requires a real replacement`() {
        assertEquals(
            EditableCoverMutation.REPLACE,
            LocalMediaSupport.resolveEditableCoverMutation(
                writeCover = true,
                coverReference = "https://example.com/original-cover.jpg"
            )
        )
        assertEquals(
            EditableCoverMutation.CLEAR,
            LocalMediaSupport.resolveEditableCoverMutation(
                writeCover = true,
                coverReference = null
            )
        )
    }

    @Test
    fun `cover cache invalidation targets retriever and taglib cache entries`() {
        assertArrayEquals(
            arrayOf("/music/song.mp3", "/music/song.mp3#taglib"),
            LocalMediaSupport.embeddedCoverCacheKeys(
                uri = "content://media/external/audio/media/1",
                resolvedPath = "/music/song.mp3"
            ).toTypedArray()
        )
    }

    @Test
    fun `staged content writes invalidate URI based cover cache entries when no path is resolved`() {
        assertArrayEquals(
            arrayOf(
                "content://documents/tree/music/document/music%2FSong.m4a",
                "content://documents/tree/music/document/music%2FSong.m4a#taglib"
            ),
            LocalMediaSupport.embeddedCoverCacheKeys(
                uri = "content://documents/tree/music/document/music%2FSong.m4a",
                resolvedPath = null
            ).toTypedArray()
        )
    }

    @Test
    fun `failed m4a content write retries through a staged local file`() {
        val song = editableLocalSong(
            fileName = "bilibili - Artist - Song.m4a",
            mediaUri = "content://documents/tree/music/document/music%2FSong.m4a"
        )

        assertTrue(
            LocalMediaSupport.shouldAttemptStagedContentMetadataWrite(
                sourceScheme = "content",
                sourcePathSegment = "Song.m4a",
                song = song,
                directOutcome = LocalMediaMetadataWriteOutcome.FAILED
            )
        )
        assertFalse(
            LocalMediaSupport.shouldAttemptStagedContentMetadataWrite(
                sourceScheme = "content",
                sourcePathSegment = "Song.m4a",
                song = song,
                directOutcome = LocalMediaMetadataWriteOutcome.SUCCESS
            )
        )
    }

    @Test
    fun `failed flac content write retries through a staged local file`() {
        val song = editableLocalSong(
            fileName = "Artist - Song.flac",
            mediaUri = "content://documents/tree/music/document/music%2FSong.flac"
        )

        assertTrue(
            LocalMediaSupport.shouldAttemptStagedContentMetadataWrite(
                sourceScheme = "content",
                sourcePathSegment = "Song.flac",
                song = song,
                directOutcome = LocalMediaMetadataWriteOutcome.FAILED
            )
        )
    }

    private fun editableLocalSong(fileName: String, mediaUri: String): SongItem {
        return SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = mediaUri,
            localFileName = fileName
        )
    }
}
