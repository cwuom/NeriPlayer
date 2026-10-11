package moe.ouom.neriplayer.core.player.lyrics

import moe.ouom.neriplayer.data.model.settings.lyrics.FloatingLyricsPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FloatingLyricsEffectMathTest {
    @Test
    fun `effect alpha is clamped and squared`() {
        assertEquals(0f, resolveFloatingLyricsEffectAlpha(Float.NaN), 0f)
        assertEquals(0f, resolveFloatingLyricsEffectAlpha(-0.5f), 0f)
        assertEquals(0.25f, resolveFloatingLyricsEffectAlpha(0.5f), 0.0001f)
        assertEquals(1f, resolveFloatingLyricsEffectAlpha(3f), 0f)
    }

    @Test
    fun `edge fade width uses density and never exceeds the content fraction`() {
        val wide = AnimatedOutlinedLyricTextView.resolveEdgeFadeWidthPx(contentWidthPx = 10_000f, density = 2f)
        val unknownDensity = AnimatedOutlinedLyricTextView.resolveEdgeFadeWidthPx(contentWidthPx = 10_000f, density = Float.NaN)
        val zeroDensity = AnimatedOutlinedLyricTextView.resolveEdgeFadeWidthPx(contentWidthPx = 10_000f, density = 0f)

        assertEquals(unknownDensity * 2f, wide, 0.0001f)
        assertEquals(unknownDensity, zeroDensity, 0f)
        assertEquals(
            AnimatedOutlinedLyricTextView.resolveEdgeFadeWidthPx(contentWidthPx = 1f, density = 2f),
            AnimatedOutlinedLyricTextView.resolveEdgeFadeWidthPx(contentWidthPx = -20f, density = 2f),
            0f
        )
    }

    @Test
    fun `blank or disabled translations are hidden from the overlay`() {
        val blank = resolveFloatingLyricsOverlayText(FloatingLyricsPreferences(), "Line", "  ")
        val disabled = resolveFloatingLyricsOverlayText(
            FloatingLyricsPreferences(showTranslation = false),
            "Line",
            "Translated"
        )
        val missing = resolveFloatingLyricsOverlayText(FloatingLyricsPreferences(), "Line", null)

        for (text in listOf(blank, disabled, missing)) {
            assertFalse(text.showTranslation)
            assertEquals("", text.translation)
            assertEquals(AnimatedOutlinedLyricTextView.resolveRevealDurationMs("Line"), text.revealDurationMs)
        }
    }
}
