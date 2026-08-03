package moe.ouom.neriplayer.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingLyricsPreferencesTest {

    @Test
    fun defaultPreferencesUseShadowRendering() {
        assertEquals(
            FLOATING_LYRICS_RENDER_STYLE_SHADOW,
            FloatingLyricsPreferences().renderStyle
        )
    }

    @Test
    fun normalizeFloatingLyricsRenderStyleFallsBackToShadow() {
        assertEquals(
            FLOATING_LYRICS_RENDER_STYLE_SHADOW,
            normalizeFloatingLyricsRenderStyle("unsupported")
        )
        assertEquals(
            FLOATING_LYRICS_RENDER_STYLE_OUTLINE,
            normalizeFloatingLyricsRenderStyle(" OUTLINE ")
        )
    }
}
