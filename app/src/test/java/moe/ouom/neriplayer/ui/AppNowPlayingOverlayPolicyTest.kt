package moe.ouom.neriplayer.ui

import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayTheme
import moe.ouom.neriplayer.ui.playback.visual.selectNowPlayingOverlayTheme
import moe.ouom.neriplayer.ui.playback.visual.releaseNowPlayingInputSession
import moe.ouom.neriplayer.ui.playback.visual.shouldUseNowPlayingContainerMorph
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.TextToolbar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verifyNoMoreInteractions

class AppNowPlayingOverlayPolicyTest {
    @Test
    fun `phones use the morph while tablets keep the default transition`() {
        assertTrue(shouldUseNowPlayingContainerMorph(smallestScreenWidthDp = 390))
        assertTrue(shouldUseNowPlayingContainerMorph(smallestScreenWidthDp = 599))
        assertFalse(shouldUseNowPlayingContainerMorph(smallestScreenWidthDp = 600))
        assertFalse(shouldUseNowPlayingContainerMorph(smallestScreenWidthDp = 800))
    }

    @Test
    fun `a landscape phone keeps its morph based on the smallest width`() {
        assertTrue(shouldUseNowPlayingContainerMorph(smallestScreenWidthDp = 390))
    }

    @Test
    fun `only large Material layouts retain the standalone cover flight`() {
        for (style in UiStyle.entries) {
            assertFalse(shouldUseStandaloneNowPlayingCoverFlight(style, 390))
            assertFalse(shouldUseStandaloneNowPlayingCoverFlight(style, 599))
        }
        assertTrue(shouldUseStandaloneNowPlayingCoverFlight(UiStyle.Material, 600))
        assertTrue(shouldUseStandaloneNowPlayingCoverFlight(UiStyle.Material, 800))
        assertFalse(shouldUseStandaloneNowPlayingCoverFlight(UiStyle.Clean, 600))
        assertFalse(shouldUseStandaloneNowPlayingCoverFlight(UiStyle.Clean, 800))
    }

    @Test
    fun `opening the player ends focus before hiding selection and the keyboard`() {
        val focus = mock(FocusManager::class.java)
        val toolbar = mock(TextToolbar::class.java)
        val keyboard = mock(SoftwareKeyboardController::class.java)
        releaseNowPlayingInputSession(focus, toolbar, keyboard)
        inOrder(focus, toolbar, keyboard).apply {
            verify(focus).clearFocus(true)
            verify(toolbar).hide()
            verify(keyboard).hide()
        }
        verifyNoMoreInteractions(focus, toolbar, keyboard)
    }

    @Test
    fun `focus and selection still end on devices without a software keyboard controller`() {
        val focus = mock(FocusManager::class.java)
        val toolbar = mock(TextToolbar::class.java)
        releaseNowPlayingInputSession(focus, toolbar, null)
        inOrder(focus, toolbar).apply {
            verify(focus).clearFocus(true)
            verify(toolbar).hide()
        }
        verifyNoMoreInteractions(focus, toolbar)
    }

    private fun theme(
        dynamic: Boolean,
        activeCoverSeed: String? = null
    ) = NowPlayingOverlayTheme(
        dynamicColorEnabled = dynamic,
        activeCoverSeedHex = activeCoverSeed,
        seedColorHex = "112233",
        paletteStyle = PaletteStyle.TonalSpot,
        colorSpec = ColorSpec.SpecVersion.Default
    )

    @Test
    fun `system palette is used only while dynamic color lacks a cover and extracted seed`() {
        val withoutCover = selectNowPlayingOverlayTheme(theme(dynamic = true), null)
        assertTrue(withoutCover.useSystemDynamic)
        assertEquals("112233", withoutCover.seedColorHex)

        assertFalse(selectNowPlayingOverlayTheme(theme(dynamic = true), "cover").useSystemDynamic)
        val extracted = selectNowPlayingOverlayTheme(theme(true, "445566"), null)
        assertFalse(extracted.useSystemDynamic)
        assertEquals("445566", extracted.seedColorHex)
    }

    @Test
    fun `explicit theme ignores a cached cover seed`() {
        val selection = selectNowPlayingOverlayTheme(theme(false, "445566"), null)
        assertFalse(selection.useSystemDynamic)
        assertEquals("112233", selection.seedColorHex)
    }
}
