package moe.ouom.neriplayer.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppThemeRevealPresentationTest {
    @Test
    fun revealOnlyStartsAfterOriginAndFallbackColorAreAvailable() {
        val origin = Offset(12f, 30f)
        assertNull(appThemeRevealPresentation(null, 0xff112233.toInt(), 7))
        assertNull(appThemeRevealPresentation(origin, null, 7))

        assertEquals(
            AppThemeRevealPresentation(origin, 0xff112233.toInt(), 7),
            appThemeRevealPresentation(origin, 0xff112233.toInt(), 7)
        )
    }
}
