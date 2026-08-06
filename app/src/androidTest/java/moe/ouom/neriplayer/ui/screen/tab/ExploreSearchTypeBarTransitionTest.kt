package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseExploreSearchType
import moe.ouom.neriplayer.ui.viewmodel.tab.SearchSource
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeExploreSearchType
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
                            selectedNeteaseSearchType = NeteaseExploreSearchType.SONG,
                            selectedYouTubeSearchType = YouTubeExploreSearchType.SONG,
                            onNeteaseSearchTypeClick = {},
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
