package moe.ouom.neriplayer.data.settings

import android.os.Build
import java.util.Locale

enum class AdvancedBlurQuality(
    val storageValue: String
) {
    UltraLow("ultra_low"),
    Low("low"),
    Default("default"),
    High("high")
}

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
            "dimensity" in identifier || DimensityModelPattern.containsMatchIn(identifier)
        }
    ) {
        return true
    }
    val hasMediaTekContext = identifiers.any { identifier ->
        "mediatek" in identifier
    }
    return hasMediaTekContext && identifiers.any { identifier ->
        DimensityShorthandPattern.containsMatchIn(identifier)
    }
}

fun isCurrentBuildDimensity(): Boolean = isDimensityDevice(
    socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MANUFACTURER
    } else {
        null
    },
    socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL
    } else {
        null
    },
    hardware = Build.HARDWARE,
    board = Build.BOARD
)

private val DimensityModelPattern = Regex("(?:^|[^a-z0-9])mt(?:68|69)[0-9a-z]*")
private val DimensityShorthandPattern = Regex("(?:^|[^a-z0-9])d[6-9][0-9]{3}(?:$|[^a-z0-9])")
