package moe.ouom.neriplayer.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.nowplaying.PlaybackActionToolbarLayout
import moe.ouom.neriplayer.ui.screen.nowplaying.isNowPlayingPhoneLandscape
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverToolbarLayoutSpec
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.toolbarPreferredPadding
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.toolbarRowArrangement
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.toolbarRowVerticalPadding
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.nowPlayingLyricsToolbarDescription
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.nowPlayingLyricsToolbarIcon
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingLyricsToolbarAction
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingCoverToolbarLayoutSpec
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.toolbarWidthFraction
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.shouldAdjustNowPlayingLyricsBehavior
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class NowPlayingCoverActionToolbarTest {
    @Test
    fun `expanded landscape narrows the action row while compact and portrait keep full width`() {
        assertEquals(0.9f, toolbarWidthFraction(spec(wide = true)), 0f)
        assertEquals(1f, toolbarWidthFraction(spec(wide = true).copy(compactHeight = true)), 0f)
        assertEquals(1f, toolbarWidthFraction(spec()), 0f)
        assertEquals(1f, toolbarWidthFraction(spec(compact = true, docked = true)), 0f)
    }

    @Test
    fun `toolbar layout preserves configured targets and disables the dock only in short height`() {
        val expanded = resolveNowPlayingCoverToolbarLayoutSpec(
            true, false, true, 22.dp, 56.dp, false
        )
        val short = resolveNowPlayingCoverToolbarLayoutSpec(
            true, false, true, 22.dp, 56.dp, true
        )
        val portrait = resolveNowPlayingCoverToolbarLayoutSpec(
            false, true, true, 22.dp, 56.dp, false
        )
        val undocked = resolveNowPlayingCoverToolbarLayoutSpec(
            false, false, false, 22.dp, 56.dp, false
        )
        assertTrue(expanded.docked)
        assertTrue(portrait.docked)
        assertFalse(short.docked)
        assertFalse(undocked.docked)
        assertTrue(expanded.wideLandscape)
        assertTrue(short.compactHeight)
        assertTrue(portrait.compactPortrait)
        listOf(expanded, short, portrait, undocked).forEach { layout ->
            assertEquals(22.dp, layout.iconSize)
            assertEquals(56.dp, layout.minimumTouchTarget)
        }
        assertEquals(0.dp, toolbarRowVerticalPadding(short))
        assertEquals(12.dp, toolbarRowVerticalPadding(expanded))
    }

    @Test
    fun `landscape lyrics action opens adjustment without changing the selected page`() {
        val calls = mutableListOf<String>()
        val action = resolveNowPlayingLyricsToolbarAction(
            lyricsAdjustBehavior = true,
            onAdjust = { calls += "adjust" },
            onSwitchPage = { calls += "page" }
        )
        action()
        assertEquals(listOf("adjust"), calls)
        assertSame(Icons.Outlined.Tune, nowPlayingLyricsToolbarIcon(true))
        assertEquals(CoreCommonR.string.lyrics_adjust_behavior, nowPlayingLyricsToolbarDescription(true))
    }

    @Test
    fun `portrait lyrics action keeps the original page switch and description`() {
        val calls = mutableListOf<String>()
        resolveNowPlayingLyricsToolbarAction(
            lyricsAdjustBehavior = false,
            onAdjust = { calls += "adjust" },
            onSwitchPage = { calls += "page" }
        )()
        assertEquals(listOf("page"), calls)
        assertSame(Icons.Outlined.LibraryMusic, nowPlayingLyricsToolbarIcon(false))
        assertEquals(CoreCommonR.string.lyrics_title, nowPlayingLyricsToolbarDescription(false))
        assertEquals(
            CoreCommonR.string.lyrics_back_to_cover,
            nowPlayingLyricsToolbarDescription(false, CoreCommonR.string.lyrics_back_to_cover)
        )
        assertEquals(
            CoreCommonR.string.lyrics_adjust_behavior,
            nowPlayingLyricsToolbarDescription(true, CoreCommonR.string.lyrics_back_to_cover)
        )
    }

    @Test
    fun `lyric adjustment requires tablet landscape regardless of current window width`() {
        listOf(360, 599, 600, 800).forEach { smallestWidth ->
            val phoneLandscape = isNowPlayingPhoneLandscape(true, smallestWidth)
            assertEquals(
                smallestWidth >= 600,
                shouldAdjustNowPlayingLyricsBehavior(isLandscape = true, phoneLandscape = phoneLandscape)
            )
            assertFalse(shouldAdjustNowPlayingLyricsBehavior(
                isLandscape = false,
                phoneLandscape = isNowPlayingPhoneLandscape(false, smallestWidth)
            ))
        }
    }

    @Test
    fun `narrow tablet adjustment preserves the original toolbar geometry`() {
        val narrow = resolveNowPlayingCoverToolbarLayoutSpec(
            wideLandscape = false,
            compactPortrait = false,
            dockEnabled = false,
            iconSize = 20.dp,
            minimumTouchTarget = 48.dp,
            compactHeight = false,
            lyricsAdjustBehavior = shouldAdjustNowPlayingLyricsBehavior(
                isLandscape = true,
                phoneLandscape = isNowPlayingPhoneLandscape(true, 600)
            )
        )
        assertFalse(narrow.wideLandscape)
        assertTrue(narrow.lyricsAdjustBehavior)
        assertEquals(1f, toolbarWidthFraction(narrow), 0f)
        assertEquals(6.dp, toolbarPreferredPadding(narrow))
        assertEquals(8.dp, toolbarRowVerticalPadding(narrow))
        assertSame(Arrangement.SpaceBetween, toolbarRowArrangement(layout(), narrow))
        assertSame(Icons.Outlined.Tune, nowPlayingLyricsToolbarIcon(narrow.lyricsAdjustBehavior))
        assertEquals(
            CoreCommonR.string.lyrics_adjust_behavior,
            nowPlayingLyricsToolbarDescription(narrow.lyricsAdjustBehavior)
        )
        val calls = mutableListOf<String>()
        resolveNowPlayingLyricsToolbarAction(
            lyricsAdjustBehavior = narrow.lyricsAdjustBehavior,
            onAdjust = { calls += "adjust" },
            onSwitchPage = { calls += "page" }
        )()
        assertEquals(listOf("adjust"), calls)
    }

    @Test
    fun `legacy toolbar callers inherit their previous lyric action semantics`() {
        assertTrue(spec(wide = true).lyricsAdjustBehavior)
        assertFalse(spec().lyricsAdjustBehavior)
        assertTrue(resolveNowPlayingCoverToolbarLayoutSpec(
            true, false, false, 20.dp, 48.dp, false
        ).lyricsAdjustBehavior)
        assertFalse(resolveNowPlayingCoverToolbarLayoutSpec(
            false, false, false, 20.dp, 48.dp, false
        ).lyricsAdjustBehavior)
    }

    @Test
    fun `toolbar padding preserves compact and docked layouts`() {
        assertEquals(0.dp, toolbarPreferredPadding(spec(compact = true)))
        assertEquals(6.dp, toolbarPreferredPadding(spec()))
        assertEquals(18.dp, toolbarPreferredPadding(spec(docked = true)))
        assertEquals(18.dp, toolbarPreferredPadding(spec(wide = true)))
        assertEquals(8.dp, toolbarRowVerticalPadding(spec()))
        assertEquals(12.dp, toolbarRowVerticalPadding(spec(docked = true)))
        assertEquals(12.dp, toolbarRowVerticalPadding(spec(wide = true)))
    }

    @Test
    fun `equal width slots override spaced action arrangement`() {
        assertSame(Arrangement.Start, toolbarRowArrangement(layout(equalSlots = true), spec(docked = true)))
        assertSame(Arrangement.SpaceBetween, toolbarRowArrangement(layout(), spec()))
        assertSame(Arrangement.SpaceEvenly, toolbarRowArrangement(layout(), spec(docked = true)))
        assertSame(Arrangement.SpaceEvenly, toolbarRowArrangement(layout(), spec(wide = true)))
    }

    @Test
    fun `short landscape removes decorative padding while keeping action slots`() {
        val compactLandscape = spec(wide = true).copy(compactHeight = true)
        assertEquals(0.dp, toolbarPreferredPadding(compactLandscape))
        assertEquals(0.dp, toolbarRowVerticalPadding(compactLandscape))
        assertSame(Arrangement.SpaceEvenly, toolbarRowArrangement(layout(), compactLandscape))
    }

    private fun spec(
        wide: Boolean = false,
        compact: Boolean = false,
        docked: Boolean = false
    ) = NowPlayingCoverToolbarLayoutSpec(wide, compact, docked, 20.dp, 48.dp)

    private fun layout(equalSlots: Boolean = false) =
        PlaybackActionToolbarLayout(6.dp, 48.dp, 20.dp, equalSlots)
}
