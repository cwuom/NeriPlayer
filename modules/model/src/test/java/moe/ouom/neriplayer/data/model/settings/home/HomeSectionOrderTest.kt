package moe.ouom.neriplayer.data.model.settings.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSectionOrderTest {

    @Test
    fun parseOrderKeepsUserOrderAndAppendsMissing() {
        val parsed = parseHomeSectionOrder(
            "radar,continue",
            HomeMajorSectionIds.DEFAULT_ORDER
        )
        assertEquals(
            listOf("radar", "continue", "trending", "recommended", "bilibili"),
            parsed
        )
    }

    @Test
    fun parseOrderDropsUnknownAndFallsBackOnBlank() {
        assertEquals(
            HomeTrendingSubSectionIds.DEFAULT_ORDER,
            parseHomeSectionOrder("", HomeTrendingSubSectionIds.DEFAULT_ORDER)
        )
        assertEquals(
            HomeTrendingSubSectionIds.DEFAULT_ORDER,
            parseHomeSectionOrder(
                "unknown,also_unknown",
                HomeTrendingSubSectionIds.DEFAULT_ORDER
            )
        )
    }

    @Test
    fun hiddenOnlyKeepsKnownKeys() {
        val hidden = parseHomeSectionHidden(
            "daily_recommend,unknown",
            HomeRadarSubSectionIds.DEFAULT_ORDER
        )
        assertEquals(setOf("daily_recommend"), hidden)
    }

    @Test
    fun applyOrderFiltersHidden() {
        val visible = applyHomeSectionOrder(
            defaults = HomeRecommendedSubSectionIds.DEFAULT_ORDER,
            order = listOf("hot_playlists", "personalized"),
            hidden = setOf("personalized")
        )
        assertEquals(
            listOf("hot_playlists", "daily_resource", "high_quality", "acg_playlists"),
            visible
        )
    }

    @Test
    fun moveItem() {
        assertEquals(
            listOf("b", "c", "a"),
            moveHomeSectionItem(listOf("a", "b", "c"), 0, 2)
        )
    }

    @Test
    fun moveItemByKeyMovesBeforeTarget() {
        assertEquals(
            listOf("c", "a", "b"),
            moveHomeSectionItemByKey(listOf("a", "b", "c"), key = "c", targetKey = "a")
        )
    }

    @Test
    fun moveItemByKeyKeepsRelativeOrderOfOthers() {
        assertEquals(
            listOf("b", "c", "a"),
            moveHomeSectionItemByKey(listOf("a", "b", "c"), key = "a", targetKey = "c")
        )
    }

    @Test
    fun moveItemByKeyIgnoresUnknownKeys() {
        val order = listOf("a", "b")
        assertEquals(order, moveHomeSectionItemByKey(order, key = "x", targetKey = "a"))
        assertEquals(order, moveHomeSectionItemByKey(order, key = "a", targetKey = "x"))
        assertEquals(order, moveHomeSectionItemByKey(order, key = "a", targetKey = "a"))
    }
}
