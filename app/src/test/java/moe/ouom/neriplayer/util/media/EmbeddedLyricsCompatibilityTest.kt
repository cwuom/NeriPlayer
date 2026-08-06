package moe.ouom.neriplayer.util.media

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedLyricsCompatibilityTest {

    @Test
    fun `translation lines use their source timestamps in external LRC`() {
        val merged = mergeLyricsForExternalPlayers(
            lyrics = "[00:01.00]first\n[00:04.00]second",
            translatedLyrics = "[00:02.20]一\n[00:05.20]二"
        )

        assertEquals(
            "[00:01.00]first\n[00:01.00]一\n" +
                "[00:04.00]second\n[00:04.00]二",
            merged
        )
    }

    @Test
    fun `plain translation lines inherit nearby source timestamps`() {
        val merged = mergeLyricsForExternalPlayers(
            lyrics = "[00:01.00]first\n[00:02.00]second",
            translatedLyrics = "一\n二"
        )

        assertEquals(
            "[00:01.00]first\n[00:01.00]一\n" +
                "[00:02.00]second\n[00:02.00]二",
            merged
        )
    }

    @Test
    fun `plain lyrics are interleaved by line when neither side is timed`() {
        assertEquals(
            "first\n一\nsecond\n二",
            mergeLyricsForExternalPlayers(
                lyrics = "first\nsecond",
                translatedLyrics = "一\n二"
            )
        )
    }

    @Test
    fun `translation-only content remains available in the standard field`() {
        assertEquals(
            "[00:01.00]一",
            mergeLyricsForExternalPlayers(
                lyrics = null,
                translatedLyrics = "[00:01.00]一"
            )
        )
    }
}
