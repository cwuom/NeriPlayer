package moe.ouom.neriplayer.data.local.media

import com.kyant.taglib.PropertyMap
import moe.ouom.neriplayer.lyrics.embedded.NERI_ORIGINAL_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.lyrics.embedded.STANDARD_TRANSLATED_LYRICS_METADATA_KEY
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaEditableMetadataDefaultChecksTest {
    @Test
    fun `requested original lyrics are expected in the standard and neri lyric tags`() {
        val written = tags("LYRICS" to LYRIC, NERI_ORIGINAL_LYRICS_METADATA_KEY to LYRIC)
        val staleStandard = tags("LYRICS" to "[00:01]old", NERI_ORIGINAL_LYRICS_METADATA_KEY to LYRIC)

        assertTrue(check(written, lyrics = LYRIC, translated = null))
        assertFalse(check(staleStandard, lyrics = LYRIC, translated = null))
    }

    @Test
    fun `translated lyrics alone are expected as the standard lyrics`() {
        val written = tags("LYRICS" to TRANSLATION, STANDARD_TRANSLATED_LYRICS_METADATA_KEY to TRANSLATION)
        val missingStandard = tags(STANDARD_TRANSLATED_LYRICS_METADATA_KEY to TRANSLATION)

        assertTrue(check(written, lyrics = null, translated = TRANSLATION))
        assertFalse(check(missingStandard, lyrics = null, translated = TRANSLATION))
    }

    @Test
    fun `edits without lyrics only verify title and artist`() {
        val written = tags("LYRICS" to "[00:01]old")
        val renamed = tags("LYRICS" to "[00:01]old").apply { put("TITLE", arrayOf("Other")) }

        assertTrue(check(written, lyrics = null, translated = null))
        assertFalse(check(renamed, lyrics = null, translated = null))
    }

    private fun check(tags: PropertyMap, lyrics: String?, translated: String?) =
        LocalMediaSupport.hasExpectedEditableMetadataImpl(
            propertyMap = tags,
            title = "Song",
            artist = "Artist",
            lyrics = lyrics,
            translatedLyrics = translated,
            audioExtension = "flac"
        )

    private fun tags(vararg values: Pair<String, String>): PropertyMap = hashMapOf(
        "TITLE" to arrayOf("Song"),
        "ARTIST" to arrayOf("Artist")
    ).apply {
        values.forEach { (key, value) -> put(key, arrayOf(value)) }
    }

    private companion object {
        const val LYRIC = "[00:01.00]first line"
        const val TRANSLATION = "[00:01.00]第一行"
    }
}
