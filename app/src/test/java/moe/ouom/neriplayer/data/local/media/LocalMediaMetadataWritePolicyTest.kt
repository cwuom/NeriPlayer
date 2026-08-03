package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaMetadataWritePolicyTest {

    @Test
    fun `editable text update preserves unrelated tag values`() {
        val original = hashMapOf(
            "TITLE" to arrayOf("Old title"),
            "ARTIST" to arrayOf("Old artist"),
            "ALBUM" to arrayOf("Album"),
            "LYRICS" to arrayOf("[00:00.00]lyrics")
        )

        val updated = LocalMediaSupport.applyEditableTextMetadata(
            propertyMap = original,
            title = "New title",
            artist = "New artist"
        )

        assertArrayEquals(arrayOf("New title"), updated["TITLE"])
        assertArrayEquals(arrayOf("New artist"), updated["ARTIST"])
        assertArrayEquals(arrayOf("Album"), updated["ALBUM"])
        assertArrayEquals(arrayOf("[00:00.00]lyrics"), updated["LYRICS"])
    }

    @Test
    fun `verification requires the requested title and artist`() {
        val propertyMap = hashMapOf(
            "TITLE" to arrayOf("Song"),
            "ARTIST" to arrayOf("Artist")
        )

        assertTrue(
            LocalMediaSupport.hasExpectedEditableTextMetadata(
                propertyMap = propertyMap,
                title = "Song",
                artist = "Artist"
            )
        )
        assertFalse(
            LocalMediaSupport.hasExpectedEditableTextMetadata(
                propertyMap = propertyMap,
                title = "Song",
                artist = "Other"
            )
        )
    }
}
