package moe.ouom.neriplayer.ui.screen.tab.home

import moe.ouom.neriplayer.ui.viewmodel.tab.HomeSectionIdMapping
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomeSongSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 榜单卡片带的分组逻辑测试。
 *
 * 卡片带只能合并相邻的卡片形态子模块：被列表子模块隔开的卡片要各自成带，
 * 否则卡片会越过中间的子模块，破坏用户配置的小模块顺序。
 */
class HomeSongChartCardStripTest {

    @Test
    fun adjacentCardSectionsAreMergedIntoOneRun() {
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
    fun cardsSeparatedByAListSectionAreSplitIntoSeparateRuns() {
        // 私人雷达（卡片）→ 雷达歌单（列表）→ 每日推荐（卡片）：
        // 首尾不能合成一条带，否则每日推荐的卡片会越过中间的雷达歌单。
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
    fun sectionsWithoutCardsProduceNoRuns() {
        val runs = splitHomeSongChartCardRuns(
            visibleIds = listOf("personalized_new_songs", "top_new"),
            cardIds = emptySet()
        )

        assertEquals(emptyList<List<String>>(), runs)
    }

    @Test
    fun cardIdsOutsideTheVisibleSectionsAreIgnored() {
        val runs = splitHomeSongChartCardRuns(
            visibleIds = listOf("top_soaring", "top_hot"),
            cardIds = setOf("top_soaring", "top_hot", "hidden_section")
        )

        assertEquals(listOf(listOf("top_soaring", "top_hot")), runs)
    }

    @Test
    fun sourcesRequiringLoginAreExcludedFromTheCardGroup() {
        // 未登录时每日推荐与私人 FM 拿不到数据，列表里本来就没有它们，
        // 卡片形态也不该出现，否则点进去只会得到一个接口报错。
        val group = collectHomeSongChartCardGroup(
            visibleIds = listOf("personal_radar", "daily_recommend", "private_fm"),
            availableSources = setOf(NeteaseHomeSongSource.PERSONAL_RADAR),
            isChartCardMode = { true },
            sourceOf = { HomeSectionIdMapping.radarSourceOf(it) },
            titleResOf = { it.titleRes }
        )

        assertEquals(setOf("personal_radar"), group.cardIds)
        assertEquals(listOf("personal_radar"), group.cards.map { it.id })
    }

    @Test
    fun loginOnlySourcesAppearOnceSignedIn() {
        val group = collectHomeSongChartCardGroup(
            visibleIds = listOf("personal_radar", "daily_recommend", "private_fm"),
            availableSources = setOf(
                NeteaseHomeSongSource.PERSONAL_RADAR,
                NeteaseHomeSongSource.DAILY_RECOMMEND,
                NeteaseHomeSongSource.PRIVATE_FM
            ),
            isChartCardMode = { true },
            sourceOf = { HomeSectionIdMapping.radarSourceOf(it) },
            titleResOf = { it.titleRes }
        )

        assertEquals(
            listOf("personal_radar", "daily_recommend", "private_fm"),
            group.cards.map { it.id }
        )
    }
}
