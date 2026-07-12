package moe.ouom.neriplayer.ui.theme

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.theme/NeriTheme
 * Created: 2025/8/8
 */

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.toColorInt
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

private val NeriTypography = Typography()
private const val ThemeColorTransitionDurationMs = 420

@Composable
fun NeriTheme(
    followSystemDark: Boolean,
    forceDark: Boolean,
    dynamicColor: Boolean,
    seedColorHex: String,
    paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    colorSpec: ColorSpec.SpecVersion = ColorSpec.SpecVersion.Default,
    systemDark: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val isDark = when {
        forceDark -> true
        followSystemDark -> systemDark ?: isSystemInDarkTheme()
        else -> false
    }

    val targetColorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            val seed = Color(("#$seedColorHex").toColorInt())
            rememberDynamicColorScheme(
                seedColor = seed,
                isDark = isDark,
                style = paletteStyle,
                specVersion = colorSpec
            )
        }
    }
    val colorScheme = animateColorScheme(targetColorScheme)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NeriTypography,
        content = content
    )
}

@Composable
private fun animateColorScheme(target: ColorScheme): ColorScheme {
    return target.copy(
        primary = animateThemeColor(target.primary, "theme-primary"),
        onPrimary = animateThemeColor(target.onPrimary, "theme-on-primary"),
        primaryContainer = animateThemeColor(target.primaryContainer, "theme-primary-container"),
        onPrimaryContainer = animateThemeColor(target.onPrimaryContainer, "theme-on-primary-container"),
        inversePrimary = animateThemeColor(target.inversePrimary, "theme-inverse-primary"),
        secondary = animateThemeColor(target.secondary, "theme-secondary"),
        onSecondary = animateThemeColor(target.onSecondary, "theme-on-secondary"),
        secondaryContainer = animateThemeColor(target.secondaryContainer, "theme-secondary-container"),
        onSecondaryContainer = animateThemeColor(target.onSecondaryContainer, "theme-on-secondary-container"),
        tertiary = animateThemeColor(target.tertiary, "theme-tertiary"),
        onTertiary = animateThemeColor(target.onTertiary, "theme-on-tertiary"),
        tertiaryContainer = animateThemeColor(target.tertiaryContainer, "theme-tertiary-container"),
        onTertiaryContainer = animateThemeColor(target.onTertiaryContainer, "theme-on-tertiary-container"),
        background = animateThemeColor(target.background, "theme-background"),
        onBackground = animateThemeColor(target.onBackground, "theme-on-background"),
        surface = animateThemeColor(target.surface, "theme-surface"),
        onSurface = animateThemeColor(target.onSurface, "theme-on-surface"),
        surfaceVariant = animateThemeColor(target.surfaceVariant, "theme-surface-variant"),
        onSurfaceVariant = animateThemeColor(target.onSurfaceVariant, "theme-on-surface-variant"),
        surfaceTint = animateThemeColor(target.surfaceTint, "theme-surface-tint"),
        inverseSurface = animateThemeColor(target.inverseSurface, "theme-inverse-surface"),
        inverseOnSurface = animateThemeColor(target.inverseOnSurface, "theme-inverse-on-surface"),
        error = animateThemeColor(target.error, "theme-error"),
        onError = animateThemeColor(target.onError, "theme-on-error"),
        errorContainer = animateThemeColor(target.errorContainer, "theme-error-container"),
        onErrorContainer = animateThemeColor(target.onErrorContainer, "theme-on-error-container"),
        outline = animateThemeColor(target.outline, "theme-outline"),
        outlineVariant = animateThemeColor(target.outlineVariant, "theme-outline-variant"),
        scrim = animateThemeColor(target.scrim, "theme-scrim"),
        surfaceBright = animateThemeColor(target.surfaceBright, "theme-surface-bright"),
        surfaceDim = animateThemeColor(target.surfaceDim, "theme-surface-dim"),
        surfaceContainer = animateThemeColor(target.surfaceContainer, "theme-surface-container"),
        surfaceContainerHigh = animateThemeColor(target.surfaceContainerHigh, "theme-surface-container-high"),
        surfaceContainerHighest = animateThemeColor(target.surfaceContainerHighest, "theme-surface-container-highest"),
        surfaceContainerLow = animateThemeColor(target.surfaceContainerLow, "theme-surface-container-low"),
        surfaceContainerLowest = animateThemeColor(target.surfaceContainerLowest, "theme-surface-container-lowest"),
        primaryFixed = animateThemeColor(target.primaryFixed, "theme-primary-fixed"),
        primaryFixedDim = animateThemeColor(target.primaryFixedDim, "theme-primary-fixed-dim"),
        onPrimaryFixed = animateThemeColor(target.onPrimaryFixed, "theme-on-primary-fixed"),
        onPrimaryFixedVariant = animateThemeColor(target.onPrimaryFixedVariant, "theme-on-primary-fixed-variant"),
        secondaryFixed = animateThemeColor(target.secondaryFixed, "theme-secondary-fixed"),
        secondaryFixedDim = animateThemeColor(target.secondaryFixedDim, "theme-secondary-fixed-dim"),
        onSecondaryFixed = animateThemeColor(target.onSecondaryFixed, "theme-on-secondary-fixed"),
        onSecondaryFixedVariant = animateThemeColor(target.onSecondaryFixedVariant, "theme-on-secondary-fixed-variant"),
        tertiaryFixed = animateThemeColor(target.tertiaryFixed, "theme-tertiary-fixed"),
        tertiaryFixedDim = animateThemeColor(target.tertiaryFixedDim, "theme-tertiary-fixed-dim"),
        onTertiaryFixed = animateThemeColor(target.onTertiaryFixed, "theme-on-tertiary-fixed"),
        onTertiaryFixedVariant = animateThemeColor(target.onTertiaryFixedVariant, "theme-on-tertiary-fixed-variant")
    )
}

@Composable
private fun animateThemeColor(target: Color, label: String): Color {
    if (target == Color.Unspecified) return target
    val color by animateColorAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = ThemeColorTransitionDurationMs,
            easing = FastOutSlowInEasing
        ),
        label = label
    )
    return color
}
