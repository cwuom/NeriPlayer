package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalMediaStableKeyTagTest {
    @Test
    fun `source stable keys are trimmed into the neri tag`() {
        val original = hashMapOf("ALBUM" to arrayOf("Album"))

        val updated = LocalMediaSupport.applyEditableMetadata(
            propertyMap = original,
            title = " Night Drive ",
            artist = "Artist",
            lyrics = null,
            translatedLyrics = null,
            audioExtension = "flac",
            sourceStableKey = "  123|netease|  "
        )

        assertArrayEquals(arrayOf("123|netease|"), updated["NERI_STABLE_KEY"])
        assertArrayEquals(arrayOf("Night Drive"), updated["TITLE"])
        assertArrayEquals(arrayOf("Artist"), updated["ARTIST"])
        assertArrayEquals(arrayOf("Album"), updated["ALBUM"])
        assertEquals(setOf("ALBUM"), original.keys)
    }

    @Test
    fun `blank or missing stable keys keep the stored tag`() {
        listOf(null, "", "   ").forEach { stableKey ->
            val original = hashMapOf(
                "NERI_STABLE_KEY" to arrayOf("old|key"),
                "TITLE" to arrayOf("Old"),
                "ARTIST" to arrayOf("Old artist")
            )

            val updated = LocalMediaSupport.applyEditableMetadata(
                propertyMap = original,
                title = "New",
                artist = " ",
                lyrics = null,
                translatedLyrics = null,
                audioExtension = "mp3",
                sourceStableKey = stableKey
            )

            assertArrayEquals(arrayOf("old|key"), updated["NERI_STABLE_KEY"])
            assertArrayEquals(arrayOf("New"), updated["TITLE"])
            assertFalse("ARTIST" in updated)
            assertArrayEquals(arrayOf("Old"), original["TITLE"])
            assertArrayEquals(arrayOf("Old artist"), original["ARTIST"])
        }
    }
}
