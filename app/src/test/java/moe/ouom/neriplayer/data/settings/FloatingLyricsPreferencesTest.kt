package moe.ouom.neriplayer.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun defaultPreferencesAllowLongPressDragging() {
        assertTrue(FloatingLyricsPreferences().longPressDragEnabled)
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

    @Test
    fun legacyPortraitPositionSeedsLandscapePosition() {
        val preferences = FloatingLyricsPreferences(positionX = 0.35f, positionY = 0.65f)

        assertEquals(0.35f, preferences.landscapePositionX)
        assertEquals(0.65f, preferences.landscapePositionY)
    }

    @Test
    fun normalizedPositionsClampBothOrientations() {
        val normalized = FloatingLyricsPreferences(
            positionX = -1f,
            positionY = 2f,
            landscapePositionX = 2f,
            landscapePositionY = -1f
        ).normalized()

        assertEquals(0f, normalized.positionX)
        assertEquals(1f, normalized.positionY)
        assertEquals(1f, normalized.landscapePositionX)
        assertEquals(0f, normalized.landscapePositionY)
    }

    @Test
    fun positionResolverUsesOrientationSpecificValues() {
        val preferences = FloatingLyricsPreferences(
            positionX = 0.1f,
            positionY = 0.2f,
            landscapePositionX = 0.8f,
            landscapePositionY = 0.9f
        )

        assertEquals(0.1f, resolveFloatingLyricsPositionX(preferences, isLandscape = false))
        assertEquals(0.2f, resolveFloatingLyricsPositionY(preferences, isLandscape = false))
        assertEquals(0.8f, resolveFloatingLyricsPositionX(preferences, isLandscape = true))
        assertEquals(0.9f, resolveFloatingLyricsPositionY(preferences, isLandscape = true))
    }
}
