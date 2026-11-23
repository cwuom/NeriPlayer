package moe.ouom.neriplayer.data.settings.appearance

import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedBlurQualityTest {
    @Test
    fun missingPreferenceUsesUltraLowOnlyForDimensityDevices() {
        assertEquals(
            AdvancedBlurQuality.UltraLow,
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
    fun savedQualityRemainsUnchangedWhenDimensityIdentificationChanges() {
        listOf(false, true).forEach { isDimensityDevice ->
            listOf(AdvancedBlurQuality.Default, AdvancedBlurQuality.High).forEach { quality ->
                assertEquals(
                    "Saved $quality must survive device classification=$isDimensityDevice",
                    quality,
                    AdvancedBlurQualityPreference.resolve(
                        value = quality.storageValue,
                        isDimensityDevice = isDimensityDevice
                    )
                )
            }
        }
    }

    @Test
    fun highQualitySelectionRequiresEnhancedAdvancedBlur() {
        assertFalse(AdvancedBlurQuality.High.canBeSelectedWhen(false))
        assertTrue(AdvancedBlurQuality.High.canBeSelectedWhen(true))
        assertTrue(AdvancedBlurQuality.Low.canBeSelectedWhen(false))
        assertTrue(AdvancedBlurQuality.Default.canBeSelectedWhen(false))
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
        assertTrue(
            isDimensityDevice(
                socManufacturer = "MediaTek",
                socModel = "d9000",
                hardware = null,
                board = null
            )
        )
        assertTrue(
            isDimensityDevice(
                socManufacturer = "MediaTek",
                socModel = null,
                hardware = "d8000",
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
        assertFalse(
            isDimensityDevice(
                socManufacturer = "Qualcomm",
                socModel = "d9000",
                hardware = null,
                board = null
            )
        )
        assertFalse(
            isDimensityDevice(
                socManufacturer = "MediaTek",
                socModel = "9000",
                hardware = null,
                board = null
            )
        )
    }

    @Test
    fun detectsLegacyDimensityNamesAndMediaTekShorthand() {
        listOf("700", "900", "1000", "1000C").forEach { model ->
            assertTrue(
                "Expected the Dimensity $model marketing name to be recognized",
                isDimensityDevice(
                    socManufacturer = null,
                    socModel = "Dimensity $model",
                    hardware = null,
                    board = null
                )
            )
            assertTrue(
                "Expected d$model with MediaTek context to be recognized",
                isDimensityDevice(
                    socManufacturer = "MediaTek",
                    socModel = "d$model",
                    hardware = null,
                    board = null
                )
            )
        }
    }

    @Test
    fun detectsDimensityShorthandWithALetterSuffix() {
        assertTrue(
            isDimensityDevice(
                socManufacturer = "MediaTek",
                socModel = "d9500s",
                hardware = null,
                board = null
            )
        )
    }

    @Test
    fun detectsMediaTekPrefixedDimensityModel() {
        assertTrue(
            isDimensityDevice(
                socManufacturer = null,
                socModel = "MTK6895",
                hardware = null,
                board = null
            )
        )
    }

    @Test
    fun detectsDimensityBoardIdentifierOnlyWithMediaTekContext() {
        assertTrue(
            isDimensityDevice(
                socManufacturer = "MediaTek",
                socModel = null,
                hardware = null,
                board = "k6895v1_64"
            )
        )
        assertFalse(
            isDimensityDevice(
                socManufacturer = null,
                socModel = null,
                hardware = null,
                board = "k6895v1_64"
            )
        )
    }

    @Test
    fun incompleteModelPrefixesAndEmbeddedNamesAreNotDimensityEvidence() {
        listOf("mt68", "mt69", "mt683", "notdimensity").forEach { identifier ->
            assertFalse(
                "Incomplete or embedded identifier must not match: $identifier",
                isDimensityDevice(
                    socManufacturer = "MediaTek",
                    socModel = identifier,
                    hardware = null,
                    board = null
                )
            )
        }
    }

    @Test
    fun missingOrUnknownIdentifiersAreNotDimensityEvidence() {
        listOf(null, "", " ", "unknown", "UNKNOWN").forEach { identifier ->
            assertFalse(
                "Unknown identifier must not match: $identifier",
                isDimensityDevice(
                    socManufacturer = "MediaTek",
                    socModel = identifier,
                    hardware = identifier,
                    board = identifier
                )
            )
        }
        assertFalse(isDimensityDevice(null, null, null, null))
    }

    @Test
    fun shorthandWithoutMediaTekContextIsNotDimensityEvidence() {
        listOf("d700", "d900", "d1000", "d1000c", "d9500s").forEach { model ->
            assertFalse(
                "Shorthand without MediaTek context must not match: $model",
                isDimensityDevice(
                    socManufacturer = "Qualcomm",
                    socModel = model,
                    hardware = null,
                    board = null
                )
            )
        }
    }

    @Test
    fun newlyRecognizedDimensityModelsDoNotExpandLensCompatibility() {
        listOf("d700", "d900", "d1000", "d1000c", "d9500s", "MTK6895").forEach { model ->
            assertFalse(
                "New identity evidence must not enable blur compatibility: $model",
                usesDimensityBlurCompatibility(
                    socManufacturer = "MediaTek",
                    socModel = model,
                    hardware = null,
                    board = null
                )
            )
        }
        assertFalse(
            usesDimensityBlurCompatibility(
                socManufacturer = "MediaTek",
                socModel = null,
                hardware = null,
                board = "k6895v1_64"
            )
        )
    }

    @Test
    fun blurCompatibilityRetainsExistingDimensityMatches() {
        listOf("MT6985", "Dimensity 9300", "d9000").forEach { model ->
            assertTrue(
                "Existing blur compatibility must remain enabled: $model",
                usesDimensityBlurCompatibility(
                    socManufacturer = "MediaTek",
                    socModel = model,
                    hardware = null,
                    board = null
                )
            )
        }
        assertTrue(
            usesDimensityBlurCompatibility(
                socManufacturer = null,
                socModel = null,
                hardware = "MT6985",
                board = null
            )
        )
        assertTrue(
            usesDimensityBlurCompatibility(
                socManufacturer = "MediaTek",
                socModel = null,
                hardware = "d8000",
                board = null
            )
        )
    }

    @Test
    fun stricterIdentityMatchingDoesNotChangeLegacyBlurCompatibility() {
        listOf("mt68", "mt69", "mt683", "notdimensity").forEach { identifier ->
            assertTrue(
                "Legacy blur compatibility must retain its existing match: $identifier",
                usesDimensityBlurCompatibility(
                    socManufacturer = "MediaTek",
                    socModel = identifier,
                    hardware = null,
                    board = null
                )
            )
        }
    }

    @Test
    fun blurCompatibilityRetainsExistingExclusions() {
        listOf(null, "unknown", "MT6765", "9000").forEach { model ->
            assertFalse(
                "Existing blur compatibility must remain disabled: $model",
                usesDimensityBlurCompatibility(
                    socManufacturer = "MediaTek",
                    socModel = model,
                    hardware = null,
                    board = null
                )
            )
        }
        assertFalse(
            usesDimensityBlurCompatibility(
                socManufacturer = "Qualcomm",
                socModel = "d9000",
                hardware = null,
                board = null
            )
        )
    }

    @Test
    fun identifiedDimensityDevicesUseUltraLowByDefaultAndPreserveExplicitQuality() {
        listOf<Pair<String?, String?>>(
            "d700" to null,
            "d900" to null,
            "d1000" to null,
            "d1000c" to null,
            "d9500s" to null,
            "MTK6895" to null,
            "MT6985" to null,
            "d9000" to null,
            null to "k6895v1_64"
        ).forEach { (model, board) ->
            val dimensityDevice = isDimensityDevice(
                socManufacturer = "MediaTek",
                socModel = model,
                hardware = null,
                board = board
            )
            assertTrue("Expected Dimensity identity: model=$model board=$board", dimensityDevice)
            assertEquals(
                AdvancedBlurQuality.UltraLow,
                AdvancedBlurQualityPreference.defaultForDevice(dimensityDevice)
            )
            assertEquals(
                "Missing quality preference must default to UltraLow: model=$model board=$board",
                AdvancedBlurQuality.UltraLow,
                AdvancedBlurQualityPreference.resolve(
                    value = null,
                    isDimensityDevice = dimensityDevice
                )
            )
            AdvancedBlurQuality.entries.forEach { quality ->
                assertEquals(
                    "Saved $quality must remain selected: model=$model board=$board",
                    quality,
                    AdvancedBlurQualityPreference.resolve(
                        value = quality.storageValue,
                        isDimensityDevice = dimensityDevice
                    )
                )
            }
        }
    }

    @Test
    fun missingPreferenceUsesUltraLowForEntryLevelDevicesToo() {
        assertEquals(
            AdvancedBlurQuality.UltraLow,
            AdvancedBlurQualityPreference.resolve(value = null, isDimensityDevice = false, isEntryLevelDevice = true)
        )
        assertEquals(
            AdvancedBlurQuality.High,
            AdvancedBlurQualityPreference.resolve(value = "high", isDimensityDevice = false, isEntryLevelDevice = true)
        )
    }

    @Test
    fun entryLevelDetectionCoversMemoryCoresAndLowEndSocs() {
        fun entry(
            socManufacturer: String? = "QTI",
            socModel: String? = "SM8550",
            hardware: String? = "qcom",
            lowRam: Boolean = false,
            totalMemoryBytes: Long = 12L * GiB,
            cores: Int = 8
        ) = isEntryLevelBlurDevice(socManufacturer, socModel, hardware, null, lowRam, totalMemoryBytes, cores)

        assertFalse("旗舰与未知内存不能误判", entry())
        assertFalse(entry(totalMemoryBytes = 0L))
        assertTrue(entry(lowRam = true))
        assertTrue("标称 4GB 的实际可用内存", entry(totalMemoryBytes = 3_800_000_000L))
        assertFalse("6GB 不算入门", entry(totalMemoryBytes = 5_600_000_000L))
        assertTrue(entry(cores = 4))
        assertTrue("Helio G99", entry(socManufacturer = "Mediatek", socModel = "MT6789V/CD", hardware = "mt6789"))
        assertTrue(entry(socManufacturer = null, socModel = null, hardware = "Helio G85"))
        assertTrue("Unisoc T606", entry(socManufacturer = "Unisoc", socModel = "T606", hardware = "ums512"))
        assertTrue("Snapdragon 680", entry(socModel = "SM6225"))
        assertTrue("Snapdragon 4 Gen 2", entry(socModel = "SM4450"))
        assertFalse("Snapdragon 695", entry(socModel = "SM6375"))
        assertFalse("Dimensity 走天玑规则", entry(socManufacturer = "Mediatek", socModel = "MT6989", hardware = "mt6989"))
    }

    private companion object {
        const val GiB = 1L shl 30
    }
}
