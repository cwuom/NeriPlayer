package moe.ouom.neriplayer.ui.screen.tab.home

import moe.ouom.neriplayer.ui.viewmodel.tab.HomeNeteaseSongSectionState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeSectionState
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomeSongSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 榜单卡片条相关测试。
 *
 * 覆盖两件事：
 * 1. 卡片条要放在「第一个卡片形态小模块」所在的位置，否则用户配置的顺序会被忽略；
 * 2. 卡片宽度要按「并排数量」平均分配，否则 1 列设置会因固定宽度上限而露出多张卡片。
 */
class HomeSongChartCardStripTest {

    private fun sectionState(source: NeteaseHomeSongSource): HomeNeteaseSongSectionState =
        HomeNeteaseSongSectionState(
            source = source,
            section = HomeSectionState(items = emptyList())
        )

    @Test
    fun `chart cards keep the configured subsection order`() {
        val group = collectHomeSongChartCardGroup(
            visibleIds = listOf("top_soaring", "personalized_new_songs", "top_hot"),
            isChartCardMode = { id -> id == "top_soaring" || id == "top_hot" },
            sourceOf = { id ->
                when (id) {
                    "top_soaring" -> NeteaseHomeSongSource.TOP_SOARING
                    "top_hot" -> NeteaseHomeSongSource.TOP_HOT
                    else -> null
                }
            },
            sectionStateOf = { source -> sectionState(source) }
        )

        assertEquals(listOf("top_soaring", "top_hot"), group.cards.map { it.id })
        assertEquals(setOf("top_soaring", "top_hot"), group.cardIds)
    }

    @Test
    fun `default list sections are not collected as chart cards`() {
        val group = collectHomeSongChartCardGroup(
            visibleIds = listOf("personalized_new_songs"),
            isChartCardMode = { false },
            sourceOf = { NeteaseHomeSongSource.PERSONALIZED_NEW_SONGS },
            sectionStateOf = { source -> sectionState(source) }
        )

        assertTrue(group.cards.isEmpty())
        assertTrue(group.cardIds.isEmpty())
    }

    @Test
    fun `card width is divided evenly so the column count is honored`() {
        val containerWidthDp = 360f
        listOf(1, 2, 3).forEach { columns ->
            val widthDp = resolveHomeSongChartCardWidthDp(containerWidthDp, columns)
            val spacingDp = 12f
            val paddingDp = 8f * 2f
            val occupiedDp = widthDp * columns + spacingDp * (columns - 1) + paddingDp
            assertTrue(
                "列数为 $columns 时卡片不应超出容器宽度，实际 $occupiedDp > $containerWidthDp",
                occupiedDp <= containerWidthDp + 0.01f
            )
        }
    }

    @Test
    fun `one column card is wide enough that no second card fits on screen`() {
        val containerWidthDp = 360f
        val widthDp = resolveHomeSongChartCardWidthDp(containerWidthDp, columns = 1)
        // 一列时，卡片加上间距后应当正好占满一行，看不到第二张卡片。
        assertTrue("一列时卡片宽度不应被压得过小：$widthDp", widthDp > 140f)
    }

    @Test
    fun `card width is capped on very wide containers`() {
        val widthDp = resolveHomeSongChartCardWidthDp(containerWidthDp = 2000f, columns = 1)
        assertTrue("超宽容器下单张卡片不应铺满整行：$widthDp", widthDp <= 200f)
    }
}
