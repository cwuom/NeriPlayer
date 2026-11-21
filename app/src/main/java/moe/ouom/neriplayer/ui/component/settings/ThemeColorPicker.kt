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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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

/**
 * 取色器对外暴露的提交句柄, 让调用方在读取颜色前先落地输入框草稿
 * 例如对话框的"应用颜色"/"加入色板"按钮, 用户可能没按键盘 Done
 */
@Stable
class ThemeColorPickerState internal constructor() {
    internal var pendingInput: (() -> String)? = null

    /**
     * 输入框里尚未确认的草稿, 供调用方在按钮可用性判断中实时读取
     * 未归一, 可能是不完整的 6 位 HEX 或非法文本
     */
    var draftHex by mutableStateOf<String?>(null)
        internal set

    /**
     * 提交输入框中尚未确认的 HEX, 返回当前生效的 6 位大写 HEX
     * 不足 6 位或含非 hex 字符时回滚到上一个生效颜色, 未初始化时返回 null
     */
    fun commitPendingInput(): String? = pendingInput?.invoke()
}

@Composable
fun rememberThemeColorPickerState(): ThemeColorPickerState = remember { ThemeColorPickerState() }

/**
 * 主题色取色器：HSV 三滑块与 RGB 三通道可切换, 预览色旁的 HEX 输入框只接受完整 6 位
 * 三条路径共享 rgb/hsv/hexInput 状态, 每次生效颜色变化都会回调 [onColorChanged]
 *
 * @param onColorChanged 生效颜色变化回调, 参数为不含 # 的 6 位大写 HEX
 * @param initialHex 初始颜色, 非 6 位或非法值会回退到主题默认种子色
 * @param state 供外部在读取当前颜色前提交输入框草稿, 默认由 [rememberThemeColorPickerState] 创建
 */
@Composable
fun ThemeColorPicker(
    onColorChanged: (String) -> Unit, // 回调返回 6 位 HEX (不含#)
    initialHex: String = ThemeDefaults.DEFAULT_SEED_COLOR_HEX,
    state: ThemeColorPickerState = rememberThemeColorPickerState()
) {
    var isRgbPicker by remember { mutableStateOf(false) }
    val selectedIndex = if (isRgbPicker) 1 else 0

    // 初始值先归一, 让大写/小写输入都落到同一个 6 位 HEX
    val initialRgb = remember(initialHex) {
        ThemeColorPickerColor.normalizeHex(initialHex)
            ?.let(ThemeColorPickerColor::hexToRgb)
            ?: FALLBACK_RGB
    }
    var rgb by remember(initialHex) { mutableStateOf(initialRgb) }
    var hsv by remember(initialHex) { mutableStateOf(ThemeColorPickerColor.rgbToHsv(initialRgb)) }
    // 输入框始终显示已生效的颜色, 非法初始值以主题默认种子色呈现
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

    fun commitHexInput(input: String): String {
        val normalized = ThemeColorPickerColor.normalizeHex(input)
        val parsed = normalized?.let(ThemeColorPickerColor::hexToRgb)
        if (normalized == null || parsed == null) {
            // 不足 6 位或含非法字符时回退到当前生效颜色, 不留下无效文本
            hexInput = hex
            return hex
        }
        // 颜色未变化时 hex 不变, LaunchedEffect 不会重跑, 这里直接写回归一值
        hexInput = normalized
        updateRgb(parsed[0], parsed[1], parsed[2])
        return normalized
    }

    LaunchedEffect(hex) {
        // 滑动条或提交后的新颜色回写输入框, 避免输入框停在旧值
        hexInput = hex
        onColorChanged(hex)
    }

    // 把当前输入框草稿的提交入口交给外部, 供对话框按钮在读取颜色前调用
    SideEffect {
        state.pendingInput = { commitHexInput(hexInput) }
        state.draftHex = hexInput
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
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                onValueChange = { input ->
                    // 6 位输满即提交, 避免用户直接点"应用颜色"时丢掉未按 Done 的输入
                    ThemeColorPickerColor.sanitizeHexInput(input)?.let { sanitized ->
                        hexInput = sanitized
                        if (sanitized.length == 6) commitHexInput(sanitized)
                    }
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
