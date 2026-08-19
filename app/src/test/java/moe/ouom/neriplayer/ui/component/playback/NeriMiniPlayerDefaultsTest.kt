package moe.ouom.neriplayer.ui.component.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriMiniPlayerDefaultsTest {
    @Test
    fun normalFontScaleKeepsOriginalMetadataSizeRange() {
        val range = resolveMiniPlayerTextAutoSizeRange(
            baseFontSizeSp = 16f,
            maxLineHeightDp = 24f,
            fontScale = 1f,
            minVisualFontSizeSp = 10f,
            lineHeightEm = 1.5f
        )

        assertEquals(
            16f,
            range.maxFontSizeSp,
            0.001f
        )
        assertEquals(10f, range.minFontSizeSp, 0.001f)
    }

    @Test
    fun largeFontScaleReducesMaximumToTheAvailableLineHeight() {
        val titleRange = resolveMiniPlayerTextAutoSizeRange(
            baseFontSizeSp = 16f,
            maxLineHeightDp = 24f,
            fontScale = 2f,
            minVisualFontSizeSp = 10f,
            lineHeightEm = 1.5f
        )
        val artistRange = resolveMiniPlayerTextAutoSizeRange(
            baseFontSizeSp = 14f,
            maxLineHeightDp = 20f,
            fontScale = 2f,
            minVisualFontSizeSp = 9f,
            lineHeightEm = 20f / 14f
        )

        assertEquals(8f, titleRange.maxFontSizeSp, 0.001f)
        assertEquals(5f, titleRange.minFontSizeSp, 0.001f)
        assertTrue(titleRange.maxFontSizeSp > artistRange.maxFontSizeSp)
        assertTrue(titleRange.minFontSizeSp <= titleRange.maxFontSizeSp)
    }
}
