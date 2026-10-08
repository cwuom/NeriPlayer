package moe.ouom.neriplayer.ui.screen.artist

import android.app.Application
import android.content.Context
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.data.model.bilibili.uploader.UploaderContent
import moe.ouom.neriplayer.data.model.bilibili.uploader.UploaderContentKind
import moe.ouom.neriplayer.data.model.bilibili.uploader.UploaderVideo
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.ui.viewmodel.artist.BiliUploaderDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.artist.BiliUploaderHeader
import moe.ouom.neriplayer.util.format.formatDurationSec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h1800dp")
class BiliUploaderContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val uploader = BiliUploaderSummary(mid = 42L, name = "Summary Uploader", avatarUrl = "summary-avatar")

    private val header = BiliUploaderHeader(
        mid = 42L,
        name = "Uploader Name",
        avatarUrl = "https://example.invalid/face.jpg",
        sign = "Uploader signature",
        bannerUrl = "https://example.invalid/banner.jpg"
    )

    private val videos = listOf(
        video("BV1", "First video", durationSec = 125, play = 12L),
        video("BV2", "Second video", durationSec = 0, play = null)
    )

    private val collections = listOf(
        content(1L, "Collection described", UploaderContentKind.COLLECTION, description = "Collection description"),
        content(2L, "Collection counted", UploaderContentKind.COLLECTION, description = " ", total = 3)
    )

    private val series = listOf(content(3L, "Series one", UploaderContentKind.SERIES, description = "Series description"))

    private val events = mutableListOf<String>()

    @Before
    fun allowUserDatabaseInTestProcess() {
        context.applicationInfo.processName = Application.getProcessName()
    }

    @Test
    fun `phone layout shows the uploader card and switches between work tabs`() {
        var ui by mutableStateOf(
            BiliUploaderDetailUiState(
                loading = false,
                header = header,
                videos = videos,
                collections = collections,
                series = series,
                videosHasMore = true,
                contentsHasMore = true,
                contentsLoadingMore = true
            )
        )
        setContent(tabletDevice = false) { ui }

        composeRule.onNodeWithText("Uploader Name").assertExists()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.bili_uploader_mid, 42L)).assertExists()
        composeRule.onNodeWithText("Uploader signature").assertExists()
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.bili_uploader_video_count, 2)).assertExists()
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.bili_uploader_collection_count, 2)).assertExists()
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.bili_uploader_series_count, 1)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_follow)).performClick()
        waitForText(string(CoreCommonR.string.artist_followed))
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_followed)).performClick()
        waitForText(string(CoreCommonR.string.artist_follow))
        composeRule.onNodeWithText(formatDurationSec(125), substring = true).assertExists()
        composeRule.onNodeWithText(formatDurationSec(0), substring = true).assertExists()

        composeRule.onNodeWithText("Second video").performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.bili_uploader_load_more)).performClick()
        assertEquals(listOf("video:BV2/1", "moreVideos"), events)

        composeRule.onNodeWithText(string(CoreCommonR.string.bili_uploader_tab_collections)).performClick()
        composeRule.onNodeWithText("Collection description").assertExists()
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.bili_uploader_content_count, 3)).assertExists()
        composeRule.onNodeWithText("Collection counted").performClick()
        composeRule.onNode(hasText(string(CoreCommonR.string.bili_uploader_load_more)) and hasClickAction())
            .assertIsNotEnabled()
        assertEquals("content:2", events.last())

        composeRule.onNodeWithText(string(CoreCommonR.string.bili_uploader_tab_series)).performClick()
        composeRule.onNodeWithText("Series description").assertExists()
        composeRule.onNodeWithText("Series one").performClick()
        assertEquals("content:3", events.last())

        ui = ui.copy(series = emptyList(), contentsHasMore = false)
        composeRule.onNodeWithText(string(CoreCommonR.string.bili_uploader_series_empty)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.bili_uploader_tab_collections)).performClick()
        ui = ui.copy(collections = emptyList())
        composeRule.onNodeWithText(string(CoreCommonR.string.bili_uploader_collections_empty)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.bili_uploader_tab_videos)).performClick()
        ui = ui.copy(videos = emptyList(), header = header.copy(sign = "", bannerUrl = "", avatarUrl = ""))
        composeRule.onNodeWithText(string(CoreCommonR.string.bili_uploader_videos_empty)).assertExists()
        composeRule.onNodeWithText("Uploader signature").assertDoesNotExist()
    }

    @Test
    fun `phone layout reports loading and errors`() {
        var ui by mutableStateOf(BiliUploaderDetailUiState(loading = true))
        setContent(tabletDevice = false) { ui }

        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_loading_content)).assertExists()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.bili_uploader_mid, 0L)).assertExists()

        ui = BiliUploaderDetailUiState(loading = false, error = "Uploader offline")
        composeRule.onNodeWithText(string(CoreCommonR.string.action_retry)).performClick()
        assertEquals(listOf("retry"), events)

        ui = BiliUploaderDetailUiState(loading = false, error = "Partial failure", header = header, videos = videos.take(1))
        composeRule.onNodeWithText("Partial failure").assertExists()
        composeRule.onNodeWithText("First video").assertExists()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `tablet layout keeps the profile beside the tabs`() {
        var ui by mutableStateOf(
            BiliUploaderDetailUiState(loading = false, header = header, videos = videos)
        )
        setContent(tabletDevice = true) { ui }

        composeRule.onNodeWithText("Uploader Name").assertExists()
        composeRule.onNodeWithText("Uploader signature").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.bili_uploader_tab_videos)).assertExists()
        composeRule.onNodeWithText("First video").performClick()
        assertEquals(listOf("video:BV1/0"), events)

        ui = BiliUploaderDetailUiState(loading = true, header = header)
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_loading_content)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.bili_uploader_tab_videos)).assertDoesNotExist()
    }

    @Test
    fun `follow favorite falls back to the summary for missing or blank header values`() {
        val withoutHeader = createBiliUploaderFavorite(uploader, null)
        assertEquals("Summary Uploader", withoutHeader.name)
        assertEquals("summary-avatar", withoutHeader.coverUrl)
        assertNull(withoutHeader.subtitle)

        val blankHeader = createBiliUploaderFavorite(
            uploader.copy(avatarUrl = ""),
            BiliUploaderHeader(mid = 42L, name = " ", avatarUrl = "", sign = " ", bannerUrl = "")
        )
        assertEquals("Summary Uploader", blankHeader.name)
        assertNull(blankHeader.coverUrl)
        assertNull(blankHeader.subtitle)
    }

    @Test
    fun `follow state matches both the creator id and the favorite source`() {
        val favorite = createBiliUploaderFavorite(uploader, header)

        assertTrue(listOf(favorite.copy(name = "Renamed")).containsCreator(favorite))
        assertFalse(listOf(favorite.copy(id = 43L)).containsCreator(favorite))
        assertFalse(listOf(favorite.copy(source = "other")).containsCreator(favorite))
        assertFalse(emptyList<FavoritePlaylist>().containsCreator(favorite))
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText(text) and hasClickAction() and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun setContent(tabletDevice: Boolean, ui: () -> BiliUploaderDetailUiState) {
        composeRule.setContent {
            var selectedTab by remember { mutableIntStateOf(0) }
            val state = ui()
            MaterialTheme {
                BiliUploaderContent(
                    ui = state,
                    followFavorite = createBiliUploaderFavorite(uploader, state.header),
                    listState = rememberLazyListState(),
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    onRetry = { events += "retry" },
                    onLoadMoreVideos = { events += "moreVideos" },
                    onLoadMoreContents = { events += "moreContents" },
                    onVideoClick = { video, index -> events += "video:${video.bvid}/$index" },
                    onContentClick = { events += "content:${it.id}" },
                    offlineMode = false,
                    tabletDevice = tabletDevice
                )
            }
        }
    }

    private fun string(id: Int): String = context.getString(id)

    private fun plural(id: Int, count: Int): String = context.resources.getQuantityString(id, count, count)

    private fun video(bvid: String, title: String, durationSec: Int, play: Long?) = UploaderVideo(
        aid = 0L,
        bvid = bvid,
        title = title,
        coverUrl = "",
        durationSec = durationSec,
        uploaderMid = 42L,
        uploaderName = "Uploader Name",
        play = play,
        pubdate = null
    )

    private fun content(
        id: Long,
        title: String,
        kind: UploaderContentKind,
        description: String,
        total: Int = 1
    ) = UploaderContent(
        id = id,
        mid = 42L,
        kind = kind,
        title = title,
        coverUrl = "",
        description = description,
        total = total
    )
}
