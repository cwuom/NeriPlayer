package moe.ouom.neriplayer.ui.screen.tab.home

import android.app.Application
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeLayout
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeSectionIds
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeShelf
import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeSectionState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w411dp-h850dp")
class InternationalHomeContentTest {
    @get:Rule val compose = createComposeRule()
    private var action = ""
    private val state = HomeUiState(internationalizationEnabled = true, homeSourceReady = true)

    @Test fun disabledProviderShowsOneSettingsAction() {
        render { state }
        compose.onNodeWithText("YouTube Music is turned off").assertExists()
        compose.onNodeWithText("Open general settings").performClick()
        assertEquals("settings", action)
        compose.onNodeWithTag("internationalHomeFeed").assertDoesNotExist()
    }

    @Test fun emptyFirstPageWaitsForBackgroundPagesWithoutOfferingManualPagination() {
        render { state.copy(youtubeHomeEnabled = true, loadingMoreYouTubeHome = true) }
        compose.onNodeWithText("Loading").assertExists()
        compose.onNodeWithText("Load more", ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("No recommendations match", substring = true).assertDoesNotExist()
    }

    @Test fun matchingShelvesAppearWhileLaterPagesAreStillLoading() {
        val first = YouTubeMusicHomeShelf("First picks", listOf(YouTubeMusicHomeItem("One", "", "", videoId = "track")))
        val later = YouTubeMusicHomeShelf("Album picks", listOf(YouTubeMusicHomeItem("Two", "", "", browseId = "MPREtwo")))
        var ui by mutableStateOf(state.copy(youtubeHomeEnabled = true, loadingMoreYouTubeHome = true,
            ytMusicHomeShelves = HomeSectionState(items = listOf(first))))
        render(InternationalHomeKind.ALBUM) { ui }
        compose.onNodeWithTag("internationalHomeSpotlight").assertExists()
        compose.onNodeWithText("Loading").assertExists()
        compose.onNodeWithText("No matches", substring = true).assertDoesNotExist()
        compose.runOnIdle { ui = ui.copy(ytMusicHomeShelves = HomeSectionState(items = listOf(first, later))) }
        compose.onNodeWithText("Album picks").assertExists()
        compose.onNodeWithText("Loading").assertDoesNotExist()
        compose.onNodeWithText("Load more", ignoreCase = true).assertDoesNotExist()
    }

    @Test fun failedContinuationKeepsSpotlightAndRetriesOnlyTheFailedPage() {
        val layout = InternationalHomeLayout(hidden = setOf(InternationalHomeSectionIds.FEED))
        val shelf = YouTubeMusicHomeShelf("First picks", listOf(YouTubeMusicHomeItem("One", "", "", videoId = "track")))
        render { state.copy(youtubeHomeEnabled = true, internationalHomeLayout = layout,
            ytMusicHomeShelves = HomeSectionState(items = listOf(shelf)), youTubeHomeLoadMoreError = UiText.Literal("Connection failed")) }
        compose.onNodeWithTag("internationalHomeSpotlight").assertExists()
        compose.onNodeWithText("Connection failed").assertExists()
        compose.onNodeWithText("Tap to Retry").performClick()
        assertEquals("retry-page", action)
    }

    @Test fun spotlightPlaysFromItsShelfWhenDomesticSectionsAreDisabled() {
        val shelf = YouTubeMusicHomeShelf("Tokyo picks", listOf(
            YouTubeMusicHomeItem("One", "Singer", "", videoId = "videoA"),
            YouTubeMusicHomeItem("Two", "Singer", "", videoId = "videoB")
        ))
        val layout = InternationalHomeLayout(hidden = setOf(InternationalHomeSectionIds.CONTINUE, InternationalHomeSectionIds.FEED))
        render { state.copy(youtubeHomeEnabled = true, internationalHomeLayout = layout,
            ytMusicHomeShelves = HomeSectionState(items = listOf(shelf))) }
        compose.onNodeWithTag("internationalHomeSpotlight").performClick()
        assertEquals("0:One,Two", action)
        compose.onNodeWithTag("internationalHomeFeed").assertDoesNotExist()
    }

    @Test fun numberedSongPagesKeepTheFullShelfQueueBeyondTheDomesticPreview() {
        val tracks = (1..31).map { index ->
            YouTubeMusicHomeItem("Track $index", "Artist", "https://example.invalid/cover.jpg", videoId = "video$index")
        }
        val shelf = YouTubeMusicHomeShelf("Picks", tracks)
        val layout = InternationalHomeLayout(hidden = setOf(InternationalHomeSectionIds.SPOTLIGHT))
        render { state.copy(youtubeHomeEnabled = true, internationalHomeLayout = layout,
            ytMusicHomeShelves = HomeSectionState(items = listOf(shelf))) }

        compose.onNodeWithText("1").assertExists()
        val songPager = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and hasScrollToIndexAction()
        compose.onNode(songPager).performScrollToIndex(7)
        compose.onNodeWithText("29").assertExists()
        compose.onNodeWithText("Track 31").performClick()
        assertEquals("30:" + tracks.joinToString(",") { it.title }, action)
    }

    private fun render(kind: InternationalHomeKind? = null, ui: () -> HomeUiState) {
        compose.setContent {
            val snapshot = ui()
            val shelves = buildInternationalHomeShelves(snapshot.ytMusicHomeShelves.items)
            val env = HomeMajorSectionEnv(ui = snapshot, showContinue = false, showTrending = false,
                showRadar = false, showRecommended = false, showBiliMusic = false, showOnlineFeeds = true,
                radarPlaylistListState = rememberLazyListState(), loadingText = "Loading", registerKey = { it },
                onSongClick = { songs, index -> action = "$index:" + songs.joinToString(",") { it.name } })
            LazyVerticalGrid(columns = GridCells.Fixed(2)) {
                addInternationalHome(env, shelves, shelves.filterHomeShelves(kind), kind, {}, { action = "refresh" },
                    { action = "retry-page" }, { action = "settings" })
            }
        }
    }
}
