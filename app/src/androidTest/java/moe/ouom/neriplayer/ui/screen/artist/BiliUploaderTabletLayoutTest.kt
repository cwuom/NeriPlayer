package moe.ouom.neriplayer.ui.screen.artist

import android.content.res.Configuration
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.data.model.bilibili.uploader.UploaderContent
import moe.ouom.neriplayer.data.model.bilibili.uploader.UploaderContentKind
import moe.ouom.neriplayer.data.model.bilibili.uploader.UploaderVideo
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.artist.BiliUploaderDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.artist.BiliUploaderHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiliUploaderTabletLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun landscape_keepsProfileAndTabsFixedWhileVideosScroll() {
        setFixture(width = 1280.dp, height = 800.dp)
        val profile = composeRule.onNodeWithText(UPLOADER_NAME).fetchSemanticsNode().boundsInRoot
        val tabs = composeRule.onNodeWithText(videosLabel).fetchSemanticsNode().boundsInRoot
        val video = composeRule.onNodeWithText("Video 0").fetchSemanticsNode().boundsInRoot
        assertTrue(video.left > profile.right)

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(20)

        composeRule.onNodeWithText(UPLOADER_NAME).assertIsDisplayed()
        assertEquals(profile, composeRule.onNodeWithText(UPLOADER_NAME).fetchSemanticsNode().boundsInRoot)
        assertEquals(tabs, composeRule.onNodeWithText(videosLabel).fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun compactPortrait_largeFontKeepsStatsAndFollowReachable() {
        val initialUi = uploaderFixture()
        val ui = initialUi.copy(
            header = checkNotNull(initialUi.header).copy(
                name = UPLOADER_NAME.repeat(5),
                sign = "A longer creator profile for a compact tablet. ".repeat(6)
            )
        )
        setFixture(width = 600.dp, height = 800.dp, fontScale = 1.5f, ui = ui)
        composeRule.onNodeWithTag("creatorDetailSplitLayout").assertIsDisplayed()
        val profile = composeRule.onNodeWithTag("creatorDetailProfile").fetchSemanticsNode().boundsInRoot
        val stats = listOf(
            quantity(CoreCommonR.plurals.bili_uploader_video_count, ui.videos.size),
            quantity(CoreCommonR.plurals.bili_uploader_collection_count, ui.collections.size),
            quantity(CoreCommonR.plurals.bili_uploader_series_count, ui.series.size)
        )
        stats.forEach { label ->
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            val bounds = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= profile.left && bounds.right <= profile.right)
        }
        composeRule.onNodeWithText(resource(CoreCommonR.string.artist_follow))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(videosLabel).assertIsDisplayed()
        composeRule.onNodeWithText("Video 0").assertIsDisplayed()
    }

    @Test
    fun tabsPreservePositionsPaginationAndOriginalVideoIndex() {
        val ui = uploaderFixture()
        var playedVideo: UploaderVideo? = null
        var playedIndex: Int? = null
        var videoLoads = 0
        var contentLoads = 0
        var openedContent: UploaderContent? = null
        val lists = setFixture(
            width = 800.dp,
            height = 1000.dp,
            ui = ui,
            onVideoClick = { video, index ->
                playedVideo = video
                playedIndex = index
            },
            onContentClick = { openedContent = it },
            onLoadMoreVideos = { videoLoads++ },
            onLoadMoreContents = { contentLoads++ }
        )
        composeRule.onNodeWithText("Video 0").performClick()
        composeRule.runOnIdle {
            assertEquals(ui.videos[0], playedVideo)
            assertEquals(0, playedIndex)
        }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(12)
        val videoPosition = composeRule.runOnIdle { lists.videos.firstVisibleItemIndex }
        composeRule.onNodeWithText(collectionsLabel).performClick()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(10)
        val collectionPosition = composeRule.runOnIdle { lists.collections.firstVisibleItemIndex }
        composeRule.onNodeWithText(seriesLabel).performClick()
        composeRule.onNodeWithText("Series 0").performClick()
        composeRule.runOnIdle { assertEquals(ui.series[0], openedContent) }
        composeRule.onNodeWithText(videosLabel).performClick()
        composeRule.runOnIdle { assertEquals(videoPosition, lists.videos.firstVisibleItemIndex) }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(ui.videos.size + 2)
        composeRule.onNodeWithText(loadMoreLabel).performClick()
        composeRule.onNodeWithText(collectionsLabel).performClick()
        composeRule.runOnIdle { assertEquals(collectionPosition, lists.collections.firstVisibleItemIndex) }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(ui.collections.size + 2)
        composeRule.onNodeWithText(loadMoreLabel).performClick()
        composeRule.runOnIdle {
            assertEquals(1, videoLoads)
            assertEquals(1, contentLoads)
        }
    }

    @Test
    fun narrowWindowRoundTripKeepsSameVideoPosition() {
        val width = mutableStateOf(800.dp)
        val lists = setFixture(width = width.value, height = 1000.dp, windowWidth = width)
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
        val initialPosition = composeRule.runOnIdle { lists.videos.firstVisibleItemIndex }
        composeRule.runOnIdle { width.value = 500.dp }
        composeRule.onNodeWithTag("creatorDetailSplitLayout").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(initialPosition, lists.videos.firstVisibleItemIndex) }
        composeRule.runOnIdle { width.value = 800.dp }
        composeRule.onNodeWithTag("creatorDetailSplitLayout").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(initialPosition, lists.videos.firstVisibleItemIndex) }
    }

    @Test
    fun initialErrorKeepsRetryInWorksPane() {
        var retryRequests = 0
        setFixture(
            width = 600.dp,
            height = 800.dp,
            ui = BiliUploaderDetailUiState(
                loading = false,
                error = "fixture load failure",
                header = BiliUploaderHeader(UPLOADER_MID, UPLOADER_NAME, "", "", "")
            ),
            onRetry = { retryRequests++ }
        )
        val profile = composeRule.onNodeWithText(UPLOADER_NAME).fetchSemanticsNode().boundsInRoot
        val retry = composeRule.onNodeWithText(resource(CoreCommonR.string.action_retry))
        retry.assertIsDisplayed()
        assertTrue(retry.fetchSemanticsNode().boundsInRoot.left > profile.right)
        retry.performClick()
        composeRule.runOnIdle { assertEquals(1, retryRequests) }
    }

    private fun setFixture(
        width: Dp,
        height: Dp,
        fontScale: Float = 1f,
        ui: BiliUploaderDetailUiState = uploaderFixture(),
        windowWidth: MutableState<Dp>? = null,
        onRetry: () -> Unit = {},
        onVideoClick: (UploaderVideo, Int) -> Unit = { _, _ -> },
        onContentClick: (UploaderContent) -> Unit = {},
        onLoadMoreVideos: () -> Unit = {},
        onLoadMoreContents: () -> Unit = {}
    ): UploaderFixtureLists {
        val lists = UploaderFixtureLists()
        composeRule.setContent {
            MaterialTheme {
                val actualWidth = windowWidth?.value ?: width
                val baseDensity = LocalDensity.current
                val configuration = Configuration(LocalConfiguration.current).apply {
                    smallestScreenWidthDp = 600
                    screenWidthDp = actualWidth.value.toInt()
                    screenHeightDp = height.value.toInt()
                }
                CompositionLocalProvider(
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(baseDensity.density * 0.5f, fontScale),
                    LocalMiniPlayerHeight provides 0.dp
                ) {
                    var selectedTab by remember { mutableIntStateOf(0) }
                    FittedTestViewport(actualWidth, height, fontScale = fontScale) {
                        BiliUploaderContent(
                            ui = ui,
                            followFavorite = createBiliUploaderFavorite(
                                BiliUploaderSummary(UPLOADER_MID, UPLOADER_NAME, ""), ui.header
                            ),
                            listState = when (selectedTab) {
                                0 -> lists.videos
                                1 -> lists.collections
                                else -> lists.series
                            },
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it },
                            onRetry = onRetry,
                            onLoadMoreVideos = onLoadMoreVideos,
                            onLoadMoreContents = onLoadMoreContents,
                            onVideoClick = onVideoClick,
                            onContentClick = onContentClick,
                            offlineMode = true,
                            tabletDevice = true
                        )
                    }
                }
            }
        }
        return lists
    }

    private val videosLabel get() = resource(CoreCommonR.string.bili_uploader_tab_videos)
    private val collectionsLabel get() = resource(CoreCommonR.string.bili_uploader_tab_collections)
    private val seriesLabel get() = resource(CoreCommonR.string.bili_uploader_tab_series)
    private val loadMoreLabel get() = resource(CoreCommonR.string.bili_uploader_load_more)
    private fun resource(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun quantity(id: Int, count: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.getQuantityString(id, count, count)
}

private const val UPLOADER_MID = 9_009_001L
private const val UPLOADER_NAME = "Tablet Uploader"

private class UploaderFixtureLists {
    val videos = LazyListState()
    val collections = LazyListState()
    val series = LazyListState()
}

private fun uploaderFixture(): BiliUploaderDetailUiState = BiliUploaderDetailUiState(
    loading = false,
    header = BiliUploaderHeader(UPLOADER_MID, UPLOADER_NAME, "", "Creator profile", ""),
    videos = List(40) { index ->
        UploaderVideo(index.toLong(), "BVfixture$index", "Video $index", "", 120, UPLOADER_MID, UPLOADER_NAME, 100, 0)
    },
    collections = uploaderContents(UploaderContentKind.COLLECTION, "Collection"),
    series = uploaderContents(UploaderContentKind.SERIES, "Series"),
    videosHasMore = true,
    contentsHasMore = true
)

private fun uploaderContents(kind: UploaderContentKind, prefix: String): List<UploaderContent> =
    List(24) { index -> UploaderContent(index.toLong(), UPLOADER_MID, kind, "$prefix $index", "", "", 4) }
