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
 * File: moe.ouom.neriplayer.theme/NeriTheme
 * Created: 2025/8/8
 */

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val SeedPrimary = Color(0xFF5661F2)
private val LightColors = lightColorScheme(
    primary = SeedPrimary,
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260)
)
private val DarkColors = darkColorScheme(
    primary = SeedPrimary,
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8)
)

private val NeriTypography = Typography()

@Composable
fun NeriTheme(
    followSystemDark: Boolean,
    forceDark: Boolean,
    dynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val isDark = when {
        forceDark -> true
        followSystemDark -> isSystemInDarkTheme()
        else -> false
    }

    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NeriTypography,
        content = content
    )
}
