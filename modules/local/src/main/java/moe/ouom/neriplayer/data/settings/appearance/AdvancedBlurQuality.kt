package moe.ouom.neriplayer.data.settings.appearance

import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality

import android.os.Build
import java.util.Locale

const val DEFAULT_ADVANCED_BLUR_QUALITY = "default"

object AdvancedBlurQualityPreference {
    fun normalize(value: String): String = fromStorage(value).storageValue

    fun defaultForDevice(isDimensityDevice: Boolean): AdvancedBlurQuality = if (
        isDimensityDevice
    ) {
        AdvancedBlurQuality.UltraLow
    } else {
        AdvancedBlurQuality.Default
    }

    fun resolve(
        value: String?,
        isDimensityDevice: Boolean
    ): AdvancedBlurQuality {
        return value?.let(::fromStorage)
            ?: defaultForDevice(isDimensityDevice)
    }

    fun fromStorage(value: String): AdvancedBlurQuality = when (
        value.trim().lowercase(Locale.ROOT)
    ) {
        AdvancedBlurQuality.UltraLow.storageValue -> AdvancedBlurQuality.UltraLow
        AdvancedBlurQuality.Low.storageValue -> AdvancedBlurQuality.Low
        AdvancedBlurQuality.High.storageValue -> AdvancedBlurQuality.High
        else -> AdvancedBlurQuality.Default
    }
}

fun AdvancedBlurQuality.canBeSelectedWhen(
    enhancedAdvancedBlurEnabled: Boolean
): Boolean = this != AdvancedBlurQuality.High || enhancedAdvancedBlurEnabled

fun isDimensityDevice(
    socManufacturer: String?,
    socModel: String?,
    hardware: String?,
    board: String?
): Boolean {
    val identifiers = listOfNotNull(socManufacturer, socModel, hardware, board).map { identifier ->
        identifier.lowercase(Locale.ROOT)
    }
    if (identifiers.any { identifier ->
            DimensityNamePattern.containsMatchIn(identifier) ||
                DimensityModelPattern.containsMatchIn(identifier)
        }
    ) {
        return true
    }
    val hasMediaTekContext = identifiers.any(MediaTekContextPattern::containsMatchIn)
    return hasMediaTekContext && identifiers.any { identifier ->
        DimensityShorthandPattern.containsMatchIn(identifier) ||
            DimensityBoardPattern.containsMatchIn(identifier)
    }
}

// 保留既有透镜兼容策略，避免扩充芯片身份识别后额外关闭透镜
fun usesDimensityBlurCompatibility(
    socManufacturer: String?,
    socModel: String?,
    hardware: String?,
    board: String?
): Boolean {
    val identifiers = listOfNotNull(socManufacturer, socModel, hardware, board).map { identifier ->
        identifier.lowercase(Locale.ROOT)
    }
    if (identifiers.any { identifier ->
            "dimensity" in identifier || LegacyDimensityModelPattern.containsMatchIn(identifier)
        }
    ) {
        return true
    }
    val hasMediaTekContext = identifiers.any { identifier ->
        "mediatek" in identifier
    }
    return hasMediaTekContext && identifiers.any { identifier ->
        LegacyDimensityShorthandPattern.containsMatchIn(identifier)
    }
}

fun isCurrentBuildDimensity(): Boolean = currentBuildMatches(::isDimensityDevice)

fun currentBuildUsesDimensityBlurCompatibility(): Boolean =
    currentBuildMatches(::usesDimensityBlurCompatibility)

private inline fun currentBuildMatches(
    predicate: (String?, String?, String?, String?) -> Boolean
): Boolean = predicate(
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MANUFACTURER
    } else {
        null
    },
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL
    } else {
        null
    },
    Build.HARDWARE,
    Build.BOARD
)

private val DimensityNamePattern =
    Regex("(?:^|[^a-z0-9])dimensity(?:[0-9]{3,4}[a-z]*)?(?:$|[^a-z0-9])")
private val DimensityModelPattern =
    Regex("(?:^|[^a-z0-9])mtk?(?:68|69)[0-9]{2}(?:[a-z][a-z0-9]*)?(?:$|[^a-z0-9])")
private val MediaTekContextPattern = Regex("(?:^|[^a-z0-9])(?:mediatek|mtk)(?:$|[^a-z0-9])")
private val DimensityShorthandPattern =
    Regex("(?:^|[^a-z0-9])d(?:[7-9][0-9]{2}|1[0-9]{3}|[6-9][0-9]{3})(?:[a-z][a-z0-9]*)?(?:$|[^a-z0-9])")
private val DimensityBoardPattern =
    Regex("(?:^|[^a-z0-9])k(?:68|69)[0-9]{2}(?:[a-z][a-z0-9]*)?(?:$|[^a-z0-9])")
private val LegacyDimensityModelPattern = Regex("(?:^|[^a-z0-9])mt(?:68|69)[0-9a-z]*")
private val LegacyDimensityShorthandPattern = Regex("(?:^|[^a-z0-9])d[6-9][0-9]{3}(?:$|[^a-z0-9])")
