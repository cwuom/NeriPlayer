package moe.ouom.neriplayer.ui.screen

import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.WordTiming
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingWideLyricsContent
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingWideLyricsMode
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingWideLyricsPreferences
import moe.ouom.neriplayer.ui.screen.nowplaying.resolveNowPlayingWideLyricsPresentation
import moe.ouom.neriplayer.ui.screen.nowplaying.resolveNowPlayingWideLyricsSecondaryContent
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingSyncedLyricContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingWideLyricsPresentationTest {
    @Test
    fun `cover modes preserve advanced preference and never render missing lines`() {
        assertEquals(NowPlayingWideLyricsMode.ADVANCED, presentation(advanced = true).coverMode)
        assertEquals(NowPlayingWideLyricsMode.SYNCED, presentation(advanced = false).coverMode)
        listOf(false, true).forEach { advanced ->
            val empty = presentation(advanced = advanced, hasLyrics = false)
            assertEquals(NowPlayingWideLyricsMode.NO_LYRICS, empty.coverMode)
            assertFalse(empty.fullPage)
        }
    }

    @Test
    fun `full lyrics remains a full page even when advanced is disabled or lyrics are empty`() {
        listOf(false, true).forEach { advanced ->
            listOf(false, true).forEach { hasLyrics ->
                assertTrue(presentation(fullPage = true, advanced = advanced, hasLyrics = hasLyrics).fullPage)
            }
        }
    }

    @Test
    fun `cover and full lyrics use their own saved scales in both height classes`() {
        val cover = presentation()
        val full = presentation(fullPage = true)
        assertEquals(0.9f, cover.typography.lyricScale, 0f)
        assertEquals(1.1f, cover.typography.translationScale, 0f)
        assertEquals(1.3f, full.typography.lyricScale, 0f)
        assertEquals(1.5f, full.typography.translationScale, 0f)
        assertEquals(24f, cover.typography.baseFontSizeSp, 0f)
        assertEquals(22f * 0.9f, cover.typography.syncedFontSizeSp, 0.001f)
        assertEquals(14f * 1.1f, cover.typography.syncedTranslationFontSizeSp, 0.001f)
        assertEquals(24.dp, full.bottomContentInset)
        val compactCover = presentation(compact = true)
        val compactFull = presentation(fullPage = true, compact = true)
        assertEquals(18f, compactCover.typography.baseFontSizeSp, 0f)
        assertEquals(18f * 0.9f, compactCover.typography.syncedFontSizeSp, 0.001f)
        assertEquals(1.3f, compactFull.typography.lyricScale, 0f)
        assertEquals(1.5f, compactFull.typography.translationScale, 0f)
        assertEquals(8.dp, compactFull.bottomContentInset)
    }

    @Test
    fun `tablet renderer boundary uses device identity and the actual reading width`() {
        assertFalse(presentation(width = 719.dp).useTabletLayout)
        assertTrue(presentation(width = 720.dp).useTabletLayout)
        assertTrue(presentation(width = 1280.dp, compact = true).useTabletLayout)
        assertFalse(presentation(width = 1280.dp, phone = true).useTabletLayout)
    }

    @Test
    fun `translation keeps original rich lines and raw word timed source`() {
        val content = lyricContent()
        val selected = resolveNowPlayingWideLyricsSecondaryContent(content, true, false)
        assertSame(content.translatedLyrics, selected.lines)
        assertEquals(content.rawTranslatedLyrics, selected.rawTranslation)
        assertEquals(listOf(WordTiming(1_000L, 1_500L, 3)), selected.lines?.single()?.words)
        assertEquals(350L, content.synced.offsetMs)
        assertEquals("fixture-track", content.synced.playbackSessionKey)
    }

    @Test
    fun `phonetic display replaces translation lines and excludes the raw translation source`() {
        val content = lyricContent()
        val selected = resolveNowPlayingWideLyricsSecondaryContent(content, true, true)
        assertSame(content.phoneticLyrics, selected.lines)
        assertNull(selected.rawTranslation)
        assertEquals("romaji", selected.lines?.single()?.text)
        assertEquals("translated", content.translatedLyrics.single().text)
    }

    @Test
    fun `hidden secondary lyrics cannot leak either translation or phonetic lines`() {
        val content = lyricContent()
        val translationHidden = resolveNowPlayingWideLyricsSecondaryContent(content, false, false)
        val phoneticHidden = resolveNowPlayingWideLyricsSecondaryContent(content, false, true)
        assertNull(translationHidden.lines)
        assertNull(phoneticHidden.lines)
        assertEquals(content.rawTranslatedLyrics, translationHidden.rawTranslation)
        assertNull(phoneticHidden.rawTranslation)
    }

    private fun presentation(
        fullPage: Boolean = false,
        compact: Boolean = false,
        phone: Boolean = false,
        width: androidx.compose.ui.unit.Dp = 1280.dp,
        advanced: Boolean = true,
        hasLyrics: Boolean = true
    ) = resolveNowPlayingWideLyricsPresentation(
        fullPage, compact, phone, width, hasLyrics,
        NowPlayingWideLyricsPreferences(
            advancedEnabled = advanced,
            showSecondary = true,
            usePhonetic = false,
            fontScales = LyricFontScales(0.9f, 1.1f, 1.3f, 1.5f),
            blurEnabled = true,
            blurAmount = 2.5f
        )
    )

    private fun lyricContent(): NowPlayingWideLyricsContent {
        val original = listOf(LyricEntry("original", 1_000L, 2_000L))
        val translation = listOf(
            LyricEntry("translated", 1_000L, 2_000L, listOf(WordTiming(1_000L, 1_500L, 3)))
        )
        return NowPlayingWideLyricsContent(
            lyrics = original,
            translatedLyrics = translation,
            phoneticLyrics = listOf(LyricEntry("romaji", 1_000L, 2_000L)),
            plainTranslatedLyrics = translation.map { it.copy(words = null) },
            rawLyrics = "[00:01.00]original",
            rawTranslatedLyrics = "[00:01.00]translated",
            synced = NowPlayingSyncedLyricContent(original, translation, true, "fixture-track", 350L)
        )
    }
}
