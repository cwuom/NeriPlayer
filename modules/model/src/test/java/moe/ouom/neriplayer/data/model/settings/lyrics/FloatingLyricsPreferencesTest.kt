package moe.ouom.neriplayer.data.model.settings.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingLyricsPreferencesTest {

    @Test
    fun `alpha values fall back when not finite and clamp otherwise`() {
        assertEquals(0.72f, normalizeFloatingLyricsAlpha(Float.NaN), 0f)
        assertEquals(0.5f, normalizeFloatingLyricsAlpha(Float.NEGATIVE_INFINITY, fallback = 0.5f), 0f)
        assertEquals(1f, normalizeFloatingLyricsAlpha(2f), 0f)
        assertEquals(0f, normalizeFloatingLyricsAlpha(-1f), 0f)
    }

    @Test
    fun `missing stored alphas use the per field defaults`() {
        assertEquals(0.72f, resolveFloatingLyricsTranslationAlpha(null), 0f)
        assertEquals(0.72f, resolveFloatingLyricsTranslationAlpha(Float.NaN), 0f)
        assertEquals(0.72f, resolveFloatingLyricsTranslationAlpha(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0.4f, resolveFloatingLyricsTranslationAlpha(0.4f), 0f)

        assertEquals(1f, resolveFloatingLyricsLyricAlpha(null), 0f)
        assertEquals(1f, resolveFloatingLyricsLyricAlpha(Float.NaN), 0f)
        assertEquals(1f, resolveFloatingLyricsLyricAlpha(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(0f, resolveFloatingLyricsLyricAlpha(-3f), 0f)
    }

    @Test
    fun `missing translation outline width derives from the main outline`() {
        assertEquals(1.152f, resolveFloatingLyricsTranslationOutlineWidthDp(null, outlineWidthDp = 1.6f), 1e-6f)
        assertEquals(0.3f, resolveFloatingLyricsTranslationOutlineWidthDp(Float.NaN, outlineWidthDp = 0.2f), 1e-6f)
        assertEquals(0f, resolveFloatingLyricsTranslationOutlineWidthDp(Float.POSITIVE_INFINITY, outlineWidthDp = 0f), 0f)
        assertEquals(4f, resolveFloatingLyricsTranslationOutlineWidthDp(9f, outlineWidthDp = 1f), 0f)
    }

    @Test
    fun `alignment and render style accept known values ignoring case`() {
        assertEquals(FLOATING_LYRICS_ALIGNMENT_LEFT, normalizeFloatingLyricsAlignment(" LEFT "))
        assertEquals(FLOATING_LYRICS_ALIGNMENT_RIGHT, normalizeFloatingLyricsAlignment("right"))
        assertEquals(FLOATING_LYRICS_ALIGNMENT_CENTER, normalizeFloatingLyricsAlignment("justify"))
        assertEquals(FLOATING_LYRICS_ALIGNMENT_CENTER, normalizeFloatingLyricsAlignment(null))

        assertEquals(FLOATING_LYRICS_RENDER_STYLE_OUTLINE, normalizeFloatingLyricsRenderStyle(" Outline"))
        assertEquals(FLOATING_LYRICS_RENDER_STYLE_SHADOW, normalizeFloatingLyricsRenderStyle("glow"))
        assertEquals(FLOATING_LYRICS_RENDER_STYLE_SHADOW, normalizeFloatingLyricsRenderStyle(null))
    }

    @Test
    fun `text colors are normalised to six upper case hex digits`() {
        assertEquals("1A2B3C", normalizeFloatingLyricsColorHex(" #1a2b3c "))
        assertEquals("FFFFFF", normalizeFloatingLyricsColorHex("#12345"))
        assertEquals("FFFFFF", normalizeFloatingLyricsColorHex("GGGGGG"))
        assertEquals("FFFFFF", normalizeFloatingLyricsColorHex(null))
    }

    @Test
    fun `normalising preferences repairs every stored field`() {
        val normalized = FloatingLyricsPreferences(
            textColorHex = "#abcdef",
            outlineColorHex = "0a0a0a",
            fontSizeSp = 100f,
            outlineWidthDp = -1f,
            lyricAlpha = Float.NaN,
            translationOutlineWidthDp = 10f,
            translationAlpha = 2f,
            maxWidthDp = 10f,
            positionX = -1f,
            positionY = 2f,
            landscapePositionX = 0.5f,
            landscapePositionY = 3f,
            alignment = "RIGHT",
            renderStyle = "OUTLINE"
        ).normalized()

        assertEquals(
            FloatingLyricsPreferences(
                textColorHex = "ABCDEF",
                outlineColorHex = "0A0A0A",
                fontSizeSp = MAX_FLOATING_LYRICS_FONT_SIZE_SP,
                outlineWidthDp = MIN_FLOATING_LYRICS_OUTLINE_WIDTH_DP,
                lyricAlpha = 1f,
                translationOutlineWidthDp = MAX_FLOATING_LYRICS_OUTLINE_WIDTH_DP,
                translationAlpha = 1f,
                maxWidthDp = MIN_FLOATING_LYRICS_MAX_WIDTH_DP,
                positionX = 0f,
                positionY = 1f,
                landscapePositionX = 0.5f,
                landscapePositionY = 1f,
                alignment = FLOATING_LYRICS_ALIGNMENT_RIGHT,
                renderStyle = FLOATING_LYRICS_RENDER_STYLE_OUTLINE
            ),
            normalized
        )
    }
}
