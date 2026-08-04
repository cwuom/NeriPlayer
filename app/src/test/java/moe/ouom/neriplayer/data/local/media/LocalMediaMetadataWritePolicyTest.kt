package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertArrayEquals
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
            audioExtension = "mp3"
        )

        assertArrayEquals(arrayOf("New title"), updated["TITLE"])
        assertArrayEquals(arrayOf("New artist"), updated["ARTIST"])
        assertArrayEquals(arrayOf("Album"), updated["ALBUM"])
        assertArrayEquals(arrayOf("[00:01.00]new lyrics"), updated["LYRICS"])
        assertArrayEquals(arrayOf("[00:01.00]new lyrics"), updated["UNSYNCEDLYRICS"])
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
    fun `verification includes requested lyrics and translation`() {
        val propertyMap = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Artist"),
            "UNSYNCEDLYRICS" to arrayOf("[00:01.00]lyrics"),
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
}
