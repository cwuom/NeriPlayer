package moe.ouom.neriplayer.core.player.service.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 近似系统字体的固定字宽：汉字与 emoji 1em、宽字母 0.95em、省略号 0.8em、其余 0.55em */
internal object FixedWidthMeasurer : LiveUpdateTextMeasurer {
    override fun widthEm(text: String): Float {
        var width = 0f
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            width += when {
                Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt() -> 0f
                codePoint == '…'.code -> 0.8f
                codePoint == 'W'.code || codePoint == 'M'.code -> 0.95f
                codePoint >= 0x2E80 -> 1f
                else -> 0.55f
            }
            index += Character.charCount(codePoint)
        }
        return width
    }
}

class LiveUpdateChipTextTest {
    private fun fit(text: String?, maxWidthEm: Float = LIVE_UPDATE_CHIP_MAX_WIDTH_EM) =
        fitLiveUpdateChipText(text, maxWidthEm, FixedWidthMeasurer)

    @Test
    fun `text that fits the chip is kept whole`() {
        // Android 17 实测这些都能完整显示
        assertEquals("一二三四五", fit("一二三四五"))
        assertEquals("Hellowo", fit("Hellowo"))
        assertEquals("ab一二三", fit("ab一二三"))
    }

    @Test
    fun `wide text is cut by width instead of character count`() {
        // 7 个汉字或 7 个 W 会让系统只显示图标
        assertEquals("晚风吹过…", fit("晚风吹过旧街角"))
        assertEquals("WWWW…", fit("WWWWWWW"))
        assertEquals("Hello w…", fit("Hello world"))
    }

    @Test
    fun `whitespace is normalized and never left before the ellipsis`() {
        assertEquals("abcdefg…", fit("abcdefg hijkl"))
        assertEquals("你好 世界", fit(" 你好\n\t世界 "))
        assertNull(fit(" \n "))
        assertNull(fit(null))
    }

    @Test
    fun `truncation keeps whole graphemes`() {
        assertEquals("😀".repeat(4) + "…", fit("😀".repeat(8)))
        val accented = "é"
        assertEquals(accented.repeat(7) + "…", fit(accented.repeat(12)))
    }

    @Test
    fun `nothing is returned when not even one grapheme fits`() {
        assertNull(fit("一二三", maxWidthEm = 1.2f))
    }
}
