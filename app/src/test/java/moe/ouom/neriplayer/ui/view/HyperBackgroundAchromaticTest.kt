package moe.ouom.neriplayer.ui.view

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class HyperBackgroundAchromaticTest {
    @Test
    fun nearBlackSwatchesWithNoisyHueAreTreatedAsAchromatic() {
        // #0A0510 reports ~50% HSL saturation but carries almost no real chroma
        assertTrue(isAchromaticCoverColor(0xFF0A0510.toInt()))
        assertTrue(isAchromaticCoverColor(0xFF000000.toInt()))
        assertTrue(isAchromaticCoverColor(0xFF7F7F80.toInt()))
        assertTrue(isAchromaticCoverColor(0xFFFAFAFC.toInt()))
    }

    @Test
    fun blackCoverSwatchesStayNeutralAfterLightnessLift() {
        val noisyBlack = 0xFF0A0510.toInt()
        val accent = pickAccentPaletteColor(noisyBlack, noisyBlack, 0xFF050505.toInt())
        DynamicBackgroundColorRole.entries.forEach { role ->
            listOf(true, false).forEach { isDark ->
                val softened = softenPaletteColor(accent, noisyBlack, isDark, role)
                assertTrue(
                    "role=$role dark=$isDark produced ${Integer.toHexString(softened)}",
                    isAchromaticCoverColor(softened)
                )
            }
        }
    }

    @Test
    fun colorfulSwatchesStayChromatic() {
        assertFalse(isAchromaticCoverColor(0xFF2E6BE6.toInt()))
        assertFalse(isAchromaticCoverColor(0xFF8A1C1C.toInt()))
    }
}
