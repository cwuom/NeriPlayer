package moe.ouom.neriplayer.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedBlurQualityTest {
    @Test
    fun missingPreferenceUsesLowOnlyForDimensityDevices() {
        assertEquals(
            AdvancedBlurQuality.Low,
            AdvancedBlurQualityPreference.resolve(value = null, isDimensityDevice = true)
        )
        assertEquals(
            AdvancedBlurQuality.Default,
            AdvancedBlurQualityPreference.resolve(value = null, isDimensityDevice = false)
        )
    }

    @Test
    fun savedPreferenceOverridesDeviceDefaultAndNormalizesInvalidValues() {
        assertEquals(
            AdvancedBlurQuality.High,
            AdvancedBlurQualityPreference.resolve(value = "HIGH", isDimensityDevice = true)
        )
        assertEquals(
            DEFAULT_ADVANCED_BLUR_QUALITY,
            AdvancedBlurQualityPreference.normalize("unsupported")
        )
    }

    @Test
    fun detectsDimensityModelWithoutClassifyingAllMediaTekDevices() {
        assertTrue(
            isDimensityDevice(
                socManufacturer = "MediaTek",
                socModel = "MT6985",
                hardware = null,
                board = null
            )
        )
        assertTrue(
            isDimensityDevice(
                socManufacturer = "MediaTek",
                socModel = "Dimensity 9300",
                hardware = null,
                board = null
            )
        )
        assertFalse(
            isDimensityDevice(
                socManufacturer = "MediaTek",
                socModel = "MT6765",
                hardware = null,
                board = null
            )
        )
    }
}
