package moe.ouom.neriplayer.ui.onboarding

import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupMotionProfileTest {

    private fun spec(
        socClass: StartupSocClass = StartupSocClass.Unknown,
        sdkInt: Int = 35,
        ramGb: Int = 8,
        cpuCores: Int = 8,
        maxRefreshRate: Int = 120,
        lowRamDevice: Boolean = false,
        powerSaveMode: Boolean = false,
        dimensity: Boolean = false,
        entryLevelBlur: Boolean = false
    ) = StartupDeviceSpec(
        displayName = "Test Phone",
        modelCode = "TP-1",
        socLabel = null,
        socClass = socClass,
        sdkInt = sdkInt,
        ramGb = ramGb,
        cpuCores = cpuCores,
        maxRefreshRate = maxRefreshRate,
        lowRamDevice = lowRamDevice,
        powerSaveMode = powerSaveMode,
        dimensityDevice = dimensity,
        dimensityBlurCompatibility = dimensity,
        entryLevelBlurDevice = entryLevelBlur
    )

    @Test
    fun `entry level gpus keep the lightweight blur quality outside the immersive preset`() {
        val entry = spec(entryLevelBlur = true)
        assertEquals(
            AdvancedBlurQuality.UltraLow,
            startupMotionConfigFor(StartupMotionPreset.Balanced, entry).advancedBlurQuality
        )
        assertEquals(
            AdvancedBlurQuality.UltraLow,
            startupMotionConfigFor(StartupMotionPreset.Light, entry).advancedBlurQuality
        )
        assertEquals(
            AdvancedBlurQuality.Default,
            startupMotionConfigFor(StartupMotionPreset.Balanced, spec()).advancedBlurQuality
        )
    }

    @Test
    fun `total memory rounds up to the marketed capacity`() {
        val gib = 1L shl 30
        assertEquals(12, roundStartupRamGb((11.2 * gib).toLong()))
        assertEquals(8, roundStartupRamGb((7.4 * gib).toLong()))
        assertEquals(4, roundStartupRamGb((3.6 * gib).toLong()))
        assertEquals(16, roundStartupRamGb((15.3 * gib).toLong()))
        assertEquals(0, roundStartupRamGb(0L))
    }

    @Test
    fun `refresh rate rounds to whole hertz and falls back to 60`() {
        assertEquals(120, roundStartupRefreshRate(119.88f))
        assertEquals(60, roundStartupRefreshRate(Float.NaN))
        assertEquals(60, roundStartupRefreshRate(0f))
    }

    @Test
    fun `known chipsets get a friendly name and class`() {
        val snapdragon = identifyStartupSoc("QTI", "SM8650", "qcom")
        assertEquals("Snapdragon 8 Gen 3", snapdragon.label)
        assertEquals(StartupSocClass.Flagship, snapdragon.socClass)

        val dimensity = identifyStartupSoc("Mediatek", "MT6897", "mt6897")
        assertEquals(StartupSocClass.Upper, dimensity.socClass)

        val unknown = identifyStartupSoc("Google", "Unknown", "ranchu")
        assertEquals("ranchu", unknown.label)
        assertEquals(StartupSocClass.Unknown, unknown.socClass)
    }

    @Test
    fun `flagship hardware on a modern platform gets the immersive preset`() {
        val recommendation = recommendStartupMotion(spec(socClass = StartupSocClass.Flagship, ramGb = 12))
        assertEquals(StartupDeviceTier.Flagship, recommendation.tier)
        assertEquals(StartupMotionPreset.Immersive, recommendation.preset)
        assertEquals(StartupMotionReason.FlagshipHardware, recommendation.reason)
    }

    @Test
    fun `unknown chipsets need generous memory and refresh rate to count as flagship`() {
        assertEquals(StartupDeviceTier.Mainstream, classifyStartupDeviceTier(spec(ramGb = 12)))
        assertEquals(
            StartupDeviceTier.Flagship,
            classifyStartupDeviceTier(spec(ramGb = 16, maxRefreshRate = 120))
        )
        assertEquals(
            StartupDeviceTier.Mainstream,
            classifyStartupDeviceTier(spec(ramGb = 16, maxRefreshRate = 90))
        )
    }

    @Test
    fun `dimensity flagships are capped at balanced because of the blur policy`() {
        val recommendation = recommendStartupMotion(
            spec(socClass = StartupSocClass.Flagship, ramGb = 16, dimensity = true)
        )
        assertEquals(StartupDeviceTier.Flagship, recommendation.tier)
        assertEquals(StartupMotionPreset.Balanced, recommendation.preset)
        assertEquals(StartupMotionReason.DimensityBlurPolicy, recommendation.reason)
    }

    @Test
    fun `entry devices and battery saver get the light preset`() {
        assertEquals(StartupMotionPreset.Light, recommendStartupMotion(spec(ramGb = 4)).preset)
        assertEquals(StartupMotionPreset.Light, recommendStartupMotion(spec(lowRamDevice = true)).preset)
        assertEquals(StartupMotionPreset.Light, recommendStartupMotion(spec(sdkInt = 28)).preset)
        val saver = recommendStartupMotion(spec(socClass = StartupSocClass.Flagship, ramGb = 16, powerSaveMode = true))
        assertEquals(StartupMotionPreset.Light, saver.preset)
        assertEquals(StartupMotionReason.PowerSaveMode, saver.reason)
    }

    @Test
    fun `immersive enables the high quality glass only where the backend exists`() {
        val modern = startupMotionConfigFor(StartupMotionPreset.Immersive, spec(sdkInt = 35))
        assertTrue(modern.advancedBlur)
        assertTrue(modern.enhancedAdvancedBlur)
        assertEquals(AdvancedBlurQuality.High, modern.advancedBlurQuality)
        assertTrue(modern.liquidGlass)

        val legacy = startupMotionConfigFor(StartupMotionPreset.Immersive, spec(sdkInt = 31))
        assertFalse(legacy.advancedBlur)
        assertFalse(legacy.enhancedAdvancedBlur)
        assertFalse(legacy.liquidGlass)
        assertEquals(AdvancedBlurQuality.Default, legacy.advancedBlurQuality)
    }

    @Test
    fun `balanced turns advanced blur and the lens off`() {
        listOf(spec(), spec(dimensity = true), spec(socClass = StartupSocClass.Flagship, ramGb = 16)).forEach { device ->
            val config = startupMotionConfigFor(StartupMotionPreset.Balanced, device)
            assertFalse(config.advancedBlur)
            assertFalse(config.enhancedAdvancedBlur)
            assertFalse(config.liquidGlass)
        }
        val dimensity = startupMotionConfigFor(StartupMotionPreset.Balanced, spec(dimensity = true))
        assertEquals(AdvancedBlurQuality.UltraLow, dimensity.advancedBlurQuality)
    }

    @Test
    fun `dimensity always gets ultra low blur quality whatever the preset`() {
        val device = spec(socClass = StartupSocClass.Flagship, ramGb = 16, dimensity = true)
        StartupMotionPreset.entries.forEach { preset ->
            assertEquals(preset.name, AdvancedBlurQuality.UltraLow, startupMotionConfigFor(preset, device).advancedBlurQuality)
        }
        val immersive = startupMotionConfigFor(StartupMotionPreset.Immersive, device)
        assertTrue(immersive.advancedBlur)
        assertFalse(immersive.liquidGlass)
    }

    @Test
    fun `immersive keeps the lens off when only the newer dimensity rule matches`() {
        // MTK6893 这类型号只被新规则识别, 旧的兼容策略识别不到
        val device = spec(socClass = StartupSocClass.Flagship, ramGb = 16)
            .copy(dimensityDevice = true, dimensityBlurCompatibility = false)
        val immersive = startupMotionConfigFor(StartupMotionPreset.Immersive, device)
        assertEquals(AdvancedBlurQuality.UltraLow, immersive.advancedBlurQuality)
        assertFalse(immersive.liquidGlass)
    }

    @Test
    fun `light turns every costly effect off`() {
        val config = startupMotionConfigFor(StartupMotionPreset.Light, spec())
        assertFalse(config.advancedBlur)
        assertFalse(config.liquidGlass)
        assertFalse(config.enhancedVisualEffects)
        assertFalse(config.nowPlayingDynamicBackground)
        assertFalse(config.nowPlayingAudioReactive)
        assertFalse(config.lyricBlur)
        assertFalse(config.preferHighRefreshRate)
    }

    @Test
    fun `stored settings are matched back to a preset or reported as custom`() {
        val device = spec()
        StartupMotionPreset.entries.forEach { preset ->
            assertEquals(preset, matchStartupMotionPreset(startupMotionConfigFor(preset, device), device))
        }
        val custom = startupMotionConfigFor(StartupMotionPreset.Light, device).copy(lyricBlur = true)
        assertNull(matchStartupMotionPreset(custom, device))
    }

    @Test
    fun `ambiguous presets on old systems keep the one the user picked`() {
        val android12 = spec(socClass = StartupSocClass.Flagship, sdkInt = 31)
        val balanced = startupMotionConfigFor(StartupMotionPreset.Balanced, android12)
        assertEquals(balanced, startupMotionConfigFor(StartupMotionPreset.Immersive, android12))

        assertEquals(
            StartupMotionPreset.Balanced,
            matchStartupMotionPreset(balanced, android12, preferred = StartupMotionPreset.Balanced)
        )
        assertEquals(
            StartupMotionPreset.Immersive,
            matchStartupMotionPreset(balanced, android12, preferred = StartupMotionPreset.Immersive)
        )
        val custom = balanced.copy(lyricBlur = false)
        assertNull(matchStartupMotionPreset(custom, android12, preferred = StartupMotionPreset.Balanced))
    }

    @Test
    fun `turning enhanced blur off drops the high quality that depends on it`() {
        val immersive = startupMotionConfigFor(StartupMotionPreset.Immersive, spec())
        val reduced = immersive.withEnhancedAdvancedBlur(false)
        assertFalse(reduced.enhancedAdvancedBlur)
        assertEquals(AdvancedBlurQuality.Default, reduced.advancedBlurQuality)
    }

    @Test
    fun `capabilities follow the platform and the panel`() {
        assertTrue(startupMotionCapabilities(spec(sdkInt = 33)).advancedBlurSupported)
        assertFalse(startupMotionCapabilities(spec(sdkInt = 32)).advancedBlurSupported)
        assertFalse(startupMotionCapabilities(spec(maxRefreshRate = 60)).highRefreshAvailable)
        assertTrue(startupMotionCapabilities(spec(maxRefreshRate = 90)).highRefreshAvailable)
    }
}
