package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.tab.explore.EXPLORE_NETEASE_SEARCH_TYPE_BAR_TAG
import moe.ouom.neriplayer.ui.screen.tab.explore.EXPLORE_SEARCH_TYPE_BAR_CONTAINER_TAG
import moe.ouom.neriplayer.ui.screen.tab.explore.EXPLORE_YOUTUBE_SEARCH_TYPE_BAR_TAG
import moe.ouom.neriplayer.ui.screen.tab.explore.ExploreSearchTypeBar
import moe.ouom.neriplayer.ui.screen.tab.explore.ExploreSearchPagerEffect
import moe.ouom.neriplayer.ui.screen.tab.explore.exploreSearchSourceDisplayOrder
import moe.ouom.neriplayer.ui.viewmodel.tab.BilibiliExploreSearchType
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseExploreSearchType
import moe.ouom.neriplayer.ui.viewmodel.tab.SearchSource
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeExploreSearchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExploreSearchTypeBarTransitionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun rapidPlatformSwitchKeepsOneContinuousSearchTypeBar() {
        lateinit var source: MutableState<SearchSource?>
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                source = remember { mutableStateOf(SearchSource.NETEASE) }
                MaterialTheme {
                    Box(Modifier.width(600.dp)) {
                        ExploreSearchTypeBar(
                            source = source.value,
                            selectedBilibiliSearchType = BilibiliExploreSearchType.SONG,
                            selectedNeteaseSearchType = NeteaseExploreSearchType.SONG,
                            selectedYouTubeSearchType = YouTubeExploreSearchType.SONG,
                            onNeteaseSearchTypeClick = {},
                            onBilibiliSearchTypeClick = {},
                            onYouTubeSearchTypeClick = {},
                            selectedAlpha = 1f,
                            unselectedAlpha = 1f,
                            borderAlpha = 1f
                        )
                    }
                }
            }

            composeRule.waitForIdle()
            val settledNeteaseHeight = searchTypeBarHeight()
            composeRule.runOnIdle { source.value = SearchSource.YOUTUBE_MUSIC }
            settleTransition()
            val settledYouTubeHeight = searchTypeBarHeight()
            val maximumSettledHeight = maxOf(settledNeteaseHeight, settledYouTubeHeight)
            var maximumInterruptedHeight = 0f

            composeRule.runOnIdle { source.value = SearchSource.NETEASE }
            advanceFrames(3) {
                maximumInterruptedHeight = maxOf(
                    maximumInterruptedHeight,
                    searchTypeBarHeight()
                )
            }
            composeRule.runOnIdle { source.value = SearchSource.YOUTUBE_MUSIC }
            advanceFrames(3) {
                maximumInterruptedHeight = maxOf(
                    maximumInterruptedHeight,
                    searchTypeBarHeight()
                )
            }
            composeRule.runOnIdle { source.value = SearchSource.NETEASE }
            advanceFrames(20) {
                maximumInterruptedHeight = maxOf(
                    maximumInterruptedHeight,
                    searchTypeBarHeight()
                )
            }

            assertTrue(
                "快速切换时筛选条高度不应叠成两个平台的两行: " +
                    "interrupted=$maximumInterruptedHeight settled=$maximumSettledHeight",
                maximumInterruptedHeight <= maximumSettledHeight + HEIGHT_TOLERANCE_PX
            )
            settleTransition()
            composeRule.onAllNodesWithTag(EXPLORE_NETEASE_SEARCH_TYPE_BAR_TAG)
                .assertCountEquals(1)
            composeRule.onAllNodesWithTag(EXPLORE_YOUTUBE_SEARCH_TYPE_BAR_TAG)
                .assertCountEquals(0)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun bilibiliSearchChipsSelectSongsVideosAndCreators() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selections = mutableListOf<BilibiliExploreSearchType>()
        composeRule.setContent {
            val selected = remember { mutableStateOf(BilibiliExploreSearchType.SONG) }
            MaterialTheme {
                ExploreSearchTypeBar(
                    source = SearchSource.BILIBILI,
                    selectedBilibiliSearchType = selected.value,
                    selectedNeteaseSearchType = NeteaseExploreSearchType.SONG,
                    selectedYouTubeSearchType = YouTubeExploreSearchType.SONG,
                    onBilibiliSearchTypeClick = {
                        selected.value = it
                        selections.add(it)
                    },
                    onNeteaseSearchTypeClick = {},
                    onYouTubeSearchTypeClick = {},
                    selectedAlpha = 1f,
                    unselectedAlpha = 1f,
                    borderAlpha = 1f
                )
            }
        }

        listOf(
            CoreCommonR.string.explore_search_type_video,
            CoreCommonR.string.explore_search_type_creator,
            CoreCommonR.string.explore_search_type_song
        ).forEach { label -> composeRule.onNodeWithText(context.getString(label)).performClick() }

        composeRule.runOnIdle {
            assertEquals(
                listOf(BilibiliExploreSearchType.VIDEO, BilibiliExploreSearchType.CREATOR, BilibiliExploreSearchType.SONG),
                selections
            )
        }
    }

    @Test
    fun restoringAnInternationalSearchKeepsItsSourceUntilTheUserChangesPage() {
        val selected = mutableStateOf(SearchSource.BILIBILI)
        val changes = mutableListOf<SearchSource>()
        lateinit var international: MutableState<Boolean>
        lateinit var showPager: MutableState<Boolean>
        lateinit var pager: PagerState
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            international = remember { mutableStateOf(false) }
            showPager = remember { mutableStateOf(false) }
            val sources = exploreSearchSourceDisplayOrder(international.value, youtubeEnabled = true)
            pager = rememberPagerState(initialPage = sources.indexOf(selected.value)) { sources.size }
            val scope = rememberCoroutineScope()
            ExploreSearchPagerEffect(pager, sources, selected.value) { source ->
                changes.add(source)
                selected.value = source
            }
            if (showPager.value) {
                Column {
                    Text("YouTube", Modifier.clickable {
                        scope.launch { pager.animateScrollToPage(sources.indexOf(SearchSource.YOUTUBE_MUSIC)) }
                    })
                    HorizontalPager(pager, Modifier.size(240.dp).testTag("source_pager")) { page ->
                        Text(sources[page].name)
                    }
                }
            }
        }

        composeRule.runOnIdle {
            international.value = true
            showPager.value = true
        }
        composeRule.runOnIdle { assertEquals(2, pager.settledPage) }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle {
            // the saved page still points to the international ordering until the first layout
            assertEquals(2, pager.currentPage)
            assertTrue(changes.isEmpty())
            showPager.value = true
        }
        composeRule.runOnIdle {
            assertEquals(1, pager.settledPage)
            international.value = true
        }
        composeRule.runOnIdle {
            assertEquals(2, pager.settledPage)
            assertTrue(changes.isEmpty())
        }

        composeRule.onNodeWithTag("source_pager").performTouchInput { swipeRight() }
        composeRule.runOnIdle { assertEquals(SearchSource.NETEASE, selected.value) }
        composeRule.onNodeWithText("YouTube").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(SearchSource.NETEASE, SearchSource.YOUTUBE_MUSIC), changes)
            assertEquals(0, pager.settledPage)
        }
    }

    private fun advanceFrames(count: Int, afterFrame: () -> Unit) {
        repeat(count) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            afterFrame()
        }
    }

    private fun settleTransition() {
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    private fun searchTypeBarHeight(): Float {
        return composeRule.onNodeWithTag(EXPLORE_SEARCH_TYPE_BAR_CONTAINER_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
            .height
    }

    private companion object {
        const val HEIGHT_TOLERANCE_PX = 1f
    }
}
