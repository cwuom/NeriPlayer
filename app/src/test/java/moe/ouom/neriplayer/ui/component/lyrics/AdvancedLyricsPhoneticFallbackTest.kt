package moe.ouom.neriplayer.ui.component.lyrics

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedLyricsPhoneticFallbackTest {

    @Test
    fun `zero length words before the last word are dropped from the karaoke line`() {
        val lyrics = buildAdvancedSyncedLyrics(
            rawLyrics = null,
            rawTranslatedLyrics = null,
            lyrics = listOf(
                LyricEntry(
                    text = "Hello world",
                    startTimeMs = 1_000L,
                    endTimeMs = 2_000L,
                    words = listOf(
                        WordTiming(startTimeMs = 1_000L, endTimeMs = 1_400L, charCount = 5),
                        WordTiming(startTimeMs = 1_400L, endTimeMs = 1_500L, charCount = 0),
                        WordTiming(startTimeMs = 1_500L, endTimeMs = 2_000L, charCount = 6)
                    )
                )
            ),
            translatedLyrics = emptyList()
        )

        val line = lyrics.lines.single() as KaraokeLine.MainKaraokeLine
        assertEquals(listOf("Hello", " world"), line.syllables.map { it.content })
        assertEquals(listOf(1_000, 1_500), line.syllables.map { it.start })
    }

    @Test
    fun `syllable transliterations stand in for a missing or blank line phonetic`() {
        val entries = buildPhoneticLyricEntries(
            rawLyrics = ttml(
                transliterations = """
                    <transliteration>
                        <text for="L1"><span>ha</span><span>ro</span></text>
                        <text for="L2"><span>bu</span><span>ranku</span></text>
                    </transliteration>
                """,
                lines = """
                    <p begin="00:01.000" end="00:02.000" itunes:key="L1">
                        <span begin="00:01.000" end="00:01.500">Ha</span>
                        <span begin="00:01.500" end="00:02.000">lo</span>
                    </p>
                    <p begin="00:03.000" end="00:04.000" itunes:key="L2">
                        <span begin="00:03.000" end="00:03.500">Bu</span>
                        <span begin="00:03.500" end="00:04.000">rank</span>
                        <span ttm:role="x-roman">   </span>
                    </p>
                """
            ),
            lyrics = emptyList()
        )

        assertEquals(listOf("ha ro", "bu ranku"), entries.map { it.text })
        assertEquals(listOf(1_000L, 3_000L), entries.map { it.startTimeMs })
    }

    @Test
    fun `lines without any phonetic produce no phonetic entry`() {
        val entries = buildPhoneticLyricEntries(
            rawLyrics = ttml(
                transliterations = "",
                lines = """
                    <p begin="00:01.000" end="00:02.000" itunes:key="L1">
                        <span begin="00:01.000" end="00:01.500">Plain</span>
                        <span begin="00:01.500" end="00:02.000">line</span>
                    </p>
                    <p begin="00:03.000" end="00:04.000" itunes:key="L2">
                        <span begin="00:03.000" end="00:04.000">Romanized</span>
                        <span ttm:role="x-roman">Romanaizudo</span>
                    </p>
                """
            ),
            lyrics = emptyList()
        )

        assertEquals(listOf("Romanaizudo"), entries.map { it.text })
    }

    private fun ttml(transliterations: String, lines: String): String = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
            <head>
                <metadata>
                    <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
                        <transliterations>$transliterations</transliterations>
                    </iTunesMetadata>
                </metadata>
            </head>
            <body>
                <div>$lines</div>
            </body>
        </tt>
    """.trimIndent()
}
