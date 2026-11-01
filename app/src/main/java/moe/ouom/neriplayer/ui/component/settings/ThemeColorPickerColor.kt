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
 * File: moe.ouom.neriplayer.ui.component/ThemeColorPickerColor
 * Updated: 2026/11/1
 */

import java.util.Locale
import android.graphics.Color as AndroidColor

/**
 * 取色器的颜色换算与输入校验, 与 Compose 状态解耦以便单独测试
 */
internal object ThemeColorPickerColor {
    private const val HEX_DIGITS = "0123456789ABCDEF"
    private const val FULL_LENGTH = 6

    /** 输入框实时过滤: 去掉 # 前缀后只接受最多 6 位 hex 字符, 非法输入返回 null */
    fun sanitizeHexInput(input: String): String? {
        val candidate = input.trim().removePrefix("#")
        if (candidate.length > FULL_LENGTH) return null
        if (candidate.any { it.uppercaseChar() !in HEX_DIGITS }) return null
        return candidate
    }

    /** 只接受完整的 6 位 HEX, 归一成大写; 其他长度返回 null */
    fun normalizeHex(input: String): String? {
        val candidate = sanitizeHexInput(input) ?: return null
        if (candidate.length != FULL_LENGTH) return null
        return candidate.uppercase(Locale.ROOT)
    }

    /** 6 位 HEX 转 RGB, 非法输入返回 null, 不依赖异常控制流程 */
    fun hexToRgb(hex: String): IntArray? {
        val candidate = sanitizeHexInput(hex) ?: return null
        if (candidate.length != FULL_LENGTH) return null
        val value = candidate.toIntOrNull(16) ?: return null
        return intArrayOf(
            (value shr 16) and 0xFF,
            (value shr 8) and 0xFF,
            value and 0xFF
        )
    }

    /** RGB 三通道转大写 6 位 HEX, 越界通道按 0..255 截断 */
    fun rgbToHex(rgb: IntArray): String {
        require(rgb.size >= 3) { "RGB 需要三个通道" }
        return channelHex(rgb[0]) + channelHex(rgb[1]) + channelHex(rgb[2])
    }

    /** ARGB 整数转大写 6 位 HEX, 忽略 alpha */
    fun argbToHex(argb: Int): String =
        channelHex((argb shr 16) and 0xFF) +
            channelHex((argb shr 8) and 0xFF) +
            channelHex(argb and 0xFF)

    /** RGB 转 HSV, 色相 0..360, 饱和度与明度 0..1 */
    fun rgbToHsv(rgb: IntArray): FloatArray {
        require(rgb.size >= 3) { "RGB 需要三个通道" }
        val hsv = FloatArray(3)
        AndroidColor.RGBToHSV(
            rgb[0].coerceIn(0, 0xFF),
            rgb[1].coerceIn(0, 0xFF),
            rgb[2].coerceIn(0, 0xFF),
            hsv
        )
        return hsv
    }

    /** HSV 转不透明 ARGB 整数, 供 Compose Color 直接使用 */
    fun hsvToArgb(hsv: FloatArray): Int = AndroidColor.HSVToColor(hsv)

    /** HSV 转 RGB 三通道, 供滑动条与输入框同步 */
    fun hsvToRgb(hsv: FloatArray): IntArray {
        val argb = hsvToArgb(hsv)
        return intArrayOf(
            AndroidColor.red(argb),
            AndroidColor.green(argb),
            AndroidColor.blue(argb)
        )
    }

    /** 手写两位十六进制, 避免 String.format 受默认 Locale 影响 */
    private fun channelHex(value: Int): String {
        val channel = value.coerceIn(0, 0xFF)
        return "${HEX_DIGITS[channel shr 4]}${HEX_DIGITS[channel and 0x0F]}"
    }
}
