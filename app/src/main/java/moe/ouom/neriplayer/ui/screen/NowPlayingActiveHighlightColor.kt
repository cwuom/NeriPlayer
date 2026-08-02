package moe.ouom.neriplayer.ui.screen

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.toColorInt
import moe.ouom.neriplayer.data.settings.ThemeDefaults
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

private const val NowPlayingActiveIconMinSaturation = 0.32f
private const val NowPlayingActiveIconBoostedMinSaturation = 0.52f
private const val NowPlayingActiveIconMissingHueSaturation = 0.04f
private const val NowPlayingActiveIconMinBackgroundContrast = 3.0
private const val NowPlayingActiveIconMinColorDistance = 0.25f
private const val NowPlayingActiveIconMinLuminanceGap = 0.14
private const val NowPlayingDarkBackgroundLuminanceThreshold = 0.5
private val NowPlayingActiveIconDarkFallback = Color(0xFF8FD8FF)
private val NowPlayingActiveIconLightFallback = Color(0xFF0068B5)

internal fun resolveNowPlayingThemeSeedColor(hex: String): Color {
    return Color(("#${ThemeDefaults.sanitizeSeedColorHex(hex)}").toColorInt())
}

internal fun resolveNowPlayingActiveIconColor(
    accentColor: Color,
    seedColor: Color,
    inactiveContentColor: Color,
    backgroundColor: Color
): Color {
    val safeBackground = backgroundColor.orNowPlayingFallback(Color.Black).copy(alpha = 1f)
    val fallbackContent = if (isNowPlayingDarkBackground(safeBackground)) Color.White else Color.Black
    val safeInactive = inactiveContentColor.orNowPlayingFallback(fallbackContent).copy(alpha = 1f)
    val safeSeed = seedColor.orNowPlayingFallback(NowPlayingActiveIconDarkFallback).copy(alpha = 1f)
    val safeAccent = accentColor.orNowPlayingFallback(safeSeed).copy(alpha = 1f)

    if (isNowPlayingActiveIconReadable(safeAccent, safeInactive, safeBackground)) {
        return safeAccent
    }

    val source = listOf(safeSeed, safeAccent).maxBy { nowPlayingColorSaturation(it) }
    val boosted = boostNowPlayingActiveIconColor(source, safeBackground)
    if (isNowPlayingActiveIconReadable(boosted, safeInactive, safeBackground)) {
        return boosted
    }

    return if (isNowPlayingDarkBackground(safeBackground)) {
        NowPlayingActiveIconDarkFallback
    } else {
        NowPlayingActiveIconLightFallback
    }
}

internal fun isNowPlayingActiveIconReadable(
    activeColor: Color,
    inactiveContentColor: Color,
    backgroundColor: Color
): Boolean {
    val safeBackground = backgroundColor.orNowPlayingFallback(Color.Black).copy(alpha = 1f)
    val safeActive = activeColor.orNowPlayingFallback(Color.Transparent).copy(alpha = 1f)
    val safeInactive = inactiveContentColor.orNowPlayingFallback(Color.White).copy(alpha = 1f)
    val activeArgb = safeActive.toArgb()
    val backgroundArgb = safeBackground.toArgb()
    val saturation = nowPlayingColorSaturation(safeActive)
    val backgroundContrast = nowPlayingContrastRatio(activeArgb, backgroundArgb)
    val colorDistance = nowPlayingRgbDistance(activeArgb, safeInactive.toArgb())
    val luminanceGap = abs(
        nowPlayingRelativeLuminance(activeArgb) -
            nowPlayingRelativeLuminance(safeInactive.toArgb())
    )

    return saturation >= NowPlayingActiveIconMinSaturation &&
        backgroundContrast >= NowPlayingActiveIconMinBackgroundContrast &&
        (
            colorDistance >= NowPlayingActiveIconMinColorDistance ||
                luminanceGap >= NowPlayingActiveIconMinLuminanceGap
            )
}

private fun boostNowPlayingActiveIconColor(
    sourceColor: Color,
    backgroundColor: Color
): Color {
    if (nowPlayingColorSaturation(sourceColor) < NowPlayingActiveIconMissingHueSaturation) {
        return if (isNowPlayingDarkBackground(backgroundColor)) {
            NowPlayingActiveIconDarkFallback
        } else {
            NowPlayingActiveIconLightFallback
        }
    }

    val hsl = nowPlayingColorToHsl(sourceColor)
    hsl[1] = maxOf(hsl[1], NowPlayingActiveIconBoostedMinSaturation)
    hsl[2] = if (isNowPlayingDarkBackground(backgroundColor)) {
        hsl[2].coerceIn(0.58f, 0.74f)
    } else {
        hsl[2].coerceIn(0.28f, 0.44f)
    }
    return nowPlayingHslToColor(hsl)
}

