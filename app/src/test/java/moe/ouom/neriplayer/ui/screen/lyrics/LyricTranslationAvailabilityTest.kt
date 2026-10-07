package moe.ouom.neriplayer.ui.screen.lyrics

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricTranslationAvailabilityTest {

    @Test
    fun `parsed translation track counts once any line has text`() {
        val translated = listOf(entry(text = "  "), entry(text = "Translated"))

        assertTrue(hasDisplayableLyricTranslation(null, translated, emptyList()))
    }

    @Test
    fun `blank parsed translations fall back to inline lyric translations`() {
        val translated = listOf(entry(text = ""), entry(text = " "))
        val lyrics = listOf(
            entry(text = "Line 1", translation = null),
            entry(text = "Line 2", translation = "   "),
            entry(text = "Line 3", translation = "Inline")
        )

        assertTrue(hasDisplayableLyricTranslation(null, translated, lyrics))
    }

    @Test
    fun `missing raw track with only blank sources has no translation`() {
        val translated = listOf(entry(text = " "))
        val lyrics = listOf(
            entry(text = "Line 1", translation = null),
            entry(text = "Line 2", translation = "")
        )

        assertFalse(hasDisplayableLyricTranslation(null, translated, lyrics))
        assertFalse(hasDisplayableLyricTranslation(null, emptyList(), emptyList()))
    }

    @Test
    fun `supplied raw track trusts already parsed lines before reparsing`() {
        val translated = listOf(entry(text = ""), entry(text = "Parsed"))

        assertTrue(hasDisplayableLyricTranslation("[ar:Artist]", translated, emptyList()))
    }

    @Test
    fun `supplied raw track with only blank timestamps is not displayable`() {
        val raw = "[00:01.00]\n[00:02.00]   "

        assertFalse(hasDisplayableLyricTranslation(raw, listOf(entry(text = " ")), emptyList()))
    }

    @Test
    fun `supplied raw track is displayable when a later timestamp has text`() {
        val raw = "[00:01.00]\n[00:03.00]Hello"

        assertTrue(hasDisplayableLyricTranslation(raw, emptyList(), emptyList()))
    }

    private fun entry(text: String, translation: String? = null) = LyricEntry(
        text = text,
        startTimeMs = 1_000L,
        endTimeMs = 2_000L,
        translation = translation
    )
}
