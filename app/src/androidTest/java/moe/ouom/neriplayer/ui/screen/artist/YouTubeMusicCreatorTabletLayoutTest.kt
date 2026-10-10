package moe.ouom.neriplayer.ui.screen.artist

import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorBrowseEndpoint
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorDetail
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorHeader
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemType
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.artist.YouTubeMusicCreatorDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.artist.YouTubeMusicCreatorItemsUiState
import moe.ouom.neriplayer.ui.viewmodel.artist.toCreatorSongItem
import moe.ouom.neriplayer.ui.viewmodel.artist.youtubeMusicCreatorSectionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class YouTubeMusicCreatorTabletLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val viewport = mutableStateOf(DpSize(1280.dp, 800.dp))
    private lateinit var worksList: LazyListState
    private var playbackSongs: List<SongItem> = emptyList()
    private var playbackIndex = -1
    private var sectionPlayback: Pair<YouTubeMusicCreatorSection, YouTubeMusicCreatorItem>? = null
    private var moreSection: YouTubeMusicCreatorSection? = null
    private var selectedItem: YouTubeMusicCreatorItem? = null
    private var retryCount = 0
    private var loadMoreCount = 0

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun tabletLandscapeKeepsProfileBesideScrollableWorks() {
        renderDetail(1280.dp, 800.dp)
        assertSplitGeometry()
        followNode().assertIsDisplayed().assertIsEnabled()
        assertInside(unclippedBounds(followNode()), bounds("creatorDetailProfile"))
        capture("youtube-creator-landscape")
    }

    @Test
    fun tabletPortraitUsesTwoPanesAndExposesFollowBeforeTheDescription() {
        renderDetail(800.dp, 1100.dp)
        assertSplitGeometry()
        followNode().assertIsDisplayed().assertIsEnabled()
        assertInside(unclippedBounds(followNode()), bounds("creatorDetailProfile"))
        capture("youtube-creator-portrait")
    }

    @Test
    fun actualSixHundredDpTabletWithLargeFontKeepsBothPanesAndFollowReachable() {
        renderDetail(600.dp, 1000.dp, smallestWidthDp = 600, fontScale = 1.3f)
        assertSplitGeometry()
        followNode().performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertInside(unclippedBounds(followNode()), bounds("creatorDetailProfile"))
        composeRule.onNodeWithTag("youtubeCreatorWorksList").performScrollToNode(hasText("Song 45"))
        composeRule.onNodeWithText("Song 45").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(0, playbackIndex)
            assertEquals("Song 45", playbackSongs.single().name)
        }
        capture("youtube-creator-600-large-font")
    }

    @Test
    fun narrowTabletWindowFallsBackToOneScrollableColumn() {
        renderDetail(540.dp, 900.dp, smallestWidthDp = 600, fontScale = 1.3f)
        composeRule.onNodeWithTag("creatorDetailSplitLayout").assertDoesNotExist()
        composeRule.onNodeWithTag("youtubeCreatorTabletProfile").assertDoesNotExist()
        followNode().performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertInside(unclippedBounds(followNode()), bounds("youtubeCreatorWorksList"))
        composeRule.onNodeWithTag("youtubeCreatorWorksList").performScrollToNode(hasText("Song 45"))
        composeRule.onNodeWithText("Song 45").assertIsDisplayed()
        capture("youtube-creator-narrow-window")
    }

    @Test
    fun widePhoneRetainsTheSingleColumnHeader() {
        renderDetail(840.dp, 360.dp, smallestWidthDp = 360)
        composeRule.onNodeWithTag("creatorDetailSplitLayout").assertDoesNotExist()
        composeRule.onNodeWithTag("youtubeCreatorTabletProfile").assertDoesNotExist()
        followNode().performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("youtubeCreatorWorksList").performScrollToNode(hasText("Song 12"))
        composeRule.onNodeWithText("Song 12").assertIsDisplayed()
    }

    @Test
    fun profileAndWorksScrollWithoutMovingEachOthersContent() {
        renderDetail(1280.dp, 480.dp, fontScale = 1.3f)
        val worksBounds = bounds("youtubeCreatorWorksList")
        val initialWorksPosition = composeRule.runOnIdle { worksList.firstVisibleItemIndex to worksList.firstVisibleItemScrollOffset }

        followNode().performScrollTo().assertIsDisplayed().assertIsEnabled()
        val profileOffset = profileScrollOffset()
        assertTrue("短窗口应实际滚动简介以露出关注操作", profileOffset > 0f)
        assertInside(unclippedBounds(followNode()), bounds("creatorDetailProfile"))
        assertEquals(worksBounds, bounds("youtubeCreatorWorksList"))
        composeRule.runOnIdle {
            assertEquals(initialWorksPosition, worksList.firstVisibleItemIndex to worksList.firstVisibleItemScrollOffset)
        }

        composeRule.onNodeWithTag("youtubeCreatorWorksList").performScrollToNode(hasText("Song 45"))
        composeRule.onNodeWithText("Song 45").assertIsDisplayed()
        assertEquals(profileOffset, profileScrollOffset(), 0f)
        followNode().assertIsDisplayed()
        capture("youtube-creator-independent-scroll")
    }

    @Test
    fun resizingAcrossTabletAndNarrowWindowKeepsTheCurrentWorkAndListIndex() {
        renderDetail(1280.dp, 800.dp)
        composeRule.onNodeWithTag("youtubeCreatorWorksList").performScrollToNode(hasText("Song 45"))
        val index = composeRule.runOnIdle { worksList.firstVisibleItemIndex }
        assertTrue(index > 0)

        for (size in listOf(DpSize(800.dp, 1100.dp), DpSize(540.dp, 900.dp), DpSize(1280.dp, 800.dp))) {
            composeRule.runOnIdle { viewport.value = size }
            composeRule.onNodeWithText("Song 45").assertIsDisplayed()
            composeRule.runOnIdle { assertEquals("header placeholder 不应改变作品索引", index, worksList.firstVisibleItemIndex) }
            if (size.width >= 600.dp) composeRule.onNodeWithTag("creatorDetailSplitLayout").assertIsDisplayed()
            else composeRule.onNodeWithTag("creatorDetailSplitLayout").assertDoesNotExist()
        }
        capture("youtube-creator-resized")
    }

    @Test
    fun localSectionPlaybackKeepsQueueIndexAndPagedSectionKeepsItsEndpoint() {
        val local = YouTubeMusicCreatorSection("Local queue", (0..2).map(::song))
        val endpoint = YouTubeMusicCreatorBrowseEndpoint("fixture-more-browse", "fixture-params")
        val paged = YouTubeMusicCreatorSection("Paged queue", (10..12).map(::song), endpoint)
        renderDetail(1280.dp, 800.dp, state = mutableStateOf(detailState(listOf(local, paged))))

        composeRule.onNodeWithText("Song 1").performClick()
        composeRule.runOnIdle {
            assertEquals(local.items.mapNotNull { it.toCreatorSongItem() }, playbackSongs)
            assertEquals(1, playbackIndex)
        }
        composeRule.onNodeWithText("Song 11").performClick()
        composeRule.runOnIdle {
            assertEquals(paged to paged.items[1], sectionPlayback)
            assertEquals(endpoint, sectionPlayback?.first?.moreEndpoint)
            assertEquals(1, playbackIndex)
        }
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.youtube_creator_view_all, paged.title)).performClick()
        composeRule.runOnIdle { assertEquals(paged, moreSection) }
    }

    @Test
    fun sectionQueueLoadingDisablesOnlyItsRowsAndDetailErrorCanRetry() {
        val local = YouTubeMusicCreatorSection("Local queue", (0..2).map(::song))
        val paged = YouTubeMusicCreatorSection("Paged queue", (10..12).map(::song), YouTubeMusicCreatorBrowseEndpoint("fixture-more"))
        val state = mutableStateOf(detailState(listOf(local, paged)))
        renderDetail(1280.dp, 800.dp, state = state)
        composeRule.runOnIdle { state.value = state.value.copy(playbackQueueLoadingSectionKey = youtubeMusicCreatorSectionKey(paged)) }
        composeRule.onNodeWithText("Song 11").assertIsNotEnabled()
        composeRule.onNodeWithText("Song 1").assertIsEnabled()
        composeRule.runOnIdle {
            assertEquals(null, sectionPlayback)
            state.value = state.value.copy(playbackQueueLoadingSectionKey = null, error = "fixture detail error")
        }
        composeRule.onNodeWithText("Song 11").assertIsEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_retry)).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, retryCount) }
    }

    @Test
    fun tabletItemsRemainBoundedAcrossWidthsAndReturnTheExactSelectedItem() {
        val items = (0..3).map(::song)
        renderItems(1280.dp, 800.dp, mutableStateOf(itemsState(items)), fontScale = 1.3f)
        val frame = bounds(FIXTURE_ROOT)
        val pane = bounds("youtubeCreatorItemsPane")
        assertTrue(pane.width <= frame.width / 1280f * 1000f + 1f)
        assertInside(bounds("youtubeCreatorItemsList"), frame)
        composeRule.onNodeWithText("Song 2").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(items[2], selectedItem) }
        capture("youtube-creator-items-landscape")

        for (size in listOf(DpSize(800.dp, 1100.dp), DpSize(600.dp, 1000.dp), DpSize(540.dp, 900.dp))) {
            composeRule.runOnIdle { viewport.value = size }
            val list = bounds("youtubeCreatorItemsList")
            assertInside(list, bounds(FIXTURE_ROOT))
            composeRule.onNodeWithText("Song 2").assertIsDisplayed()
            if (size.width >= 600.dp) assertTrue(list.left > bounds(FIXTURE_ROOT).left)
            capture("youtube-creator-items-${size.width.value.toInt()}")
        }
    }

    @Test
    fun itemsQueueAndPaginationLoadingDisableRowsAndMoreUntilReady() {
        val state = mutableStateOf(itemsState((0..2).map(::song)).copy(playbackQueueLoading = true))
        renderItems(800.dp, 1000.dp, state)
        composeRule.onNodeWithText("Song 1").assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.youtube_creator_load_more)).assertIsNotEnabled()
        composeRule.runOnIdle { state.value = state.value.copy(playbackQueueLoading = false, loadingMore = true) }
        composeRule.onNodeWithText("Song 1").assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.youtube_creator_load_more)).assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(null, selectedItem)
            assertEquals(0, loadMoreCount)
            state.value = state.value.copy(loadingMore = false)
        }
        composeRule.onNodeWithText("Song 1").assertIsEnabled().performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.youtube_creator_load_more)).assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(state.value.items[1], selectedItem)
            assertEquals(1, loadMoreCount)
        }
    }

    @Test
    fun initialItemsErrorAndPaginationErrorUseTheirOwnRetryCallbacks() {
        val state = mutableStateOf(YouTubeMusicCreatorItemsUiState())
        renderItems(800.dp, 1000.dp, state)
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        composeRule.onNodeWithText(string(CoreCommonR.string.youtube_creator_load_more)).assertDoesNotExist()
        composeRule.runOnIdle { state.value = state.value.copy(loading = false, error = "fixture initial error") }
        composeRule.onNodeWithText("fixture initial error").assertIsDisplayed()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_retry)).assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, retryCount)
            assertEquals(0, loadMoreCount)
            state.value = itemsState((0..2).map(::song)).copy(continuation = null, loadMoreError = "fixture pagination error")
        }
        composeRule.onNodeWithText("fixture pagination error").assertIsDisplayed()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_retry)).assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, retryCount)
            assertEquals(1, loadMoreCount)
        }
        capture("youtube-creator-items-retry")
    }

    private fun renderDetail(
        width: Dp,
        height: Dp,
        smallestWidthDp: Int = 600,
        fontScale: Float = 1f,
        state: MutableState<YouTubeMusicCreatorDetailUiState> = mutableStateOf(detailState())
    ) {
        render(width, height, smallestWidthDp, fontScale) {
            worksList = rememberLazyListState()
            YouTubeMusicCreatorDetailContent(
                uiState = state.value, listState = worksList, creatorBrowseId = FIXTURE_CREATOR.browseId,
                sectionStateHolder = rememberSaveableStateHolder(), miniPlayerHeight = MINI_HEIGHT,
                offlineMode = true, onRetry = { retryCount += 1 },
                onSongClick = { songs, index -> playbackSongs = songs; playbackIndex = index },
                onSectionSongClick = { section, item -> sectionPlayback = section to item },
                onPlaylistClick = {}, onCreatorClick = {}, onSectionMoreClick = { moreSection = it },
                isTabletLayout = LocalConfiguration.current.smallestScreenWidthDp >= 600,
                fallbackCreator = FIXTURE_CREATOR,
                followFavorite = createYouTubeMusicCreatorFavorite(FIXTURE_CREATOR, state.value.detail?.header)
            )
        }
    }

    private fun renderItems(
        width: Dp,
        height: Dp,
        state: MutableState<YouTubeMusicCreatorItemsUiState>,
        fontScale: Float = 1f
    ) {
        render(width, height, 600, fontScale) {
            worksList = rememberLazyListState()
            YouTubeMusicCreatorItemsContent(
                uiState = state.value, listState = worksList, onRetry = { retryCount += 1 },
                onLoadMore = { loadMoreCount += 1 }, onSongClick = { selectedItem = it },
                offlineMode = true, isTabletLayout = LocalConfiguration.current.smallestScreenWidthDp >= 600
            )
        }
    }

    private fun render(width: Dp, height: Dp, smallestWidthDp: Int, fontScale: Float, content: @Composable () -> Unit) {
        viewport.value = DpSize(width, height)
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val size = viewport.value
                    val baseDensity = LocalDensity.current
                    val scale = minOf(maxWidth.value / size.width.value, maxHeight.value / size.height.value)
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        this.smallestScreenWidthDp = smallestWidthDp
                        screenWidthDp = size.width.value.toInt()
                        screenHeightDp = size.height.value.toInt()
                        orientation = if (size.width > size.height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    }
                    CompositionLocalProvider(
                        LocalDensity provides Density(baseDensity.density * scale, fontScale),
                        LocalConfiguration provides configuration,
                        LocalMiniPlayerHeight provides MINI_HEIGHT
                    ) {
                        Box(Modifier.requiredSize(size).background(MaterialTheme.colorScheme.background).testTag(FIXTURE_ROOT)) {
                            content()
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertSplitGeometry() {
        composeRule.onNodeWithTag("creatorDetailSplitLayout").assertIsDisplayed()
        val profile = bounds("creatorDetailProfile")
        val works = bounds("creatorDetailWorks")
        assertTrue("作品应位于简介右侧", profile.right < works.left)
        assertTrue("作品应获得更多横向空间", works.width > profile.width)
        assertInside(profile, bounds(FIXTURE_ROOT))
        assertInside(works, bounds(FIXTURE_ROOT))
        assertTrue("作品不能延伸到迷你播放器预留内", works.bottom <= bounds(FIXTURE_ROOT).bottom - pixels(MINI_HEIGHT) + 1f)
    }

    private fun followNode() = composeRule.onNode(hasText(string(CoreCommonR.string.artist_follow)) or hasText(string(CoreCommonR.string.artist_followed)))

    private fun profileScrollOffset(): Float = composeRule.onNodeWithTag("creatorDetailProfile")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun unclippedBounds(node: SemanticsNodeInteraction): Rect {
        val semantics = node.fetchSemanticsNode()
        val position = semantics.positionInRoot
        return Rect(position.x, position.y, position.x + semantics.size.width, position.y + semantics.size.height)
    }

    private fun pixels(value: Dp): Float = bounds(FIXTURE_ROOT).width / viewport.value.width.value * value.value

    private fun assertInside(child: Rect, parent: Rect) {
        assertTrue(child.left >= parent.left - 1f && child.right <= parent.right + 1f)
        assertTrue(child.top >= parent.top - 1f && child.bottom <= parent.bottom + 1f)
    }

    private fun string(resourceId: Int, vararg formatArgs: Any): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return if (formatArgs.isEmpty()) context.getString(resourceId) else context.getString(resourceId, *formatArgs)
    }

    private fun capture(stage: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(60)?.takeIf(String::isNotBlank) ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(checkNotNull(context.getExternalFilesDir(null)), "$prefix-$stage.png")
        val bitmap = composeRule.onNodeWithTag(FIXTURE_ROOT).captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        Log.i("YouTubeCreatorTabletTest", "screenshot=${file.absolutePath}")
    }

    private fun detailState(sections: List<YouTubeMusicCreatorSection> = (0..60).map { index ->
        YouTubeMusicCreatorSection("Section $index", listOf(song(index)))
    }) = YouTubeMusicCreatorDetailUiState(
        loading = false,
        detail = YouTubeMusicCreatorDetail(
            header = YouTubeMusicCreatorHeader(
                browseId = FIXTURE_CREATOR.browseId, title = FIXTURE_CREATOR.title,
                subtitle = "A synthetic artist subtitle with enough detail to span several lines on a narrow tablet",
                coverUrl = "", description = List(12) { "Fixture biography for layout and independent scrolling." }.joinToString(" "),
                subscriberCountText = "Fixture subscribers", monthlyListenerCountText = "Fixture monthly listeners"
            ),
            sections = sections
        )
    )

    private fun itemsState(items: List<YouTubeMusicCreatorItem>) = YouTubeMusicCreatorItemsUiState(
        loading = false, title = "Fixture works", items = items, continuation = "fixture-next-page"
    )

    private fun song(index: Int) = YouTubeMusicCreatorItem(
        type = YouTubeMusicCreatorItemType.Song, title = "Song $index", subtitle = "Fixture artist",
        coverUrl = "", videoId = "fixture-video-$index", artist = "Fixture artist", album = "Fixture album", durationMs = 180_000L
    )
}

private const val FIXTURE_ROOT = "youtubeCreatorTabletFixture"
private val MINI_HEIGHT = 72.dp
private val FIXTURE_CREATOR = YouTubeMusicCreatorSummary(
    browseId = "UCsyntheticTabletLayoutOnly",
    title = "Fixture creator with a deliberately long name for tablet portrait layout",
    subtitle = "Fixture artist",
    coverUrl = ""
)
