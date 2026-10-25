package moe.ouom.neriplayer.ui.screen.tab.library

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryLocalArtistComputationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun initializeContainer() {
        if (!AppContainer.isInitialized()) AppContainer.initialize(application)
    }

    @Test
    fun `playlist entry and reentry do not scan songs in an offscreen playlist`() {
        val songs = CountingSongs(mutableListOf(song(1L, "Alpha"), song(2L, "Beta")))
        var visible by mutableStateOf(true)
        val playlists = playlistsWithOffscreenSongs(songs)
        composeRule.setContent {
            MaterialTheme {
                if (visible) {
                    LocalPlaylistList(
                        playlists = playlists,
                        listState = rememberLazyListState(),
                        onCreate = {},
                        onClick = {},
                        onArtistClick = {},
                        offlineMode = true
                    )
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals("hidden artist data must not be built on playlist entry", 0, songs.reads.get())
            visible = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible = true }
        composeRule.runOnIdle {
            assertEquals("recreating the playlist page must not rebuild hidden artists", 0, songs.reads.get())
        }
    }

    @Test
    fun `artist category builds real summaries off main and refreshes searchable results`() {
        val songs = CountingSongs(mutableListOf(song(1L, "Alpha"), song(2L, "Beta")))
        var playlists by mutableStateOf(playlistsWithOffscreenSongs(songs))
        composeRule.setContent {
            MaterialTheme {
                LocalPlaylistList(
                    playlists = playlists,
                    listState = rememberLazyListState(),
                    onCreate = {},
                    onClick = {},
                    onArtistClick = {},
                    offlineMode = true
                )
            }
        }
        composeRule.onNodeWithText(application.getString(CoreCommonR.string.library_favorite_tab_artists))
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Alpha")).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodes(hasText("Beta")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Alpha").assertExists()
        composeRule.onNodeWithText("Beta").assertExists()
        assertTrue("the actual song source was traversed", songs.reads.get() >= 2)
        assertEquals("artist aggregation must not traverse the full library on main", 0, songs.mainThreadReads.get())

        val refreshedSongs = CountingSongs(mutableListOf(song(3L, "Gamma")))
        composeRule.runOnIdle {
            playlists = playlistsWithOffscreenSongs(refreshedSongs)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Gamma")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Alpha").assertDoesNotExist()
        composeRule.onNodeWithText("Beta").assertDoesNotExist()
        val readsAfterRefresh = refreshedSongs.reads.get()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("missing artist")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Gamma")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("Gamma").assertDoesNotExist()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("gamma")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Gamma")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Gamma").assertExists()
        assertEquals(
            "search must reuse the existing artist summaries; reads: ${refreshedSongs.readOrigins.toList()}",
            readsAfterRefresh,
            refreshedSongs.reads.get()
        )
    }

    private fun playlistsWithOffscreenSongs(songs: MutableList<SongItem>): List<LocalPlaylist> =
        List(40) { index -> LocalPlaylist(id = index.toLong() + 100L, name = "Playlist $index") } +
            LocalPlaylist(id = 999L, name = "Offscreen library", songs = songs)

    private fun song(id: Long, artist: String) = SongItem(
        id = id,
        name = "Track $id",
        artist = artist,
        album = "",
        albumId = 0L,
        durationMs = 0L,
        coverUrl = null
    )

    private class CountingSongs(private val source: MutableList<SongItem>) : AbstractMutableList<SongItem>() {
        val reads = AtomicInteger()
        val mainThreadReads = AtomicInteger()
        val readOrigins = ConcurrentLinkedQueue<String>()

        override val size: Int
            get() = source.size

        override fun get(index: Int): SongItem {
            reads.incrementAndGet()
            val origin = Thread.currentThread().stackTrace
                .filter { frame ->
                    frame.className.startsWith("moe.ouom.neriplayer.") &&
                        !frame.className.startsWith(CountingSongs::class.java.name)
                }
                .take(6)
                .joinToString(" <- ") { frame -> "${frame.className}.${frame.methodName}:${frame.lineNumber}" }
            readOrigins += "${Thread.currentThread().name}: $origin"
            if (Thread.currentThread() === Looper.getMainLooper().thread) {
                mainThreadReads.incrementAndGet()
            }
            return source[index]
        }

        override fun add(index: Int, element: SongItem) = source.add(index, element)

        override fun removeAt(index: Int): SongItem = source.removeAt(index)

        override fun set(index: Int, element: SongItem): SongItem = source.set(index, element)
    }
}
