package moe.ouom.neriplayer.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归测试 P2：种子色 hex 归一，避免非法值使 toColorInt 在主题组合期崩溃。
 */
class ThemeDefaultsTest {

    @Test
    fun `valid six digit hex is uppercased and stripped`() {
        assertEquals("0061A4", ThemeDefaults.sanitizeSeedColorHex("0061a4"))
        assertEquals("6750A4", ThemeDefaults.sanitizeSeedColorHex("#6750a4"))
        assertEquals("B3261E", ThemeDefaults.sanitizeSeedColorHex("  b3261e  "))
    }

    @Test
    fun `illegal hex falls back to default seed`() {
        // 带 # 的 8 位 ARGB、非 hex 字符、错误长度、空值都回退默认，绝不返回会崩溃的值
        assertEquals(ThemeDefaults.DEFAULT_SEED_COLOR_HEX, ThemeDefaults.sanitizeSeedColorHex("FF0061A4"))
        assertEquals(ThemeDefaults.DEFAULT_SEED_COLOR_HEX, ThemeDefaults.sanitizeSeedColorHex("zzzzzz"))
        assertEquals(ThemeDefaults.DEFAULT_SEED_COLOR_HEX, ThemeDefaults.sanitizeSeedColorHex("123"))
        assertEquals(ThemeDefaults.DEFAULT_SEED_COLOR_HEX, ThemeDefaults.sanitizeSeedColorHex(""))
        assertEquals(ThemeDefaults.DEFAULT_SEED_COLOR_HEX, ThemeDefaults.sanitizeSeedColorHex(null))
    }
}
