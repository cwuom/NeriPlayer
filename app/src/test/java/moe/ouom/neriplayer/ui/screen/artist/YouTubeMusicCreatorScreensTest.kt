package moe.ouom.neriplayer.ui.screen.artist

import android.app.Application
import android.content.Context
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.crash.ExceptionHandler
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaCovers
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaDownloads
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorBrowseEndpoint
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorDetail
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorHeader
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemType
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.ui.viewmodel.artist.YouTubeMusicCreatorDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.artist.YouTubeMusicCreatorDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.artist.YouTubeMusicCreatorItemsUiState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h2400dp")
class YouTubeMusicCreatorScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val creator = YouTubeMusicCreatorSummary(
        browseId = "UCcreator",
        title = "Creator Summary",
        subtitle = "Summary subtitle",
        coverUrl = ""
    )

    private val header = YouTubeMusicCreatorHeader(
        browseId = "UCcreator",
        title = "Creator Loaded",
        subtitle = "Artist channel",
        coverUrl = "https://example.invalid/creator.jpg",
        description = "About the creator",
        subscriberCountText = "1M subscribers",
        monthlyListenerCountText = "2M monthly"
    )

    private val topSongs = YouTubeMusicCreatorSection(
        title = "Top songs",
        items = listOf(
            song("v1", "First hit", durationMs = 61_000L),
            song("v2", "Second hit", durationMs = 0L)
        )
    )

    private val singles = YouTubeMusicCreatorSection(
        title = "Singles",
        items = listOf(song("v4", "Single one", durationMs = 0L)),
        moreEndpoint = YouTubeMusicCreatorBrowseEndpoint(browseId = "UCcreator", params = "singles")
    )

    private val albums = YouTubeMusicCreatorSection(
        title = "Albums",
        items = listOf(
            YouTubeMusicCreatorItem(
                YouTubeMusicCreatorItemType.Album,
                "Album card",
                "2024",
                "https://example.invalid/album.jpg",
                browseId = "MPREalbum"
            ),
            YouTubeMusicCreatorItem(YouTubeMusicCreatorItemType.Playlist, "Playlist card", "", "", browseId = "VLlist"),
            YouTubeMusicCreatorItem(YouTubeMusicCreatorItemType.Creator, "Related creator", "", "", browseId = "UCother"),
            YouTubeMusicCreatorItem(YouTubeMusicCreatorItemType.Video, "Video card", "", "", videoId = "v3")
        )
    )

    private val events = mutableListOf<String>()

    private var followFavorite by mutableStateOf<FavoritePlaylist?>(createYouTubeMusicCreatorFavorite(creator, header))

    @Before
    fun setUp() {
        context.applicationInfo.processName = Application.getProcessName()
        LocalMediaHostAccess.bind(
            downloads = AndroidLocalMediaDownloads,
            covers = AndroidLocalMediaCovers,
            crashLogs = CrashLogCleanup(ExceptionHandler::clearCrashLogs)
        )
    }

    @Test
    fun `phone screen loads the creator header and routes section actions`() {
        val detail = YouTubeMusicCreatorDetail(header = header, sections = listOf(topSongs, singles, albums))
        setScreen { detail }

        waitForText("Top songs")
        composeRule.onAllNodesWithText("Creator Loaded").assertCountEquals(2)
        composeRule.onNodeWithText("Artist channel").assertExists()
        composeRule.onNodeWithText("2M monthly").assertExists()
        composeRule.onNodeWithText("1M subscribers").assertExists()
        composeRule.onNodeWithText("About the creator").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_follow)).assertExists()

        composeRule.onNodeWithText("Second hit").performClick()
        composeRule.onNodeWithContentDescription(
            context.getString(CoreCommonR.string.youtube_creator_view_all, "Singles")
        ).performClick()
        composeRule.onNodeWithContentDescription("Album card").assertExists()
        composeRule.onNodeWithText("Album card").performClick()
        composeRule.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .performScrollToIndex(3)
        composeRule.onNodeWithText("Related creator").performClick()
        composeRule.onNodeWithText("Video card").performClick()
        assertEquals(
            listOf("songs:Second hit@1/2", "more:Singles", "playlist:MPREalbum/Creator Summary", "creator:UCother", "songs:Video card@0/1"),
            events
        )
    }

    @Test
    fun `phone screen keeps the summary header and retries after a load failure`() {
        var attempts = 0
        setScreen {
            attempts++
            throw IOException("offline")
        }

        val failure = context.getString(CoreCommonR.string.youtube_creator_load_failed, "offline")
        waitForText(failure)
        composeRule.onAllNodesWithText("Creator Summary").assertCountEquals(2)
        composeRule.onNodeWithText("Summary subtitle").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.youtube_creator_sections_empty)).assertDoesNotExist()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_retry)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { attempts == 2 }
    }

    @Test
    fun `content without a detail shows loading then the error with retry`() {
        var uiState by mutableStateOf(YouTubeMusicCreatorDetailUiState(loading = true))
        setContent(isTabletLayout = false) { uiState }

        composeRule.onNodeWithTag("youtubeCreatorWorksList").assertDoesNotExist()
        uiState = YouTubeMusicCreatorDetailUiState(loading = false, error = "Creator missing")
        composeRule.onNodeWithText("Creator missing").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_retry)).performClick()
        assertEquals(listOf("retry"), events)

        uiState = YouTubeMusicCreatorDetailUiState(
            loading = false,
            detail = YouTubeMusicCreatorDetail(
                header = header.copy(coverUrl = "", subtitle = "", description = "", subscriberCountText = "", monthlyListenerCountText = ""),
                sections = emptyList()
            )
        )
        composeRule.onNodeWithText(string(CoreCommonR.string.youtube_creator_sections_empty)).assertExists()
        composeRule.onNodeWithText("Artist channel").assertDoesNotExist()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_follow)).assertExists()

        uiState = uiState.copy(detail = uiState.detail?.copy(header = header.copy(subscriberCountText = "", monthlyListenerCountText = "")))
        composeRule.onNodeWithText("About the creator").assertExists()
        composeRule.onNodeWithText("1M subscribers").assertDoesNotExist()
        followFavorite = null
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_follow)).assertDoesNotExist()
        composeRule.onNodeWithText("About the creator").assertExists()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `tablet content shows the profile sidebar and hides duplicated metadata`() {
        var uiState by mutableStateOf(
            YouTubeMusicCreatorDetailUiState(
                loading = true,
                error = "Refresh failed",
                detail = YouTubeMusicCreatorDetail(
                    header = header.copy(subtitle = "Artist channel · 1M subscribers"),
                    sections = listOf(albums)
                )
            )
        )
        setContent(isTabletLayout = true) { uiState }

        composeRule.onNodeWithTag("youtubeCreatorTabletProfile").assertExists()
        composeRule.onNodeWithText("Artist channel · 1M subscribers").assertExists()
        composeRule.onNodeWithText("2M monthly").assertExists()
        composeRule.onNodeWithText("1M subscribers").assertDoesNotExist()
        composeRule.onNodeWithText("About the creator").assertExists()
        composeRule.onNodeWithText("Refresh failed").assertExists()
        composeRule.onNodeWithText("Album card").assertExists()

        uiState = uiState.copy(
            loading = false,
            error = null,
            detail = YouTubeMusicCreatorDetail(
                header = header.copy(coverUrl = "", subtitle = "", description = "", monthlyListenerCountText = ""),
                sections = emptyList()
            )
        )
        composeRule.onNodeWithText("1M subscribers").assertExists()
        composeRule.onNodeWithText("About the creator").assertDoesNotExist()
        composeRule.onNodeWithText(string(CoreCommonR.string.youtube_creator_sections_empty)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_follow)).assertExists()

        followFavorite = null
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_follow)).assertDoesNotExist()
        composeRule.onNodeWithText("1M subscribers").assertExists()
    }

    @Test
    fun `song cover shows artwork only for a non-blank cover url`() {
        var coverUrl by mutableStateOf<String?>(null)
        composeRule.setContent {
            MaterialTheme {
                CreatorSongCover(coverUrl = coverUrl, contentDescription = "Song cover", offlineMode = false)
            }
        }

        composeRule.onNodeWithContentDescription("Song cover").assertDoesNotExist()
        coverUrl = " "
        composeRule.onNodeWithContentDescription("Song cover").assertDoesNotExist()
        coverUrl = "https://example.invalid/song.jpg"
        composeRule.onNodeWithContentDescription("Song cover").assertExists()
    }

    @Test
    fun `creator items content covers loading errors pagination and playback`() {
        val items = topSongs.items + albums.items.first()
        var uiState by mutableStateOf(YouTubeMusicCreatorItemsUiState(loading = true))
        composeRule.setContent {
            MaterialTheme {
                YouTubeMusicCreatorItemsContent(
                    uiState = uiState,
                    listState = rememberLazyListState(),
                    onRetry = { events += "retry" },
                    onLoadMore = { events += "loadMore" },
                    onSongClick = { events += "item:${it.videoId}" },
                    offlineMode = false,
                    isTabletLayout = false
                )
            }
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.youtube_creator_items_empty)).assertDoesNotExist()
        uiState = YouTubeMusicCreatorItemsUiState(loading = false, error = "Items failed")
        composeRule.onNodeWithText(string(CoreCommonR.string.action_retry)).performClick()

        uiState = YouTubeMusicCreatorItemsUiState(
            loading = true,
            error = "Stale items",
            loadMoreError = "Next page failed",
            playbackQueueError = "Queue failed",
            items = items,
            continuation = "next"
        )
        composeRule.onNodeWithText("Stale items").assertExists()
        composeRule.onNodeWithText("Queue failed").assertExists()
        composeRule.onNodeWithText("Next page failed").assertExists()
        composeRule.onNodeWithText("Album card").assertDoesNotExist()
        composeRule.onNodeWithText("First hit").performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.youtube_creator_load_more)).performClick()

        uiState = uiState.copy(loading = false, loadingMore = true, error = null, loadMoreError = null)
        composeRule.onNode(hasText(string(CoreCommonR.string.youtube_creator_load_more)) and hasClickAction())
            .assertIsNotEnabled()

        uiState = YouTubeMusicCreatorItemsUiState(loading = false, items = albums.items.take(1))
        composeRule.onNodeWithText(string(CoreCommonR.string.youtube_creator_items_empty)).assertExists()
        assertEquals(listOf("retry", "item:v1", "loadMore"), events)
    }

    @Test
    fun `creator helpers derive metadata favorites and item routes`() {
        assertEquals(listOf("2M monthly", "1M subscribers"), youtubeMusicCreatorPhoneMetadata(header))
        assertEquals(
            listOf("Same"),
            youtubeMusicCreatorPhoneMetadata(header.copy(monthlyListenerCountText = "Same", subscriberCountText = "Same"))
        )
        assertEquals(emptyList<String>(), youtubeMusicCreatorPhoneMetadata(header.copy(monthlyListenerCountText = "", subscriberCountText = " ")))
        assertEquals(listOf("2M monthly"), youtubeMusicCreatorTabletMetadata(header.copy(subtitle = "Artist · 1m SUBSCRIBERS")))

        val favorite = createYouTubeMusicCreatorFavorite(creator.copy(coverUrl = "", subtitle = ""), null)
        assertEquals("Creator Summary", favorite.name)
        assertEquals(null, favorite.coverUrl)
        assertEquals(null, favorite.subtitle)

        openYouTubeMusicCreatorItem(
            YouTubeMusicCreatorItem(YouTubeMusicCreatorItemType.Creator, "No id", "", ""),
            onSongClick = { _, _ -> events += "song" },
            onPlaylistClick = { events += "playlist" },
            onCreatorClick = { events += "creator" }
        )
        openYouTubeMusicCreatorItem(
            YouTubeMusicCreatorItem(YouTubeMusicCreatorItemType.Song, "No video", "", ""),
            onSongClick = { _, _ -> events += "song" },
            onPlaylistClick = { events += "playlist" },
            onCreatorClick = { events += "creator" }
        )
        openYouTubeMusicCreatorItem(
            YouTubeMusicCreatorItem(YouTubeMusicCreatorItemType.Playlist, "List", "", "", browseId = "VLlist"),
            onSongClick = { _, _ -> events += "song" },
            onPlaylistClick = { events += "playlist:${it.playlistId}" },
            onCreatorClick = { events += "creator" }
        )
        assertEquals(listOf("playlist:list"), events)
    }

    private fun setScreen(loadDetail: suspend (YouTubeMusicCreatorSummary) -> YouTubeMusicCreatorDetail) {
        val application = context as Application
        composeRule.setContent {
            MaterialTheme {
                YouTubeMusicCreatorDetailScreen(
                    creator = creator,
                    onSongClick = { songs, index -> events += "songs:${songs[index].name}@$index/${songs.size}" },
                    onPlaylistClick = { events += "playlist:${it.browseId}/${it.creatorName}" },
                    onCreatorClick = { events += "creator:${it.browseId}" },
                    onSectionMoreClick = { events += "more:${it.title}" },
                    detailViewModelFactory = viewModelFactory {
                        initializer { YouTubeMusicCreatorDetailViewModel(application, loadDetail) }
                    }
                )
            }
        }
    }

    private fun setContent(isTabletLayout: Boolean, uiState: () -> YouTubeMusicCreatorDetailUiState) {
        composeRule.setContent {
            MaterialTheme {
                YouTubeMusicCreatorDetailContent(
                    uiState = uiState(),
                    listState = rememberLazyListState(),
                    creatorBrowseId = creator.browseId,
                    sectionStateHolder = rememberSaveableStateHolder(),
                    miniPlayerHeight = 0.dp,
                    offlineMode = false,
                    onRetry = { events += "retry" },
                    onSongClick = { _, _ -> events += "song" },
                    onSectionSongClick = { _, _ -> events += "sectionSong" },
                    onPlaylistClick = { events += "playlist" },
                    onCreatorClick = { events += "creator" },
                    onSectionMoreClick = { events += "more" },
                    isTabletLayout = isTabletLayout,
                    followFavorite = followFavorite
                )
            }
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun string(id: Int): String = context.getString(id)

    private fun song(videoId: String, title: String, durationMs: Long) = YouTubeMusicCreatorItem(
        type = YouTubeMusicCreatorItemType.Song,
        title = title,
        subtitle = "Song artist",
        coverUrl = "",
        videoId = videoId,
        durationMs = durationMs
    )
}
