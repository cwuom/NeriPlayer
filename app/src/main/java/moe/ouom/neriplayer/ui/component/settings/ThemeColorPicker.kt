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
 * Updated: 2026/11/1
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.settings.appearance.ThemeDefaults
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSegmentedTabs

private val FALLBACK_RGB = intArrayOf(0x00, 0x61, 0xA4)

// 让测试和辅助功能能定位 hex 输入框
internal const val HEX_INPUT_TEST_TAG = "themeColorPickerHexInput"

@Composable
fun ThemeColorPicker(
    onColorChanged: (String) -> Unit, // 回调返回 6 位 HEX (不含#)
    initialHex: String = ThemeDefaults.DEFAULT_SEED_COLOR_HEX
) {
    var isRgbPicker by remember { mutableStateOf(false) }
    val selectedIndex = if (isRgbPicker) 1 else 0

    // 初始值先归一, 让 3 位简写或大写/小写输入都落到同一个 6 位 HEX
    val initialRgb = remember(initialHex) {
        ThemeColorPickerColor.normalizeHex(initialHex)
            ?.let(ThemeColorPickerColor::hexToRgb)
            ?: FALLBACK_RGB
    }
    var rgb by remember(initialHex) { mutableStateOf(initialRgb) }
    var hsv by remember(initialHex) { mutableStateOf(ThemeColorPickerColor.rgbToHsv(initialRgb)) }
    // 输入框始终显示已生效的颜色, 简写或非法初始值都以归一后的 6 位 HEX 呈现
    var hexInput by remember(initialHex) { mutableStateOf(ThemeColorPickerColor.rgbToHex(initialRgb)) }

    val previewColor = remember(hsv) { Color(ThemeColorPickerColor.hsvToArgb(hsv)) }
    val hex = remember(previewColor) { ThemeColorPickerColor.argbToHex(previewColor.toArgb()) }

    // RGB 滑动条颜色同步到 HSV
    fun updateRgb(r: Int, g: Int, b: Int) {
        rgb = intArrayOf(r, g, b)
        hsv = ThemeColorPickerColor.rgbToHsv(rgb)
    }

    // HSV 滑动条颜色同步到 RGB
    fun updateHsv(h: Float, s: Float, v: Float) {
        hsv = floatArrayOf(h, s, v)
        rgb = ThemeColorPickerColor.hsvToRgb(hsv)
    }

    fun commitHexInput(input: String) {
        val parsed = ThemeColorPickerColor.normalizeHex(input)
            ?.let(ThemeColorPickerColor::hexToRgb)
        if (parsed == null) {
            // 无法归一或位数不足时回退到当前生效颜色, 不留下无效文本
            hexInput = hex
            return
        }
        updateRgb(parsed[0], parsed[1], parsed[2])
    }

    LaunchedEffect(hex) {
        // 滑动条或提交后的新颜色回写输入框, 避免输入框停在旧值
        hexInput = hex
        onColorChanged(hex)
    }

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
                modifier = Modifier.testTag(HEX_INPUT_TEST_TAG),
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace
                ),
                onValueChange = { input ->
                    ThemeColorPickerColor.sanitizeHexInput(input)?.let { hexInput = it }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { commitHexInput(hexInput) }
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
