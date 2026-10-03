package moe.ouom.neriplayer.lyrics.parser

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricPhoneticParserTest {
    @Test
    fun `line romanization keeps text and original timing`() {
        val result = parseEmbeddedPhoneticLyrics(ttml(lineRomanization = "Halo Waludo"))

        assertEquals(listOf(LyricEntry("Halo Waludo", 1_000L, 2_000L)), result)
    }

    @Test
    fun `syllable romanization becomes one timed romanized line`() {
        val result = parseEmbeddedPhoneticLyrics(ttml(syllableRomanizations = "Halo" to "Waludo"))

        assertEquals(listOf(LyricEntry("Halo Waludo", 1_000L, 2_000L)), result)
    }

    @Test
    fun `existing line romanization wins over syllable romanization`() {
        val result = parseEmbeddedPhoneticLyrics(ttml(
            lineRomanization = "Existing line phonetic",
            syllableRomanizations = "Halo" to "Waludo"
        ))

        assertEquals(listOf(LyricEntry("Existing line phonetic", 1_000L, 2_000L)), result)
    }

    @Test
    fun `blank line romanization falls back to existing syllable romanization`() {
        val result = parseEmbeddedPhoneticLyrics(ttml(
            lineRomanization = "   ",
            syllableRomanizations = "Halo" to "Waludo"
        ))

        assertEquals(listOf(LyricEntry("Halo Waludo", 1_000L, 2_000L)), result)
    }

    @Test
    fun `ttml original and translation are never mistaken for romanization`() {
        assertTrue(parseEmbeddedPhoneticLyrics(ttml()).isEmpty())
    }

    @Test
    fun `blank embedded romanization creates no usable entries`() {
        assertTrue(parseEmbeddedPhoneticLyrics(ttml(lineRomanization = "   ")).isEmpty())
        assertTrue(parseEmbeddedPhoneticLyrics(ttml(syllableRomanizations = " " to " ")).isEmpty())
    }

    @Test
    fun `ordinary lyric formats without phonetic data return no romanization`() {
        for (raw in listOf("Hello World", "[00:01.00]Hello World", "[1000,1000](1000,1000,0)Hello World")) {
            assertTrue(parseEmbeddedPhoneticLyrics(raw).isEmpty())
        }
    }

    @Test
    fun `empty and malformed input safely return no romanization`() {
        for (raw in listOf("", " \n\t", "<tt><body><p begin=\"invalid\">Hello", "<broken>")) {
            assertTrue(parseEmbeddedPhoneticLyrics(raw).isEmpty())
        }
    }

    private fun ttml(
        lineRomanization: String? = null,
        syllableRomanizations: Pair<String, String>? = null
    ): String {
        val metadata = syllableRomanizations?.let { (first, second) ->
            """
                <head><metadata>
                    <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
                        <transliterations><transliteration><text for="L1">
                            <span begin="00:01.000" end="00:01.500">$first</span>
                            <span begin="00:01.500" end="00:02.000">$second</span>
                        </text></transliteration></transliterations>
                    </iTunesMetadata>
                </metadata></head>
            """.trimIndent()
        }.orEmpty()
        val linePhonetic = lineRomanization?.let { "<span ttm:role=\"x-roman\">$it</span>" }.orEmpty()
        return """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
                $metadata
                <body><div>
                    <p begin="00:01.000" end="00:02.000" itunes:key="L1">
                        <span begin="00:01.000" end="00:01.500">Hello</span>
                        <span begin="00:01.500" end="00:02.000">World</span>
                        <span ttm:role="x-translation">你好世界</span>
                        $linePhonetic
                    </p>
                </div></body>
            </tt>
        """.trimIndent()
    }
}