private fun isNowPlayingDarkBackground(color: Color): Boolean {
    return nowPlayingRelativeLuminance(color.toArgb()) < NowPlayingDarkBackgroundLuminanceThreshold
}

private fun nowPlayingColorSaturation(color: Color): Float {
    return nowPlayingColorToHsl(color)[1]
}

private fun nowPlayingColorToHsl(color: Color): FloatArray {
    val argb = color.toArgb()
    val red = ((argb shr 16) and 0xFF) / 255f
    val green = ((argb shr 8) and 0xFF) / 255f
    val blue = (argb and 0xFF) / 255f
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    val lightness = (max + min) / 2f
    val saturation = if (delta == 0f) {
        0f
    } else if (lightness > 0.5f) {
        delta / (2f - max - min)
    } else {
        delta / (max + min)
    }
    val hue = if (delta == 0f) {
        0f
    } else {
        val rawHue = when (max) {
            red -> ((green - blue) / delta) + if (green < blue) 6f else 0f
            green -> ((blue - red) / delta) + 2f
            else -> ((red - green) / delta) + 4f
        }
        rawHue * 60f
    }
    return floatArrayOf(hue, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))
}

private fun nowPlayingHslToColor(hsl: FloatArray): Color {
    val hue = ((hsl[0] % 360f) + 360f) % 360f / 360f
    val saturation = hsl[1].coerceIn(0f, 1f)
    val lightness = hsl[2].coerceIn(0f, 1f)
    if (saturation == 0f) {
        return Color(red = lightness, green = lightness, blue = lightness, alpha = 1f)
    }

    val q = if (lightness < 0.5f) {
        lightness * (1f + saturation)
    } else {
        lightness + saturation - lightness * saturation
    }
    val p = 2f * lightness - q
    return Color(
        red = hueToNowPlayingRgb(p, q, hue + 1f / 3f),
        green = hueToNowPlayingRgb(p, q, hue),
        blue = hueToNowPlayingRgb(p, q, hue - 1f / 3f),
        alpha = 1f
    )
}

private fun hueToNowPlayingRgb(p: Float, q: Float, hue: Float): Float {
    var normalizedHue = hue
    if (normalizedHue < 0f) normalizedHue += 1f
    if (normalizedHue > 1f) normalizedHue -= 1f
    return when {
        normalizedHue < 1f / 6f -> p + (q - p) * 6f * normalizedHue
        normalizedHue < 1f / 2f -> q
        normalizedHue < 2f / 3f -> p + (q - p) * (2f / 3f - normalizedHue) * 6f
        else -> p
    }
}

private fun nowPlayingRgbDistance(firstArgb: Int, secondArgb: Int): Float {
    val red = ((firstArgb shr 16) and 0xFF) - ((secondArgb shr 16) and 0xFF)
    val green = ((firstArgb shr 8) and 0xFF) - ((secondArgb shr 8) and 0xFF)
    val blue = (firstArgb and 0xFF) - (secondArgb and 0xFF)
    val squared = red * red + green * green + blue * blue
    return sqrt(squared.toFloat()) / 441.67295f
}

private fun nowPlayingContrastRatio(firstArgb: Int, secondArgb: Int): Double {
    val firstLuminance = nowPlayingRelativeLuminance(firstArgb)
    val secondLuminance = nowPlayingRelativeLuminance(secondArgb)
    val lighter = maxOf(firstLuminance, secondLuminance)
    val darker = minOf(firstLuminance, secondLuminance)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun nowPlayingRelativeLuminance(argb: Int): Double {
    val red = nowPlayingLinearRgb((argb shr 16) and 0xFF)
    val green = nowPlayingLinearRgb((argb shr 8) and 0xFF)
    val blue = nowPlayingLinearRgb(argb and 0xFF)
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

private fun nowPlayingLinearRgb(channel: Int): Double {
    val normalized = channel / 255.0
    return if (normalized <= 0.03928) {
        normalized / 12.92
    } else {
        ((normalized + 0.055) / 1.055).pow(2.4)
    }
}

private fun Color.orNowPlayingFallback(fallback: Color): Color {
    return if (this == Color.Unspecified) fallback else this
}
