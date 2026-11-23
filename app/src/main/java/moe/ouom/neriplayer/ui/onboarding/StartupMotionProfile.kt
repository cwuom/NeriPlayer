package moe.ouom.neriplayer.ui.onboarding

import java.util.Locale
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.data.settings.appearance.AdvancedBlurQualityPreference

internal enum class StartupSocClass {
    Flagship,
    Upper,
    Unknown
}

internal data class StartupSocIdentity(
    val label: String?,
    val socClass: StartupSocClass
)

/** 只在本机读取, 用于动效推荐, 不参与任何上传 */
internal data class StartupDeviceSpec(
    val displayName: String,
    val modelCode: String,
    val socLabel: String?,
    val socClass: StartupSocClass,
    val sdkInt: Int,
    val ramGb: Int,
    val cpuCores: Int,
    val maxRefreshRate: Int,
    val lowRamDevice: Boolean,
    val powerSaveMode: Boolean,
    val dimensityDevice: Boolean,
    val dimensityBlurCompatibility: Boolean
)

internal enum class StartupDeviceTier {
    Flagship,
    Mainstream,
    Entry
}

internal enum class StartupMotionPreset {
    Immersive,
    Balanced,
    Light
}

internal data class StartupMotionConfig(
    val advancedBlur: Boolean,
    val enhancedAdvancedBlur: Boolean,
    val advancedBlurQuality: AdvancedBlurQuality,
    val liquidGlass: Boolean,
    val enhancedVisualEffects: Boolean,
    val nowPlayingDynamicBackground: Boolean,
    val nowPlayingAudioReactive: Boolean,
    val lyricBlur: Boolean,
    val preferHighRefreshRate: Boolean
)

internal data class StartupMotionCapabilities(
    val advancedBlurSupported: Boolean,
    val highRefreshAvailable: Boolean
)

internal data class StartupMotionRecommendation(
    val tier: StartupDeviceTier,
    val preset: StartupMotionPreset,
    val reason: StartupMotionReason
)

internal enum class StartupMotionReason {
    FlagshipHardware,
    MainstreamHardware,
    DimensityBlurPolicy,
    EntryHardware,
    PowerSaveMode
}

// 与 AdvancedGlassController 的后端门槛保持一致, 这里不依赖 UI 层常量
internal const val STARTUP_ADVANCED_BLUR_MIN_SDK = 33
private const val STARTUP_HIGH_REFRESH_THRESHOLD_HZ = 61
private const val STARTUP_ENTRY_MIN_SDK = 29

private val StartupMarketingRamSizesGb = intArrayOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 20, 24, 32)

/** totalMem 会扣掉内核与固件预留, 需要向上取到常见的标称容量 */
internal fun roundStartupRamGb(totalBytes: Long): Int {
    if (totalBytes <= 0L) return 0
    val gib = totalBytes.toDouble() / (1L shl 30)
    return StartupMarketingRamSizesGb.firstOrNull { size -> gib <= size + 0.05 }
        ?: kotlin.math.ceil(gib).toInt()
}

internal fun roundStartupRefreshRate(refreshRate: Float): Int {
    if (refreshRate.isNaN() || refreshRate <= 0f) return 60
    return kotlin.math.round(refreshRate).toInt()
}

private data class StartupKnownSoc(val label: String, val socClass: StartupSocClass)

