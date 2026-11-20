package moe.ouom.neriplayer.ui.screen.tab.home

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页歌曲小模块卡片的封面配色测试。
 */
class HomeSongChartPaletteTest {

    @Test
    fun `palette covers every supported song section`() {
        assertEquals(
            setOf(
                "top_soaring",
                "personal_radar",
                "top_hot",
                "top_new",
                "daily_recommend",
                "private_fm",
                "personalized_new_songs"
            ),
            HomeSongChartPalette.bySectionId.keys
        )
    }

    @Test
    fun `palette assigns a distinct color to each section`() {
        val colors = HomeSongChartPalette.bySectionId.values
        assertEquals(
            "每个小模块都应当有独立的配色",
            colors.size,
            colors.distinct().size
        )
    }

    @Test
    fun `palette provides a gradient base color for every section`() {
        assertEquals(
            HomeSongChartPalette.bySectionId.keys,
            HomeSongChartPalette.bySectionIdDeep.keys
        )
    }

    @Test
    fun `daily recommend uses a soft red rather than a harsh one`() {
        val color = HomeSongChartPalette.colorOf("daily_recommend")
        assertNotNull(color)
        assertTrue("每日推荐应当是明显的红色", color!!.red > 0.7f)
        assertTrue("每日推荐的红应当压过绿", color.red > color.green)
        assertTrue("每日推荐的红应当压过蓝", color.red > color.blue)
    }

    @Test
    fun `palette keeps the muted low saturation look`() {
        // 「柔和高级」方案：所有主色的饱和度都不应过高，避免刺眼。
        HomeSongChartPalette.bySectionId.forEach { (id, color) ->
            val max = maxOf(color.red, color.green, color.blue)
            val min = minOf(color.red, color.green, color.blue)
            val saturation = if (max == 0f) 0f else (max - min) / max
            assertTrue("$id 的配色过于饱和：$saturation", saturation <= 0.70f)
        }
    }

    @Test
    fun `unknown section id falls back to null`() {
        assertNull(HomeSongChartPalette.colorOf("not_a_real_section"))
        assertNull(HomeSongChartPalette.deepColorOf("not_a_real_section"))
    }

    @Test
    fun `shade keeps alpha and only darkens`() {
        val base = Color(0xFFA78BFA)
        val shaded = base.shadeForChartGradient(0.5f)
        assertEquals(base.alpha, shaded.alpha, 0.0001f)
        assertTrue(shaded.red < base.red)
        assertTrue(shaded.green < base.green)
        assertTrue(shaded.blue < base.blue)
    }
}
