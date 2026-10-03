package moe.ouom.neriplayer.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.component.lyrics.buildPhoneticLyricEntries
import moe.ouom.neriplayer.ui.screen.lyrics.LyricsSecondaryLineMode
import moe.ouom.neriplayer.ui.screen.lyrics.hasDisplayableLyricTranslation
import moe.ouom.neriplayer.ui.screen.lyrics.nextLyricsSecondaryLineMode
import moe.ouom.neriplayer.ui.screen.lyrics.resolveLyricsSecondaryLineMode
import moe.ouom.neriplayer.ui.screen.lyrics.resolveEffectivePhoneticLyrics
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildNowPlayingFastLyricsState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.overlayConfirmedUserLyrics

class LyricsSecondaryLineModeTest {

    @Test
    fun `confirmed phonetic clear survives full screen reparse while absent track keeps embedded fallback`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                <body><div>
                    <p begin="00:00:01.000" end="00:00:03.000">
                        <span ttm:role="x-bg" begin="00:00:01.000" end="00:00:03.000">
                            <span begin="00:00:01.000" end="00:00:03.000">background</span>
                        </span>
                        <span ttm:role="x-translation">old translation</span>
                        <span ttm:role="x-roman">old phonetic</span>
                    </p>
                    <p begin="00:00:05.000" end="00:00:07.000">original</p>
                </div></body>
            </tt>
        """.trimIndent()
        val cached = buildNowPlayingFastLyricsState(ttml, null, null)
        assertEquals(listOf("old phonetic"), cached.embeddedPhoneticLyrics.map { it.text })
        val song = SongItem(61L, "Song", "Artist", "Album", 1L, 60_000L, null)
            .copy(lyricSyncEdited = true, matchedRomanizedLyric = "")
        val cleared = overlayConfirmedUserLyrics(song, cached)
        assertEquals("original", cleared.lyrics.single().text)
        val mainPage = resolveEffectivePhoneticLyrics(cleared.rawPhoneticLyrics,
            cleared.phoneticLyrics, cleared.embeddedPhoneticLyrics)
        val reparsed = buildPhoneticLyricEntries(cleared.rawLyrics, cleared.lyrics)
        assertEquals(listOf("old phonetic"), reparsed.map { it.text })
        val fullScreen = resolveEffectivePhoneticLyrics(cleared.rawPhoneticLyrics, mainPage, reparsed)
        assertEquals(emptyList<LyricEntry>(), mainPage)
        assertEquals(emptyList<LyricEntry>(), fullScreen)
        assertEquals(LyricsSecondaryLineMode.NONE,
            resolveLyricsSecondaryLineMode(true, true, false, fullScreen.any { it.text.isNotBlank() }))

        val absent = overlayConfirmedUserLyrics(song.copy(matchedRomanizedLyric = null), cached)
        val absentMain = resolveEffectivePhoneticLyrics(absent.rawPhoneticLyrics,
            absent.phoneticLyrics, absent.embeddedPhoneticLyrics)
        val absentFull = resolveEffectivePhoneticLyrics(absent.rawPhoneticLyrics, absentMain,
            buildPhoneticLyricEntries(absent.rawLyrics, absent.lyrics))
        assertEquals(listOf("old phonetic"), absentMain.map { it.text })
        assertEquals(absentMain, absentFull)
    }

    @Test
    fun `explicit translation track cannot fall back to stale parsed or inline translations`() {
        val inline = listOf(LyricEntry("Original", 1000, 2000, translation = "old embedded"))
        val stale = listOf(LyricEntry("old parsed", 1000, 2000))
        assertEquals(false, hasDisplayableLyricTranslation("", stale, inline))
        assertEquals(false, hasDisplayableLyricTranslation("   ", stale, inline))
        assertEquals(false, hasDisplayableLyricTranslation("[ar:Artist]", emptyList(), inline))
        assertEquals(true, hasDisplayableLyricTranslation("[00:01.00]new", emptyList(), inline))
        assertEquals(true, hasDisplayableLyricTranslation(null, emptyList(), inline))
    }

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
