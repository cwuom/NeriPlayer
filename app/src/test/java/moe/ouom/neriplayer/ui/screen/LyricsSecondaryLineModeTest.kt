package moe.ouom.neriplayer.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry

class LyricsSecondaryLineModeTest {

    @Test
    fun `both variants cycle through translation phonetic and off`() {
        val first = nextLyricsSecondaryLineMode(
            LyricsSecondaryLineMode.TRANSLATION,
            hasTranslation = true,
            hasPhonetic = true
        )
        val second = nextLyricsSecondaryLineMode(first, true, true)
        val third = nextLyricsSecondaryLineMode(second, true, true)

        assertEquals(LyricsSecondaryLineMode.PHONETIC, first)
        assertEquals(LyricsSecondaryLineMode.NONE, second)
        assertEquals(LyricsSecondaryLineMode.TRANSLATION, third)
    }

    @Test
    fun `single variant alternates with off and no variants stay off`() {
        assertEquals(
            LyricsSecondaryLineMode.NONE,
            nextLyricsSecondaryLineMode(LyricsSecondaryLineMode.TRANSLATION, true, false)
        )
        assertEquals(
            LyricsSecondaryLineMode.TRANSLATION,
            nextLyricsSecondaryLineMode(LyricsSecondaryLineMode.NONE, true, false)
        )
        assertEquals(
            LyricsSecondaryLineMode.NONE,
            nextLyricsSecondaryLineMode(LyricsSecondaryLineMode.PHONETIC, false, true)
        )
        assertEquals(
            LyricsSecondaryLineMode.PHONETIC,
            nextLyricsSecondaryLineMode(LyricsSecondaryLineMode.NONE, false, true)
        )
        assertEquals(
            LyricsSecondaryLineMode.NONE,
            nextLyricsSecondaryLineMode(LyricsSecondaryLineMode.NONE, false, false)
        )
    }

    @Test
    fun `available phonetics fill a missing translation without changing the off preference`() {
        assertEquals(
            LyricsSecondaryLineMode.PHONETIC,
            resolveLyricsSecondaryLineMode(true, false, false, true)
        )
        assertEquals(
            LyricsSecondaryLineMode.TRANSLATION,
            resolveLyricsSecondaryLineMode(true, true, true, false)
        )
        assertEquals(
            LyricsSecondaryLineMode.NONE,
            resolveLyricsSecondaryLineMode(false, true, true, true)
        )
    }

    @Test
    fun `translation availability ignores metadata only text and accepts parsed or inline lyrics`() {
        assertEquals(
            false,
            hasDisplayableLyricTranslation("[ar:Artist]", emptyList(), emptyList())
        )
        assertEquals(
            true,
            hasDisplayableLyricTranslation("[00:01.00]Translated", emptyList(), emptyList())
        )
        assertEquals(
            true,
            hasDisplayableLyricTranslation(
                null,
                emptyList(),
                listOf(LyricEntry("Original", 1_000L, 2_000L, translation = "Translated"))
            )
        )
    }
}
