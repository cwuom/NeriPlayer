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
 * 覆盖三件事：
 * 1. 卡片条要放在「第一个卡片形态小模块」所在的位置，否则用户配置的顺序会被忽略；
 * 2. 被列表模块隔开的卡片要各自成带，否则卡片会越过中间的小模块；
 * 3. 卡片宽度要按「并排数量」平均分配，否则 1 列设置会因固定宽度上限而露出多张卡片。
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
    fun `cards separated by a list section are split into separate runs`() {
        // 「私人雷达（卡片）→ 雷达歌单（列表）→ 每日推荐（卡片）」
        // 不能合成一条卡片带，否则每日推荐的卡片会越过雷达歌单。
        val runs = splitHomeSongChartCardRuns(
            visibleIds = listOf("personal_radar", "radar_playlists", "daily_recommend"),
            cardIds = setOf("personal_radar", "daily_recommend")
        )

        assertEquals(
            listOf(listOf("personal_radar"), listOf("daily_recommend")),
            runs
        )
    }

    @Test
    fun `adjacent cards are merged into one run`() {
        val runs = splitHomeSongChartCardRuns(
            visibleIds = listOf("top_soaring", "top_hot", "personalized_new_songs", "top_new"),
            cardIds = setOf("top_soaring", "top_hot", "top_new")
        )

        assertEquals(
            listOf(listOf("top_soaring", "top_hot"), listOf("top_new")),
            runs
        )
    }

    @Test
    fun `chart card strip sits at the position of its first card section`() {
        // 「私人雷达（卡片）→ 雷达歌单（列表）→ 每日推荐（卡片）」
        // 渲染顺序必须是：卡片带 → 雷达歌单 → 卡片带。
        // 卡片带不能越过中间的列表小模块。
        val entries = resolveHomeSongSectionRenderEntries(
            visibleIds = listOf("personal_radar", "radar_playlists", "daily_recommend"),
            cardIds = setOf("personal_radar", "daily_recommend")
        )

        assertEquals(
            listOf(
                HomeSongSectionRenderEntry.ChartCardStrip(0, listOf("personal_radar")),
                HomeSongSectionRenderEntry.ListSection("radar_playlists"),
                HomeSongSectionRenderEntry.ChartCardStrip(1, listOf("daily_recommend"))
            ),
            entries
        )
    }

    @Test
    fun `adjacent card sections are merged into one strip at the first position`() {
        // 「热力飙升（卡片）→ 热歌榜（卡片）→ 推荐新歌（列表）」
        // 前两个卡片合成一条带，落在热力飙升的位置。
        val entries = resolveHomeSongSectionRenderEntries(
            visibleIds = listOf("top_soaring", "top_hot", "personalized_new_songs"),
            cardIds = setOf("top_soaring", "top_hot")
        )

        assertEquals(
            listOf(
                HomeSongSectionRenderEntry.ChartCardStrip(0, listOf("top_soaring", "top_hot")),
                HomeSongSectionRenderEntry.ListSection("personalized_new_songs")
            ),
            entries
        )
    }

    @Test
    fun `a list only module keeps one entry per section`() {
        val entries = resolveHomeSongSectionRenderEntries(
            visibleIds = listOf("personalized_new_songs", "top_new"),
            cardIds = emptySet()
        )

        assertEquals(
            listOf(
                HomeSongSectionRenderEntry.ListSection("personalized_new_songs"),
                HomeSongSectionRenderEntry.ListSection("top_new")
            ),
            entries
        )
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
    fun `multi column cards get the expected width instead of collapsing to zero`() {
        val containerWidthDp = 360f
        val spacingDp = 12f
        val paddingDp = 8f * 2f

        // 2 列：(360 - 16 - 12) / 2 = 166
        assertEquals(
            166f,
            resolveHomeSongChartCardWidthDp(containerWidthDp, columns = 2),
            0.01f
        )
        // 3 列：(360 - 16 - 24) / 3 = 106.666...
        assertEquals(
            (containerWidthDp - paddingDp - spacingDp * 2) / 3f,
            resolveHomeSongChartCardWidthDp(containerWidthDp, columns = 3),
            0.01f
        )
    }

    @Test
    fun `one column card uses the 200dp cap`() {
        val containerWidthDp = 360f
        val widthDp = resolveHomeSongChartCardWidthDp(containerWidthDp, columns = 1)
        // 一列时每行只放一张卡片，卡片宽度是 min(360 - 16, 200) = 200dp。
        assertEquals(200f, widthDp, 0.01f)
    }

    @Test
    fun `card width is capped on very wide containers`() {
        val widthDp = resolveHomeSongChartCardWidthDp(containerWidthDp = 2000f, columns = 1)
        assertTrue("超宽容器下单张卡片不应铺满整行：$widthDp", widthDp <= 200f)
    }
}
