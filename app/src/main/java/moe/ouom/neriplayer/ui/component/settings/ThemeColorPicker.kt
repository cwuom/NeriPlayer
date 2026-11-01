package moe.ouom.neriplayer.ui.component.settings

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
 * File: moe.ouom.neriplayer.ui.component/ThemeColorPicker
 * Updated: 2026/3/23
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSegmentedTabs
import android.graphics.Color as AndroidColor
import moe.ouom.neriplayer.common.R as CoreCommonR

@Composable
fun ThemeColorPicker(
    onColorChanged: (String) -> Unit, // 回调返回 6 位 HEX (不含#)
    initialHex: String = "0061A4"
) {
    var isRgbPicker by remember { mutableStateOf(false) }
    val selectedIndex = if (isRgbPicker) 1 else 0

    // 把 HEX 转为 RGB
    fun hexToRgb(hex: String): IntArray {
        val c = try {
            Color(("#$hex").toColorInt())
        } catch (_: Throwable) {
            Color(0xFF0061A4)
        }
        val argb = c.toArgb()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return intArrayOf(r, g, b)
    }

    // 把 HEX 转为 HSV
    fun hexToHsv(hex: String): FloatArray {
        val rgb = hexToRgb(hex)
        val hsv = FloatArray(3)
        AndroidColor.RGBToHSV(rgb[0], rgb[1], rgb[2], hsv)
        return hsv
    }

    fun normalizeColorHex(hex: String): String? {
        if (hex.isEmpty() || hex.length > 6 || !hex.all { it in "0123456789ABCDEFabcdef" }) {
            return null
        }

        val normalized = when (hex.length) {
            1 -> hex.repeat(6)
            2 -> hex.repeat(3)
            3 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex
            else -> return null
        }

        return normalized.uppercase()
    }

    var hexInput by remember(initialHex) { mutableStateOf(initialHex) }
    var rgb by remember(initialHex) { mutableStateOf(hexToRgb(initialHex)) }
    var hsv by remember(initialHex) { mutableStateOf(hexToHsv(initialHex)) }
    val previewColor = remember(hsv) {
        val argb = AndroidColor.HSVToColor(hsv)
        Color(argb)
    }
    val hex = remember(previewColor) {
        // 转 6 位 HEX (不含 #)
        val a = previewColor.toArgb()
        val r = (a shr 16) and 0xFF
        val g = (a shr 8) and 0xFF
        val b = a and 0xFF
        String.format("%02X%02X%02X", r, g, b)
    }

    // RGB 滑动条颜色同步到 HSV
    fun updateRgb(r: Int, g: Int, b: Int) {
        rgb = intArrayOf(r, g, b)
        val convertedHsv = FloatArray(3)
        AndroidColor.RGBToHSV(r, g, b, convertedHsv)
        hsv = convertedHsv
    }

    // HSV 滑动条颜色同步到 RGB
    fun updateHsv(h: Float, s: Float, v: Float) {
        hsv = floatArrayOf(h, s, v)
        val c = AndroidColor.HSVToColor(hsv)
        rgb = intArrayOf(
            AndroidColor.red(c),
            AndroidColor.green(c),
            AndroidColor.blue(c)
        )
    }

    fun updateHex(hex: String) {
        val normalizedHex = normalizeColorHex(hex)
        if (normalizedHex != null) {
            val rgb = hexToRgb(normalizedHex)
            updateRgb(rgb[0], rgb[1], rgb[2])
        }
    }

    LaunchedEffect(hex) { onColorChanged(hex) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 预览
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(previewColor)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
            )
            Spacer(Modifier.size(12.dp))
            Text("#", fontFamily = FontFamily.Monospace)
            BasicTextField(
                value = hexInput,
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace
                ),
                onValueChange = {
                    if (it.length <= 6 &&
                        it.all { c ->
                            c in "0123456789ABCDEFabcdef"
                        }
                    ) {
                        hexInput = it
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        updateHex(hexInput)
                    }
                )
            )
        }

        // HSV 与 RGB 颜色选择器切换
        MiuixSettingsSegmentedTabs(
            labels = listOf(
                stringResource(CoreCommonR.string.color_picker_hsv),
                stringResource(CoreCommonR.string.color_picker_rgb)
            ),
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { index ->
                isRgbPicker = index == 1
            }
        )

        if (!isRgbPicker) {
            // Hue 0..360
            Text(stringResource(CoreCommonR.string.color_hue))
            Slider(
                value = hsv[0],
                onValueChange = { updateHsv(it, hsv[1], hsv[2]) },
                valueRange = 0f..360f
            )

            // Saturation 0..1
            Text(stringResource(CoreCommonR.string.color_saturation))
            Slider(
                value = hsv[1],
                onValueChange = { updateHsv(hsv[0], it, hsv[2]) },
                valueRange = 0f..1f
            )

            // Value 0..1
            Text(stringResource(CoreCommonR.string.color_value))
            Slider(
                value = hsv[2],
                onValueChange = { updateHsv(hsv[0], hsv[1], it) },
                valueRange = 0f..1f
            )
        } else {
            // Red 0..255
            Text(stringResource(CoreCommonR.string.color_red))
            Slider(
                value = rgb[0].toFloat(),
                onValueChange = { updateRgb(it.toInt(), rgb[1], rgb[2]) },
                valueRange = 0f..255f
            )

            // Green 0..255
            Text(stringResource(CoreCommonR.string.color_green))
            Slider(
                value = rgb[1].toFloat(),
                onValueChange = { updateRgb(rgb[0], it.toInt(), rgb[2]) },
                valueRange = 0f..255f
            )

            // Blue 0..255
            Text(stringResource(CoreCommonR.string.color_blue))
            Slider(
                value = rgb[2].toFloat(),
                onValueChange = { updateRgb(rgb[0], rgb[1], it.toInt()) },
                valueRange = 0f..255f
            )
        }
    }
}
