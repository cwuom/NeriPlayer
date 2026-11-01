package moe.ouom.neriplayer.ui.component.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class ThemeColorPickerColorTest {

    @Test
    fun `sanitize strips prefix and rejects over long or non hex input`() {
        assertEquals("0061a4", ThemeColorPickerColor.sanitizeHexInput("#0061a4"))
        assertEquals("0061A4", ThemeColorPickerColor.sanitizeHexInput(" 0061A4 "))
        assertEquals("", ThemeColorPickerColor.sanitizeHexInput(""))
        assertNull(ThemeColorPickerColor.sanitizeHexInput("0061A45"))
        assertNull(ThemeColorPickerColor.sanitizeHexInput("0061G4"))
        assertNull(ThemeColorPickerColor.sanitizeHexInput("zzzzzz"))
    }

    @Test
    fun `normalize expands shorthand lengths and uppercases`() {
        assertEquals("FFFFFF", ThemeColorPickerColor.normalizeHex("f"))
        assertEquals("00AA00", ThemeColorPickerColor.normalizeHex("0a0"))
        assertEquals("0061A4", ThemeColorPickerColor.normalizeHex("#0061a4"))
        assertNull(ThemeColorPickerColor.normalizeHex(""))
        assertNull(ThemeColorPickerColor.normalizeHex("ab"))
        assertNull(ThemeColorPickerColor.normalizeHex("abcd"))
        assertNull(ThemeColorPickerColor.normalizeHex("abcde"))
        assertNull(ThemeColorPickerColor.normalizeHex("abcdef0"))
    }

    @Test
    fun `hex to rgb rejects invalid input without throwing`() {
        assertArrayEquals(intArrayOf(0, 97, 164), ThemeColorPickerColor.hexToRgb("0061A4"))
        assertArrayEquals(intArrayOf(255, 255, 255), ThemeColorPickerColor.hexToRgb("ffffff"))
        assertNull(ThemeColorPickerColor.hexToRgb("0061A"))
        assertNull(ThemeColorPickerColor.hexToRgb("zzzzzz"))
        assertNull(ThemeColorPickerColor.hexToRgb(""))
    }

    @Test
    fun `rgb to hex round trips and pads channels`() {
        assertEquals("0061A4", ThemeColorPickerColor.rgbToHex(intArrayOf(0, 97, 164)))
        assertEquals("FFFFFF", ThemeColorPickerColor.rgbToHex(intArrayOf(255, 255, 255)))
        assertEquals("000000", ThemeColorPickerColor.rgbToHex(intArrayOf(0, 0, 0)))
        assertEquals("0061A4", ThemeColorPickerColor.argbToHex(0xFF0061A4.toInt()))
    }

    @Test
    fun `rgb to hex clamps out of range channels`() {
        assertEquals("00FF00", ThemeColorPickerColor.rgbToHex(intArrayOf(-1, 300, 0)))
    }

    @Test
    fun `rgb and hsv conversions stay consistent`() {
        val rgb = ThemeColorPickerColor.hexToRgb("0061A4")!!
        val hsv = ThemeColorPickerColor.rgbToHsv(rgb)
        assertEquals(3, hsv.size)
        assertEquals(ThemeColorPickerColor.hsvToRgb(hsv).toList(), rgb.toList())
    }

    @Test
    fun `hsv to argb keeps the requested hue`() {
        val green = ThemeColorPickerColor.hsvToArgb(floatArrayOf(120f, 1f, 1f))
        assertEquals("00FF00", ThemeColorPickerColor.argbToHex(green))
    }
}
