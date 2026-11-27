package moe.ouom.neriplayer.data.model.settings.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSectionOrderTest {

    @Test
    fun internationalLayoutNormalizesSavedSectionsAndKeepsTheFeedIndependentlyReachable() {
        val defaults = InternationalHomeLayout()
        assertEquals(listOf("spotlight", "continue", "feed"), defaults.visibleSections)
        val layout = InternationalHomeLayout(
            order = listOf("feed", "radar", "feed"),
            hidden = setOf("spotlight", "continue", "bilibili")
        )
        assertEquals(listOf("feed"), layout.visibleSections)
        assertEquals(true, layout.isHomeAvailable(hasRecentUsage = false))
        val continueOnly = defaults.copy(hidden = setOf("spotlight", "feed"))
        assertEquals(false, continueOnly.isHomeAvailable(hasRecentUsage = false))
        assertEquals(true, continueOnly.isHomeAvailable(hasRecentUsage = true))
    }

    @Test
    fun internationalFeedUsesOnlyEnabledKindsAndKeepsSpotlightIndependent() {
        val kinds = InternationalHomeFeedKindIds
        val defaults = InternationalHomeLayout()
        assertEquals(listOf("track", "playlist", "album", "artist"), defaults.visibleFeedKinds)
        val partlyHidden = defaults.copy(hiddenFeedKinds = setOf(kinds.TRACK, kinds.ALBUM, "unknown"))
        assertEquals(listOf(kinds.PLAYLIST, kinds.ARTIST), partlyHidden.visibleFeedKinds)
        assertEquals(defaults.visibleSections, partlyHidden.visibleSections)

        val allHidden = defaults.copy(hiddenFeedKinds = kinds.DEFAULT_ORDER.toSet())
        assertEquals(listOf("spotlight", "continue"), allHidden.visibleSections)
        assertEquals(true, allHidden.hasOnlineSections)
        assertEquals(true, allHidden.isHomeAvailable(hasRecentUsage = false))
        val continueOnly = allHidden.copy(hidden = setOf("spotlight"))
        assertEquals(false, continueOnly.hasOnlineSections)
        assertEquals(false, continueOnly.isHomeAvailable(hasRecentUsage = false))
        assertEquals(true, continueOnly.isHomeAvailable(hasRecentUsage = true))
        val feedOnly = allHidden.copy(hidden = setOf("spotlight", "continue"))
        assertEquals(emptyList<String>(), feedOnly.visibleSections)
        assertEquals(false, feedOnly.isHomeAvailable(hasRecentUsage = true))
    }

    @Test
    fun radarPrecedesTrendingUnlessTheUserSavedAnotherOrder() {
        val defaults = HomeMajorSectionIds.DEFAULT_ORDER
        assertEquals(
            listOf("continue", "radar", "trending", "recommended", "bilibili"),
            parseHomeSectionOrder(null, defaults)
        )
        val saved = listOf("continue", "trending", "radar", "recommended", "bilibili")
        assertEquals(saved, parseHomeSectionOrder(encodeHomeSectionOrder(saved), defaults))
    }

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
    @Test
    fun internationalReorderPreservesBilibiliPosition() {
        assertEquals(
            listOf("radar", "bilibili", "continue"),
            mergeVisibleHomeSectionOrder(listOf("continue", "bilibili", "radar"), listOf("radar", "continue"))
        )
    }
}
