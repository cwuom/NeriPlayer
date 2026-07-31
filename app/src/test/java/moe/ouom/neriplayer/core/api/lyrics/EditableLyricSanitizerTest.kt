package moe.ouom.neriplayer.core.api.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class EditableLyricSanitizerTest {

    @Test
    fun `sanitizeMatchedEditableLyrics removes yrc title and credit prelude`() {
        val lyrics = """
            [0,170](0,170,0)Keep Your Head Up Princess - Anson Seabra
            [175,124](175,124,0)Lyrics by：Anson Long-Seabra
            [301,125](301,125,0)Composed by：Anson Long-Seabra
            [427,4385](427,500,0)When(927,400,0) she(1327,500,0) was(1827,600,0) younger
        """.trimIndent()
        val translatedLyrics = """
            [00:00.00]Keep Your Head Up Princess - Anson Seabra
            [00:00.17]Lyrics by: Anson Long-Seabra
            [00:00.30]Composed by: Anson Long-Seabra
            [00:00.42]在她还年少的时候
        """.trimIndent()

        val sanitized = sanitizeMatchedEditableLyrics(
            lyrics = lyrics,
            translatedLyrics = translatedLyrics,
            title = "Keep Your Head Up Princess",
            artist = "Anson Seabra"
        )

        assertEquals(
            "[427,4385](427,500,0)When(927,400,0) she(1327,500,0) was(1827,600,0) younger",
            sanitized.lyrics
        )
        assertEquals("[00:00.42]在她还年少的时候", sanitized.translatedLyrics)
    }

    @Test
    fun `sanitizeMatchedEditableLyrics keeps real lyric line that mentions lyrics`() {
        val lyrics = "[0,2400](0,600,0)Lyrics(600,600,0) are(1200,600,0) my(1800,600,0) shelter"

        val sanitized = sanitizeMatchedEditableLyrics(
            lyrics = lyrics,
            translatedLyrics = null,
            title = "Shelter",
            artist = "Neri"
        )

        assertEquals(lyrics, sanitized.lyrics)
    }

    @Test
    fun `sanitizeMatchedEditableLyrics keeps original when all lines look like metadata`() {
        val lyrics = "[0,170](0,170,0)Keep Your Head Up Princess - Anson Seabra"

        val sanitized = sanitizeMatchedEditableLyrics(
            lyrics = lyrics,
            translatedLyrics = null,
            title = "Keep Your Head Up Princess",
            artist = "Anson Seabra"
        )

        assertEquals(lyrics, sanitized.lyrics)
    }

    @Test
    fun `sanitizeMatchedEditableLyrics keeps ttml document unchanged`() {
        val lyrics = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt>
              <body><div><p begin="00:00.000" end="00:01.000">Song - Artist</p></div></body>
            </tt>
        """.trimIndent()

        val sanitized = sanitizeMatchedEditableLyrics(
            lyrics = lyrics,
            translatedLyrics = null,
            title = "Song",
            artist = "Artist"
        )

        assertEquals(lyrics, sanitized.lyrics)
    }
}
