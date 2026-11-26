package moe.ouom.neriplayer.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.navigation.Destinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class MainTabReselectTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tapping the settled selected tab is a reselect`() {
        assertTrue(
            isMainTabReselect(
                selectedRoute = Destinations.Home.route,
                currentRoute = Destinations.Home.route,
                pendingRoute = null,
                targetRoute = Destinations.Home.route
            )
        )
    }

    @Test
    fun `switching tabs or tapping during a pending switch keeps the switch path`() {
        assertFalse(
            isMainTabReselect(
                selectedRoute = Destinations.Home.route,
                currentRoute = Destinations.Home.route,
                pendingRoute = null,
                targetRoute = Destinations.Explore.route
            )
        )
        assertFalse(
            isMainTabReselect(
                selectedRoute = Destinations.Explore.route,
                currentRoute = Destinations.Home.route,
                pendingRoute = Destinations.Explore.route,
                targetRoute = Destinations.Explore.route
            )
        )
        assertFalse(
            isMainTabReselect(
                selectedRoute = Destinations.Library.route,
                currentRoute = Destinations.Recent.route,
                pendingRoute = null,
                targetRoute = Destinations.Library.route
            )
        )
    }

    @Test
    fun `reselect is not replayed to a page that joins composition later`() {
        val requests = MainTabReselectRequests()
        var collectorVisible by mutableStateOf(false)
        var handled = 0
        composeRule.setContent {
            if (collectorVisible) {
                MainTabReselectEffect(requests) { handled++ }
            }
        }

        requests.dispatch()
        composeRule.runOnIdle { collectorVisible = true }
        composeRule.waitForIdle()
        assertEquals(0, handled)

        requests.dispatch()
        composeRule.waitForIdle()
        assertEquals(1, handled)
    }

    @Test
    fun `dispatcher only notifies the reselected tab`() {
        val dispatcher = MainTabReselectDispatcher()
        var homeHandled = 0
        var exploreHandled = 0
        composeRule.setContent {
            MainTabReselectEffect(dispatcher.requestsFor(Destinations.Home.route)) {
                homeHandled++
            }
            MainTabReselectEffect(dispatcher.requestsFor(Destinations.Explore.route)) {
                exploreHandled++
            }
        }
        composeRule.waitForIdle()

        dispatcher.dispatch(Destinations.Home.route)
        composeRule.waitForIdle()

        assertEquals(1, homeHandled)
        assertEquals(0, exploreHandled)
    }

    @Test
    fun `reselect scrolls the list to the top and expands the collapsed top bar`() {
        val requests = MainTabReselectRequests()
        val topAppBarState = TopAppBarState(
            initialHeightOffsetLimit = -120f,
            initialHeightOffset = -120f,
            initialContentOffset = 80f
        )
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 40)
            MainTabReselectEffect(requests) {
                scrollMainTabToTop(topAppBarState) { listState.scrollToTopForReselect() }
            }
            Box(Modifier.size(width = 200.dp, height = 300.dp)) {
                LazyColumn(state = listState) {
                    items(100) { index ->
                        Text("row $index", Modifier.fillMaxWidth().height(48.dp))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(40, listState.firstVisibleItemIndex)

        requests.dispatch()
        composeRule.waitForIdle()

        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
        assertEquals(0f, topAppBarState.heightOffset)
        assertEquals(0f, topAppBarState.contentOffset)
    }

    @Test
    fun `distance to top is exact once the first item is laid out`() {
        assertEquals(
            120f,
            estimateDistanceToTop(
                firstItemOffset = -120,
                firstVisibleIndex = 2,
                firstVisibleScrollOffset = 10,
                itemsPerLine = 1,
                averageLineSize = 999f
            )
        )
        assertEquals(
            0f,
            estimateDistanceToTop(
                firstItemOffset = 0,
                firstVisibleIndex = 0,
                firstVisibleScrollOffset = 0,
                itemsPerLine = 1,
                averageLineSize = 50f
            )
        )
    }

    @Test
    fun `distance to top is estimated per line when the first item is off screen`() {
        assertEquals(
            30f + 4 * 100f,
            estimateDistanceToTop(
                firstItemOffset = null,
                firstVisibleIndex = 9,
                firstVisibleScrollOffset = 30,
                itemsPerLine = 2,
                averageLineSize = 100f
            )
        )
    }

    @Test
    fun `frame steps ease out and finish exactly when the duration runs out`() {
        val duration = SCROLL_TO_TOP_DURATION_MS
        val early = scrollToTopFrameStep(1000f, 0f, 16f, duration)
        val late = scrollToTopFrameStep(1000f, duration - 64f, duration - 48f, duration)
        assertTrue(early > late)
        assertEquals(1000f, scrollToTopFrameStep(1000f, duration - 8f, duration + 8f, duration))
        assertEquals(1000f, scrollToTopFrameStep(1000f, duration + 8f, duration + 24f, duration))
    }

    @Test
    fun `uneven item heights still reach the top within the scroll duration`() {
        val requests = MainTabReselectRequests()
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 60)
            MainTabReselectEffect(requests) { listState.scrollToTopForReselect() }
            Box(Modifier.size(width = 200.dp, height = 300.dp)) {
                LazyColumn(state = listState) {
                    items(100) { index ->
                        // 分组卡片与普通行高度相差很大，平均值估算会明显偏离
                        val height = if (index % 5 == 0) 260.dp else 40.dp
                        Text("row $index", Modifier.fillMaxWidth().height(height))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        requests.dispatch()
        composeRule.mainClock.advanceTimeBy(SCROLL_TO_TOP_DURATION_MS.toLong() + 100L)

        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
    }
}