private val StartupKnownSocs = mapOf(
    "sm8850" to StartupKnownSoc("Snapdragon 8 Elite Gen 5", StartupSocClass.Flagship),
    "sm8750" to StartupKnownSoc("Snapdragon 8 Elite", StartupSocClass.Flagship),
    "sm8735" to StartupKnownSoc("Snapdragon 8s Gen 4", StartupSocClass.Upper),
    "sm8650" to StartupKnownSoc("Snapdragon 8 Gen 3", StartupSocClass.Flagship),
    "sm8635" to StartupKnownSoc("Snapdragon 8s Gen 3", StartupSocClass.Upper),
    "sm8550" to StartupKnownSoc("Snapdragon 8 Gen 2", StartupSocClass.Flagship),
    "sm8475" to StartupKnownSoc("Snapdragon 8+ Gen 1", StartupSocClass.Flagship),
    "sm8450" to StartupKnownSoc("Snapdragon 8 Gen 1", StartupSocClass.Upper),
    "sm7675" to StartupKnownSoc("Snapdragon 7+ Gen 3", StartupSocClass.Upper),
    "sm7550" to StartupKnownSoc("Snapdragon 7 Gen 3", StartupSocClass.Upper),
    "mt6993" to StartupKnownSoc("Dimensity 9500", StartupSocClass.Flagship),
    "mt6991" to StartupKnownSoc("Dimensity 9400", StartupSocClass.Flagship),
    "mt6989" to StartupKnownSoc("Dimensity 9300", StartupSocClass.Flagship),
    "mt6985" to StartupKnownSoc("Dimensity 9200", StartupSocClass.Flagship),
    "mt6899" to StartupKnownSoc("Dimensity 8400", StartupSocClass.Upper),
    "mt6897" to StartupKnownSoc("Dimensity 8300", StartupSocClass.Upper),
    "s5e9955" to StartupKnownSoc("Exynos 2500", StartupSocClass.Flagship),
    "s5e9945" to StartupKnownSoc("Exynos 2400", StartupSocClass.Flagship),
    "tensor g5" to StartupKnownSoc("Tensor G5", StartupSocClass.Flagship),
    "tensor g4" to StartupKnownSoc("Tensor G4", StartupSocClass.Flagship),
    "tensor g3" to StartupKnownSoc("Tensor G3", StartupSocClass.Upper)
)

internal fun identifyStartupSoc(
    socManufacturer: String?,
    socModel: String?,
    hardware: String?
): StartupSocIdentity {
    val candidates = listOfNotNull(socModel, hardware)
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
    candidates.forEach { candidate ->
        StartupKnownSocs.entries.firstOrNull { (code, _) -> candidate.startsWith(code) }
            ?.let { (_, soc) -> return StartupSocIdentity(soc.label, soc.socClass) }
    }
    val rawModel = socModel?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", true) }
    val rawManufacturer = socManufacturer?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("unknown", true) }
    val label = when {
        rawModel != null && rawManufacturer != null -> "$rawManufacturer $rawModel"
        rawModel != null -> rawModel
        else -> hardware?.trim()?.takeIf { it.isNotEmpty() }
    }
    return StartupSocIdentity(label, StartupSocClass.Unknown)
}

internal fun classifyStartupDeviceTier(spec: StartupDeviceSpec): StartupDeviceTier {
    if (
        spec.lowRamDevice ||
        spec.ramGb in 1..4 ||
        spec.sdkInt < STARTUP_ENTRY_MIN_SDK ||
        spec.cpuCores in 1..4
    ) {
        return StartupDeviceTier.Entry
    }
    val modernPlatform = spec.sdkInt >= STARTUP_ADVANCED_BLUR_MIN_SDK
    val flagshipSoc = spec.socClass == StartupSocClass.Flagship && spec.ramGb >= 8
    // 未知芯片只在内存和高刷都足够宽裕时才视作旗舰, 避免把堆内存的中端机推到最重档位
    val unknownButRoomy = spec.socClass == StartupSocClass.Unknown &&
        spec.ramGb >= 16 && spec.maxRefreshRate >= 120
    return if (modernPlatform && (flagshipSoc || unknownButRoomy)) {
        StartupDeviceTier.Flagship
    } else {
        StartupDeviceTier.Mainstream
    }
}

internal fun recommendStartupMotion(spec: StartupDeviceSpec): StartupMotionRecommendation {
    val tier = classifyStartupDeviceTier(spec)
    return when {
        spec.powerSaveMode -> StartupMotionRecommendation(
            tier, StartupMotionPreset.Light, StartupMotionReason.PowerSaveMode
        )
        tier == StartupDeviceTier.Entry -> StartupMotionRecommendation(
            tier, StartupMotionPreset.Light, StartupMotionReason.EntryHardware
        )
        tier == StartupDeviceTier.Flagship && spec.dimensityBlurCompatibility ->
            StartupMotionRecommendation(
                tier, StartupMotionPreset.Balanced, StartupMotionReason.DimensityBlurPolicy
            )
        tier == StartupDeviceTier.Flagship -> StartupMotionRecommendation(
            tier, StartupMotionPreset.Immersive, StartupMotionReason.FlagshipHardware
        )
        else -> StartupMotionRecommendation(
            tier, StartupMotionPreset.Balanced, StartupMotionReason.MainstreamHardware
        )
    }
}

