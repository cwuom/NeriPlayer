package moe.ouom.neriplayer.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.data.model.bilibili.video.VideoStats
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppNavigationGraphReuseTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var firstController: NavHostController
    private lateinit var secondController: NavHostController
    private lateinit var navController: NavHostController
    private var ownerRevision by mutableIntStateOf(0)
    private var configuration by mutableStateOf(GraphConfiguration())
    private var useSecondController by mutableStateOf(false)

    @Test
    fun `replacing only the owner retains destinations entries and back stack state`() {
        setHost()
        composeRule.runOnIdle { navController.navigate(Destinations.Explore.route) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { navController.navigate(Destinations.Settings.route) }
        composeRule.waitForIdle()
        val graph = composeRule.runOnIdle { navController.graph }
        val routes = listOf(Destinations.Home.route, Destinations.Explore.route, Destinations.Settings.route)
        val entries = composeRule.runOnIdle { routes.map { navController.getBackStackEntry(it) } }
        val destinations = entries.map { it.destination }
        val entryIds = entries.map { it.id }
        composeRule.runOnIdle { entries[1].savedStateHandle["graph-reuse-marker"] = "retained" }

        repeat(3) {
            composeRule.runOnIdle { ownerRevision++ }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertSame(graph, navController.graph)
                routes.forEachIndexed { index, route ->
                    val entry = navController.getBackStackEntry(route)
                    assertSame(entries[index], entry)
                    assertEquals(entryIds[index], entry.id)
                    // 结构相等的新图也会替换 destination，所以 graph 和 entry 相同还不够
                    assertSame("owner-only recomposition rebuilt destination $route", destinations[index], entry.destination)
                    assertSame(destinations[index], navController.graph.findNode(route))
                }
                assertSame(entries[2], navController.currentBackStackEntry)
            }
        }

        composeRule.runOnIdle { assertTrue(navController.popBackStack()) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertSame(entries[1], navController.currentBackStackEntry)
            assertEquals("retained", navController.currentBackStackEntry?.savedStateHandle?.get<String>("graph-reuse-marker"))
        }
        composeRule.runOnIdle { assertTrue(navController.popBackStack()) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertSame(entries[0], navController.currentBackStackEntry) }
    }

    @Test
    fun `an existing destination renders the latest scene callback without being rebuilt`() {
        setHost()
        composeRule.runOnIdle { navController.navigate(Destinations.DownloadProgress.route) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(rendererText(0, Destinations.DownloadProgress.route)).assertExists()
        val entry = composeRule.runOnIdle { checkNotNull(navController.currentBackStackEntry) }
        val destination = entry.destination

        composeRule.runOnIdle { ownerRevision++ }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(rendererText(1, Destinations.DownloadProgress.route)).assertExists()
        composeRule.onNodeWithText(rendererText(0, Destinations.DownloadProgress.route)).assertDoesNotExist()
        composeRule.runOnIdle {
            assertSame(entry, navController.currentBackStackEntry)
            assertSame("refreshing the renderer must not rebuild its destination", destination, entry.destination)
        }
    }

    @Test
    fun `configuration start destination and controller changes still reach the real NavHost`() {
        setHost()
        val original = composeRule.runOnIdle { checkNotNull(navController.graph.findNode(Destinations.Home.route)) }

        composeRule.runOnIdle { configuration = configuration.copy(offlineMode = true) }
        composeRule.waitForIdle()
        val offline = composeRule.runOnIdle { checkNotNull(navController.graph.findNode(Destinations.Home.route)) }
        assertNotSame("offline changes must replace captured destination configuration", original, offline)

        composeRule.runOnIdle { configuration = configuration.copy(coherentFeedbackEnabled = true) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertNotSame("motion configuration changes must rebuild transition closures", offline, navController.graph.findNode(Destinations.Home.route))
        }

        composeRule.runOnIdle { configuration = configuration.copy(startDestination = Destinations.Library.route) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(Destinations.Library.route, navController.graph.startDestinationRoute)
            assertEquals(Destinations.Library.route, navController.currentDestination?.route)
        }

        composeRule.runOnIdle { useSecondController = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertSame(secondController, navController)
            assertNotSame(firstController.graph, secondController.graph)
            assertEquals(Destinations.Library.route, secondController.graph.startDestinationRoute)
            assertEquals(Destinations.Library.route, secondController.currentDestination?.route)
            secondController.navigate(Destinations.Explore.route)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(Destinations.Explore.route, secondController.currentDestination?.route)
            assertEquals(Destinations.Library.route, firstController.currentDestination?.route)
        }
    }

    @Test
    fun `retained proxy forwards every current media callback with its exact payload`() {
        var revision by mutableIntStateOf(0)
        lateinit var currentProxy: AppNavigationGraphOwner
        val calls = mutableListOf<MediaCall>()
        composeRule.setContent {
            val currentRevision = revision
            val owner = AppNavigationGraphOwner(
                navController = rememberNavController(),
                presentation = AppNavigationGraphPresentation(
                    startDestination = Destinations.Home.route,
                    coherentFeedbackEnabled = false,
                    offlineMode = false,
                    renderScene = { _, _ -> }
                ),
                mediaActions = recordingActions(currentRevision, calls)
            )
            val proxy = rememberAppNavigationGraphOwner(owner)
            SideEffect { currentProxy = proxy }
        }
        composeRule.waitForIdle()
        val originalProxy = composeRule.runOnIdle { currentProxy }
        val retainedActions = originalProxy.mediaActions
        val songs = listOf(
            SongItem(11L, "First track", "Artist A", "Album A", 21L, 60_000L, null),
            SongItem(12L, "Second track", "Artist B", "Album B", 22L, 90_000L, "song-cover")
        )
        val videos = listOf(
            BiliVideoItem(31L, "BV-fixture-a", "First video", "Uploader A", coverUrl = "video-cover-a", durationSec = 60),
            BiliVideoItem(32L, "BV-fixture-b", "Second video", "Uploader B", coverUrl = "video-cover-b", durationSec = 90)
        )
        val videoInfo = VideoBasicInfo(
            aid = 31L, bvid = "BV-fixture-a", title = "Multipart video", coverUrl = "video-cover",
            desc = "fixture", durationSec = 150, ownerMid = 41L, ownerName = "Uploader", ownerFace = "owner-cover",
            stats = VideoStats(1L, 2L, 3L, 4L, 5L, 6L, 7L),
            pages = listOf(VideoPage(51L, 1, "Part one", 60, 1920, 1080), VideoPage(52L, 2, "Part two", 90, 1920, 1080))
        )
        val album = AlbumSummary(61L, "Album", "album-cover", 2)
        val playlist = YouTubeMusicPlaylist("VL-fixture", "PL-fixture", "Playlist", "Creator", "playlist-cover", 2)
        val creator = YouTubeMusicCreatorSummary("UC-fixture", "Creator", "Artist", "creator-cover", "channel-fixture")

        composeRule.runOnIdle {
            retainedActions.playSongs(songs, 1, "initial-source")
            assertEquals(listOf(MediaCall(0, "songs", songs, 1, "initial-source")), calls)
            calls.clear()
            revision = 1
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertSame("callback-only owner replacement must retain its proxy", originalProxy, currentProxy)
            assertSame(retainedActions, currentProxy.mediaActions)
            retainedActions.playSongs(songs, 1, "local_playlist_detail/71")
            retainedActions.playSongs(songs, 0, null)
            retainedActions.onSongClick(songs, 1)
            retainedActions.playBiliAudio(videos, 1, "bili_playlist_detail/fixture")
            retainedActions.playBiliAudio(videos, 0, null)
            retainedActions.playBiliParts(videoInfo, 1, "part-cover", "bili_uploader_detail/fixture")
            retainedActions.playBiliParts(videoInfo, 0, "", null)
            retainedActions.onNeteaseAlbumClick(album)
            retainedActions.onYouTubePlaylistClick(playlist)
            retainedActions.onYouTubeCreatorClick(creator)

            val expected = listOf(
                MediaCall(1, "songs", songs, 1, "local_playlist_detail/71"),
                MediaCall(1, "songs", songs, 0),
                MediaCall(1, "songs", songs, 1),
                MediaCall(1, "audio", videos, 1, "bili_playlist_detail/fixture"),
                MediaCall(1, "audio", videos, 0),
                MediaCall(1, "parts", videoInfo, 1, "bili_uploader_detail/fixture", "part-cover"),
                MediaCall(1, "parts", videoInfo, 0, coverUrl = ""),
                MediaCall(1, "album", album),
                MediaCall(1, "playlist", playlist),
                MediaCall(1, "creator", creator)
            )
            assertEquals(expected, calls)
            expected.zip(calls).forEach { (wanted, actual) -> assertSame(wanted.payload, actual.payload) }
        }
    }

    private fun recordingActions(revision: Int, calls: MutableList<MediaCall>) = AppNavigationMediaActions(
        playSongs = { songs, index, route -> calls += MediaCall(revision, "songs", songs, index, route) },
        playBiliAudio = { videos, index, route -> calls += MediaCall(revision, "audio", videos, index, route) },
        playBiliParts = { video, index, cover, route -> calls += MediaCall(revision, "parts", video, index, route, cover) },
        onNeteaseAlbumClick = { calls += MediaCall(revision, "album", it) },
        onYouTubePlaylistClick = { calls += MediaCall(revision, "playlist", it) },
        onYouTubeCreatorClick = { calls += MediaCall(revision, "creator", it) }
    )

    private fun setHost() {
        composeRule.setContent {
            firstController = rememberNavController()
            secondController = rememberNavController()
            navController = if (useSecondController) secondController else firstController
            val revision = ownerRevision
            val config = configuration
            AppNavigationGraph(
                AppNavigationGraphOwner(
                    navController = navController,
                    presentation = AppNavigationGraphPresentation(
                        startDestination = config.startDestination,
                        coherentFeedbackEnabled = config.coherentFeedbackEnabled,
                        offlineMode = config.offlineMode,
                        renderScene = { route, _ -> Text(rendererText(revision, route)) }
                    ),
                    mediaActions = AppNavigationMediaActions(
                        playSongs = { _, _, _ -> },
                        playBiliAudio = { _, _, _ -> },
                        playBiliParts = { _, _, _, _ -> },
                        onNeteaseAlbumClick = {},
                        onYouTubePlaylistClick = {},
                        onYouTubeCreatorClick = {}
                    )
                )
            )
        }
        composeRule.waitForIdle()
    }

    private fun rendererText(revision: Int, route: String?) = "renderer $revision ${route.orEmpty()}"

    private data class GraphConfiguration(
        val startDestination: String = Destinations.Home.route,
        val coherentFeedbackEnabled: Boolean = false,
        val offlineMode: Boolean = false
    )

    private data class MediaCall(
        val revision: Int,
        val action: String,
        val payload: Any,
        val index: Int? = null,
        val sourceRoute: String? = null,
        val coverUrl: String? = null
    )
}
