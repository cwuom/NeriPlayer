package moe.ouom.neriplayer.ui

import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayTheme
import moe.ouom.neriplayer.ui.playback.visual.selectNowPlayingOverlayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNowPlayingOverlayPolicyTest {
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
