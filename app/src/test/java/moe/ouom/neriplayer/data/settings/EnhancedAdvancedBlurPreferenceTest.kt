package moe.ouom.neriplayer.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class EnhancedAdvancedBlurPreferenceTest {
    @Test
    fun normalizeClampsAndSnapsRadius() {
        assertEquals(
            MIN_ENHANCED_ADVANCED_BLUR_RADIUS_DP,
            EnhancedAdvancedBlurPreference.normalize(0f)
        )
        assertEquals(24f, EnhancedAdvancedBlurPreference.normalize(25f))
        assertEquals(28f, EnhancedAdvancedBlurPreference.normalize(27f))
        assertEquals(
            MAX_ENHANCED_ADVANCED_BLUR_RADIUS_DP,
            EnhancedAdvancedBlurPreference.normalize(100f)
        )
    }

    @Test
    fun normalizeReplacesNonFiniteValuesWithDefault() {
        assertEquals(
            DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP,
            EnhancedAdvancedBlurPreference.normalize(Float.NaN)
        )
        assertEquals(
            DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP,
            EnhancedAdvancedBlurPreference.normalize(Float.POSITIVE_INFINITY)
        )
    }
}
