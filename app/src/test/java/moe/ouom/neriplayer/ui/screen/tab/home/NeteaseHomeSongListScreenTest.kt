package moe.ouom.neriplayer.ui.screen.tab.home

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomeSongSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = PlaylistHostTestApplication::class, qualifiers = "w390dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NeteaseHomeSongListScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private var originalProcessName: String? = null

    @Before
    fun preparePlaylistHost() {
        originalProcessName = application.applicationInfo.processName
        application.applicationInfo.processName = Application.getProcessName()
        if (!AppContainer.isInitialized()) AppContainer.initialize(application)
    }

    @After
    fun restoreProcessName() {
        application.applicationInfo.processName = originalProcessName
    }

    @Test
    fun hotChartShowsPlaylistHeaderAndPlaysTheSelectedSongFromTheFullList() {
        val source = NeteaseHomeSongSource.TOP_HOT
        val songs = (1..36).map { index ->
            SongItem(
                id = index.toLong(),
                name = "Track $index",
                artist = "Artist",
                album = "Album",
                albumId = 1,
                durationMs = 180_000,
                coverUrl = null
            )
        }
        val viewModel = mock(HomeViewModel::class.java)
        runBlocking { doReturn(songs).`when`(viewModel).fetchFullSongSection(source) }
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                requireNotNull(modelClass.cast(viewModel))
        }
        var picked: Pair<List<SongItem>, Int>? = null
        composeRule.setContent {
            val owner = requireNotNull(LocalViewModelStoreOwner.current)
            remember(owner) { ViewModelProvider(owner, factory)[HomeViewModel::class.java] }
            MaterialTheme {
                NeteaseHomeSongListScreen(
                    source = source,
                    onSongClick = { playlist, index -> picked = playlist to index }
                )
            }
        }

        val title = application.getString(source.titleRes)
        composeRule.onAllNodesWithText(title).onFirst().assertExists()
        composeRule.onNodeWithContentDescription(title).assertIsDisplayed()
        composeRule.onNodeWithText(
            application.getString(CoreCommonR.string.collection_track_count_format, songs.size)
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(application.getString(CoreCommonR.string.player_play_all))
            .assertIsEnabled()

        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(songs.last().name))
        composeRule.onNodeWithText(songs.last().name).performClick()
        assertEquals(songs to songs.lastIndex, picked)
    }
}

class PlaylistHostTestApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration = Configuration.Builder().build()
}
