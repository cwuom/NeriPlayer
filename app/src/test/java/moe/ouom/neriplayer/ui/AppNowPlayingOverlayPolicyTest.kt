package moe.ouom.neriplayer.ui

import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayTheme
import moe.ouom.neriplayer.ui.playback.visual.selectNowPlayingOverlayTheme
import moe.ouom.neriplayer.ui.playback.visual.releaseNowPlayingInputSession
import moe.ouom.neriplayer.ui.playback.visual.shouldUseNowPlayingContainerMorph
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
    fun `clean morph is limited to phone device widths including the 599 to 600 boundary`() {
        assertTrue(shouldUseNowPlayingContainerMorph(cleanStyle = true, smallestScreenWidthDp = 599))
        assertFalse(shouldUseNowPlayingContainerMorph(cleanStyle = true, smallestScreenWidthDp = 600))
        assertFalse(shouldUseNowPlayingContainerMorph(cleanStyle = true, smallestScreenWidthDp = 800))
        assertFalse(shouldUseNowPlayingContainerMorph(cleanStyle = false, smallestScreenWidthDp = 390))
        assertFalse(shouldUseNowPlayingContainerMorph(cleanStyle = false, smallestScreenWidthDp = 800))
    }

    @Test
    fun `a landscape phone keeps its morph based on the smallest width`() {
        assertTrue(shouldUseNowPlayingContainerMorph(cleanStyle = true, smallestScreenWidthDp = 390))
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
