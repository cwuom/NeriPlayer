package moe.ouom.neriplayer.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScalePage
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlSize
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.nowPlayingBackIcon
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingAdaptiveCoverSize
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingTopBarButtonSize
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingPhoneTopActionButtonSize
import moe.ouom.neriplayer.ui.screen.nowplaying.resolveNowPlayingWideLayoutSpec
import moe.ouom.neriplayer.ui.screen.nowplaying.resolveNowPlayingWideLyricViewport
import moe.ouom.neriplayer.ui.screen.nowplaying.isNowPlayingPhoneLandscape
import moe.ouom.neriplayer.ui.screen.nowplaying.isNowPlayingTabletPortrait
import moe.ouom.neriplayer.ui.screen.nowplaying.resolveNowPlayingTabletPortraitLayoutSpec
import moe.ouom.neriplayer.ui.screen.nowplaying.resolveNowPlayingControlBaseSizes
import moe.ouom.neriplayer.ui.screen.nowplaying.resolveNowPlayingLyricFontPage
import moe.ouom.neriplayer.ui.screen.nowplaying.resolveNowPlayingPageControlSize
import moe.ouom.neriplayer.ui.screen.nowplaying.shouldUseStandaloneNowPlayingLyricsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingAdaptiveLayoutTest {
    @Test
    fun `tablet landscape lifts progress within the existing gap and phone retains its footer`() {
        val tablet = resolveNowPlayingWideLayoutSpec(1280.dp, 800.dp)
        val shortTablet = resolveNowPlayingWideLayoutSpec(1280.dp, 480.dp)
        val phone = resolveNowPlayingWideLayoutSpec(840.dp, 360.dp, phoneLandscape = true)
        assertEquals((-12).dp, tablet.progressVerticalOffset)
        assertEquals((-4).dp, shortTablet.progressVerticalOffset)
        assertEquals(0.dp, phone.progressVerticalOffset)
        listOf(tablet, shortTablet).forEach { spec ->
            assertTrue(-spec.progressVerticalOffset <= spec.sectionSpacing)
        }
    }

    @Test
    fun `tablet portrait specialization does not change phone or tablet landscape`() {
        assertTrue(isNowPlayingTabletPortrait(isLandscape = false, smallestScreenWidthDp = 600))
        assertTrue(isNowPlayingTabletPortrait(isLandscape = false, smallestScreenWidthDp = 800))
        assertFalse(isNowPlayingTabletPortrait(isLandscape = false, smallestScreenWidthDp = 360))
        assertFalse(isNowPlayingTabletPortrait(isLandscape = true, smallestScreenWidthDp = 800))
    }

    @Test
    fun `tablet portrait limits cover and action widths while reserving height for lyrics`() {
        val tablet = resolveNowPlayingTabletPortraitLayoutSpec(800.dp, 1280.dp)
        assertEquals(560.dp, tablet.contentWidth)
        assertEquals(336.dp, tablet.coverSize)
        assertEquals(440.dp, tablet.controlsWidth)
        assertEquals(400.dp, tablet.toolbarWidth)
        val smallTablet = resolveNowPlayingTabletPortraitLayoutSpec(600.dp, 960.dp)
        assertEquals(240.dp, smallTablet.coverSize)
        val scaledTablet = resolveNowPlayingTabletPortraitLayoutSpec(400.dp, 640.dp)
        assertEquals(400.dp, scaledTablet.contentWidth)
        assertEquals(80.dp, scaledTablet.coverSize)
        assertTrue(scaledTablet.coverSize <= 640.dp * 0.30f)
        assertTrue(scaledTablet.controlsWidth <= scaledTablet.contentWidth)
        assertTrue(scaledTablet.toolbarWidth <= scaledTablet.contentWidth)
        assertEquals(0.dp, resolveNowPlayingTabletPortraitLayoutSpec(400.dp, 400.dp).coverSize)
    }

    @Test
    fun `both landscape page states use the shared player layout while portrait keeps the lyrics page`() {
        assertFalse(shouldUseStandaloneNowPlayingLyricsPage(showLyricsScreen = false, wideLandscape = true))
        assertFalse(shouldUseStandaloneNowPlayingLyricsPage(showLyricsScreen = true, wideLandscape = true))
        assertFalse(shouldUseStandaloneNowPlayingLyricsPage(showLyricsScreen = false, wideLandscape = false))
        assertTrue(shouldUseStandaloneNowPlayingLyricsPage(showLyricsScreen = true, wideLandscape = false))
    }

    @Test
    fun `landscape page states retain independent lyric fonts and control preferences`() {
        val scales = LyricFontScales(0.9f, 1.1f, 1.3f, 1.5f)
        val coverPage = resolveNowPlayingLyricFontPage(showLyricsScreen = false)
        val lyricsPage = resolveNowPlayingLyricFontPage(showLyricsScreen = true)
        assertEquals(LyricFontScalePage.COVER, coverPage)
        assertEquals(LyricFontScalePage.LYRICS, lyricsPage)
        assertEquals(0.9f, scales.scaleFor(scales.lyricTargetFor(coverPage)), 0f)
        assertEquals(1.1f, scales.scaleFor(scales.translationTargetFor(coverPage)), 0f)
        assertEquals(1.3f, scales.scaleFor(scales.lyricTargetFor(lyricsPage)), 0f)
        assertEquals(1.5f, scales.scaleFor(scales.translationTargetFor(lyricsPage)), 0f)
        val preferences = PlaybackControlLayoutPreferences(
            nowPlayingSize = PlaybackControlSize.SMALL,
            lyricsSize = PlaybackControlSize.LARGE
        )
        assertEquals(PlaybackControlSize.SMALL, resolveNowPlayingPageControlSize(preferences, showLyricsScreen = false))
        assertEquals(PlaybackControlSize.LARGE, resolveNowPlayingPageControlSize(preferences, showLyricsScreen = true))
    }

    @Test
    fun `wide phone uses height constrained layout even with a tablet width`() {
        assertTrue(resolveNowPlayingWideLayoutSpec(840.dp, 360.dp).compactHeight)
        assertTrue(resolveNowPlayingWideLayoutSpec(1280.dp, 480.dp).compactHeight)
        assertFalse(resolveNowPlayingWideLayoutSpec(1280.dp, 800.dp).compactHeight)
    }

    @Test
    fun `expanded tablet keeps controls together and gives lyrics the wider pane`() {
        val spec = resolveNowPlayingWideLayoutSpec(1280.dp, 800.dp)
        val lyricsWidth = 1280.dp - spec.playerPaneWidth - spec.paneSpacing
        assertTrue(spec.playerPaneWidth <= 500.dp)
        assertTrue(lyricsWidth > spec.playerPaneWidth)
    }

    @Test
    fun `cover yields to controls instead of measuring against the full screen`() {
        assertEquals(160.dp, resolveNowPlayingAdaptiveCoverSize(320.dp, 160.dp, 420.dp))
        assertEquals(320.dp, resolveNowPlayingAdaptiveCoverSize(320.dp, 600.dp, 420.dp))
        assertEquals(420.dp, resolveNowPlayingAdaptiveCoverSize(600.dp, 600.dp, 420.dp))
        assertEquals(0.dp, resolveNowPlayingAdaptiveCoverSize(320.dp, 0.dp, 420.dp))
    }

    @Test
    fun `phone lyric fades leave a visible central area in a short pane`() {
        val viewport = resolveNowPlayingWideLyricViewport(120.dp)
        assertTrue(viewport.offset < 120.dp)
        assertTrue(viewport.topFadeLength + viewport.bottomFadeLength < 120.dp * 0.5f)
        val tabletViewport = resolveNowPlayingWideLyricViewport(800.dp)
        assertTrue(tabletViewport.offset <= 72.dp)
        assertTrue(tabletViewport.topFadeLength <= 132.dp)
        assertTrue(tabletViewport.bottomFadeLength <= 220.dp)
    }

    @Test
    fun `top bar actions fit a narrow player pane without changing roomy panes`() {
        assertEquals(40.dp, resolveNowPlayingTopBarButtonSize(160.dp, 58.dp, commentAvailable = true))
        assertEquals(48.dp, resolveNowPlayingTopBarButtonSize(160.dp, 48.dp, commentAvailable = false))
        assertEquals(58.dp, resolveNowPlayingTopBarButtonSize(340.dp, 58.dp, commentAvailable = true))
    }

    @Test
    fun `phone header actions leave room for the track identity`() {
        assertEquals(40.dp, resolveNowPlayingPhoneTopActionButtonSize(240.dp, 58.dp, commentAvailable = true))
        assertEquals(48.dp, resolveNowPlayingPhoneTopActionButtonSize(240.dp, 48.dp, commentAvailable = false))
        assertEquals(58.dp, resolveNowPlayingPhoneTopActionButtonSize(400.dp, 58.dp, commentAvailable = true))
    }

    @Test
    fun `phone specialization uses smallest screen width instead of short window height`() {
        assertTrue(isNowPlayingPhoneLandscape(isLandscape = true, smallestScreenWidthDp = 360))
        assertFalse(isNowPlayingPhoneLandscape(isLandscape = false, smallestScreenWidthDp = 360))
        assertFalse(isNowPlayingPhoneLandscape(isLandscape = true, smallestScreenWidthDp = 600))
        assertFalse(isNowPlayingPhoneLandscape(isLandscape = true, smallestScreenWidthDp = 800))
        assertTrue(resolveNowPlayingWideLayoutSpec(840.dp, 560.dp, phoneLandscape = true).compactHeight)
    }

    @Test
    fun `phone landscape reduces controls while tablet and portrait keep previous sizes`() {
        val phone = resolveNowPlayingControlBaseSizes(true, true, false, false)
        val tablet = resolveNowPlayingControlBaseSizes(false, true, false, false)
        val shortTablet = resolveNowPlayingControlBaseSizes(false, true, true, false)
        val portrait = resolveNowPlayingControlBaseSizes(false, false, false, false)
        assertEquals(36.dp, phone.secondaryButtonSize)
        assertEquals(40.dp, phone.primaryButtonSize)
        assertEquals(20.dp, phone.iconSize)
        assertTrue(phone.spacing < tablet.spacing)
        assertEquals(46.dp, tablet.secondaryButtonSize)
        assertEquals(50.dp, tablet.primaryButtonSize)
        assertEquals(42.dp, shortTablet.secondaryButtonSize)
        assertEquals(46.dp, shortTablet.primaryButtonSize)
        assertEquals(42.dp, portrait.primaryButtonSize)
    }

    @Test
    fun `only phone landscape uses the left pointing back icon`() {
        assertSame(Icons.AutoMirrored.Outlined.ArrowBack, nowPlayingBackIcon(phoneLandscape = true))
        assertSame(Icons.Outlined.KeyboardArrowDown, nowPlayingBackIcon(phoneLandscape = false))
    }

    @Test
    fun `cover leaves vertical breathing room without consuming control space`() {
        assertEquals(8.dp, resolveNowPlayingWideLayoutSpec(840.dp, 360.dp, phoneLandscape = true).coverVerticalPadding)
        assertEquals(12.dp, resolveNowPlayingWideLayoutSpec(1280.dp, 800.dp).coverVerticalPadding)
    }
}
