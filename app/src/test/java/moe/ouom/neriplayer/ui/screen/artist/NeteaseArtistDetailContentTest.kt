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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.crash.ExceptionHandler
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaCovers
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaDownloads
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistHeader
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h1600dp")
class NeteaseArtistDetailContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val header = NeteaseArtistHeader(
        id = 7L,
        name = "Artist Seven",
        coverUrl = "",
        avatarUrl = "https://example.invalid/avatar.jpg",
        alias = "The Seventh",
        briefDesc = "Brief artist description",
        musicSize = 4,
        albumSize = 2,
        followed = false
    )

    private val songs = listOf(
        song(1, artist = "Singer", album = "First Album"),
        song(2, artist = " ", album = "Second Album"),
        song(3, artist = "Solo", album = "Netease"),
        song(4, artist = "", album = "")
    )

    private val albums = listOf(
        AlbumSummary(id = 11L, name = "Album Eleven", picUrl = "", size = 1),
        AlbumSummary(id = 12L, name = "Album Twelve", picUrl = "", size = 3)
    )

    private val events = mutableListOf<String>()

    @Before
    fun bindMediaHost() {
        LocalMediaHostAccess.bind(
            downloads = AndroidLocalMediaDownloads,
            covers = AndroidLocalMediaCovers,
            crashLogs = CrashLogCleanup(ExceptionHandler::clearCrashLogs)
        )
    }

    @Test
    fun `phone layout shows header songs and albums and forwards actions`() {
        var ui by mutableStateOf(
            NeteaseArtistDetailUiState(
                loading = false,
                header = header,
                songs = songs,
                albums = albums,
                songsHasMore = true,
                albumsHasMore = true,
                albumsLoadingMore = true
            )
        )
        setContent { ui }

        composeRule.onNodeWithText("The Seventh").assertExists()
        composeRule.onNodeWithText("Brief artist description").assertExists()
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.artist_song_count, 4)).assertExists()
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.artist_album_count, 2)).assertExists()
        composeRule.onNodeWithText("Singer").assertExists()
        composeRule.onNodeWithText(" · First Album").assertExists()
        composeRule.onNodeWithText("Second Album").assertExists()
        composeRule.onNodeWithText("Solo").assertExists()
        composeRule.onAllNodesWithText(" · Netease").assertCountEquals(0)

        composeRule.onNodeWithText("Song 3").performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_follow)).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_load_more)).performClick()
        assertEquals(listOf("song:3/4", "follow", "moreSongs"), events)

        composeRule.onNodeWithText(string(CoreCommonR.string.artist_tab_albums)).performClick()
        composeRule.onNodeWithText("Album Twelve").assertExists()
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.count_songs_format, 3)).assertExists()
        composeRule.onNodeWithText("Album Eleven").performClick()
        composeRule.onNode(hasText(string(CoreCommonR.string.artist_load_more)) and hasClickAction())
            .assertIsNotEnabled()
        assertEquals("album:11", events.last())

        ui = ui.copy(albums = emptyList(), albumsHasMore = false)
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_albums_empty)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_tab_songs)).performClick()
        ui = ui.copy(songs = emptyList(), songsHasMore = false)
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_songs_empty)).assertExists()
    }

    @Test
    fun `phone layout reports loading errors and missing headers`() {
        var ui by mutableStateOf(NeteaseArtistDetailUiState(loading = true))
        setContent { ui }

        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_loading_content)).assertExists()
        composeRule.onNode(hasText(string(CoreCommonR.string.artist_follow)) and hasClickAction())
            .assertIsNotEnabled()
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.artist_song_count, 0)).assertExists()

        ui = NeteaseArtistDetailUiState(loading = false, error = "Network down")
        composeRule.onNodeWithText("Network down").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_retry)).performClick()
        assertEquals(listOf("retry"), events)

        ui = NeteaseArtistDetailUiState(
            loading = false,
            error = "Refresh failed",
            header = header.copy(followed = true, briefDesc = "", alias = ""),
            followUpdating = true,
            songs = songs.take(1)
        )
        composeRule.onNodeWithText("Refresh failed").assertExists()
        composeRule.onNodeWithText("Song 1").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_followed)).assertExists()
        composeRule.onNodeWithText("The Seventh").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `tablet layout shows the profile sidebar beside the works`() {
        var ui by mutableStateOf(
            NeteaseArtistDetailUiState(
                loading = false,
                header = header.copy(followed = true, coverUrl = "https://example.invalid/cover.jpg"),
                songs = songs.take(2),
                songsHasMore = true,
                songsLoadingMore = true
            )
        )
        setContent { ui }

        composeRule.onNodeWithText("Artist Seven").assertExists()
        composeRule.onNodeWithText("The Seventh").assertExists()
        composeRule.onNodeWithText("Brief artist description").assertExists()
        composeRule.onNode(hasText(string(CoreCommonR.string.artist_followed)) and hasClickAction())
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_tab_songs)).assertExists()
        composeRule.onNodeWithText("Song 2").performClick()
        assertEquals(listOf("follow", "song:2/2"), events)

        ui = NeteaseArtistDetailUiState(loading = true, header = header.copy(alias = ""))
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_loading_content)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_tab_songs)).assertDoesNotExist()
        composeRule.onNodeWithText("The Seventh").assertDoesNotExist()

        ui = NeteaseArtistDetailUiState(loading = false, error = "Tablet failure")
        composeRule.onNodeWithText("Tablet failure").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_tab_songs)).assertDoesNotExist()

        ui = NeteaseArtistDetailUiState(loading = false)
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_tab_songs)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_songs_empty)).assertExists()
    }

    @Test
    fun `hero falls back to the avatar and hides blank aliases`() {
        assertEquals(NeteaseArtistHero(coverUrl = "", avatarUrl = "", name = null, alias = null), null.toNeteaseArtistHero())
        assertEquals(
            NeteaseArtistHero(
                coverUrl = "https://example.invalid/avatar.jpg",
                avatarUrl = "https://example.invalid/avatar.jpg",
                name = "Artist Seven",
                alias = "The Seventh"
            ),
            header.toNeteaseArtistHero()
        )
        assertEquals(
            NeteaseArtistHero(coverUrl = "https://example.invalid/cover.jpg", avatarUrl = "", name = "Artist Seven", alias = null),
            header.copy(coverUrl = "https://example.invalid/cover.jpg", avatarUrl = "", alias = " ").toNeteaseArtistHero()
        )
    }

    private fun setContent(ui: () -> NeteaseArtistDetailUiState) {
        composeRule.setContent {
            var selectedTab by remember { mutableIntStateOf(0) }
            MaterialTheme {
                NeteaseArtistDetailContent(
                    ui = ui(),
                    listState = rememberLazyListState(),
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    onRetry = { events += "retry" },
                    onToggleFollow = { events += "follow" },
                    onLoadMoreSongs = { events += "moreSongs" },
                    onLoadMoreAlbums = { events += "moreAlbums" },
                    onSongClick = { list, index -> events += "song:${list[index].id}/${list.size}" },
                    onAlbumClick = { events += "album:${it.id}" },
                    offlineMode = false
                )
            }
        }
    }

    private fun string(id: Int): String = context.getString(id)

    private fun plural(id: Int, count: Int): String = context.resources.getQuantityString(id, count, count)

    private fun song(id: Long, artist: String, album: String) = SongItem(
        id = id,
        name = "Song $id",
        artist = artist,
        album = album,
        albumId = 0L,
        durationMs = 61_000L,
        coverUrl = null
    )
}