internal fun startupMotionCapabilities(spec: StartupDeviceSpec): StartupMotionCapabilities =
    StartupMotionCapabilities(
        advancedBlurSupported = spec.sdkInt >= STARTUP_ADVANCED_BLUR_MIN_SDK,
        highRefreshAvailable = spec.maxRefreshRate > STARTUP_HIGH_REFRESH_THRESHOLD_HZ
    )

internal fun startupMotionConfigFor(
    preset: StartupMotionPreset,
    spec: StartupDeviceSpec
): StartupMotionConfig {
    val blurSupported = spec.sdkInt >= STARTUP_ADVANCED_BLUR_MIN_SDK
    val deviceDefaultQuality = AdvancedBlurQualityPreference.defaultForDevice(spec.dimensityDevice)
    return when (preset) {
        StartupMotionPreset.Immersive -> StartupMotionConfig(
            advancedBlur = blurSupported,
            enhancedAdvancedBlur = blurSupported,
            // 天玑上模糊开销偏高, 不管哪个档位开了模糊都只给超低画质; High 只能在进阶模糊开启时选择
            advancedBlurQuality = when {
                spec.dimensityDevice -> AdvancedBlurQuality.UltraLow
                blurSupported -> AdvancedBlurQuality.High
                else -> deviceDefaultQuality
            },
            // 两套天玑识别规则覆盖的型号不同, 任一命中都按天玑处理, 超低画质下不开透镜
            liquidGlass = blurSupported && !spec.dimensityDevice && !spec.dimensityBlurCompatibility,
            enhancedVisualEffects = true,
            nowPlayingDynamicBackground = true,
            nowPlayingAudioReactive = true,
            lyricBlur = true,
            preferHighRefreshRate = true
        )
        StartupMotionPreset.Balanced -> StartupMotionConfig(
            // 均衡档流畅优先, 实时模糊和依赖它的透镜都交给沉浸档
            advancedBlur = false,
            enhancedAdvancedBlur = false,
            advancedBlurQuality = deviceDefaultQuality,
            liquidGlass = false,
            enhancedVisualEffects = true,
            nowPlayingDynamicBackground = true,
            // 均衡档与出厂默认对齐, 老用户回到这一步也能匹配上
            nowPlayingAudioReactive = true,
            lyricBlur = true,
            preferHighRefreshRate = true
        )
        StartupMotionPreset.Light -> StartupMotionConfig(
            advancedBlur = false,
            enhancedAdvancedBlur = false,
            advancedBlurQuality = deviceDefaultQuality,
            liquidGlass = false,
            enhancedVisualEffects = false,
            nowPlayingDynamicBackground = false,
            nowPlayingAudioReactive = false,
            lyricBlur = false,
            preferHighRefreshRate = false
        )
    }
}

/**
 * 回到这一步时用当前设置反推档位, 都对不上就显示为自定义
 * 旧系统裁掉不支持的效果后几个档位可能是同一份配置, 这时以用户点选的档位为准
 */
internal fun matchStartupMotionPreset(
    config: StartupMotionConfig,
    spec: StartupDeviceSpec,
    preferred: StartupMotionPreset? = null
): StartupMotionPreset? = preferred?.takeIf { startupMotionConfigFor(it, spec) == config }
    ?: StartupMotionPreset.entries.firstOrNull { preset -> startupMotionConfigFor(preset, spec) == config }

internal fun StartupMotionConfig.withEnhancedAdvancedBlur(enabled: Boolean): StartupMotionConfig {
    val quality = if (!enabled && advancedBlurQuality == AdvancedBlurQuality.High) {
        AdvancedBlurQuality.Default
    } else {
        advancedBlurQuality
    }
    return copy(enhancedAdvancedBlur = enabled, advancedBlurQuality = quality)
}
