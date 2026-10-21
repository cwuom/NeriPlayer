package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassOverscrollFactory
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalPlaylistDownloadSelectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository by lazy { LocalPlaylistRepository.getInstance(context) }
    private val screenVisible = mutableStateOf(false)
    private var ownedPlaylistId: Long? = null
    private var screenMounted = false

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @After
    fun removeOwnedPlaylist() {
        val playlistId = ownedPlaylistId ?: return
        runBlocking {
            withTimeout(20_000L) { repository.deletePlaylist(playlistId) }
        }
        if (screenMounted) {
            composeRule.waitUntil(20_000L) { !screenVisible.value }
        }
        AppContainer.playlistUsageRepo.removeEntry(playlistId, "local")
    }

    @Test
    fun longPressingSafSongWithEditedAlbumHidesDownloadAction() {
        val songs = createAndShowPlaylist()

        longPressSong(songs[0])

        downloadAction().assertDoesNotExist()
    }

    @Test
    fun longPressingLocalPathWithEditedAlbumHidesDownloadAction() {
        val songs = createAndShowPlaylist()

        longPressSong(songs[1])

        downloadAction().assertDoesNotExist()
    }

    @Test
    fun selectingLocalSourcesKeepsDownloadHiddenUntilRemoteSongIsSelected() {
        val songs = createAndShowPlaylist()
        longPressSong(songs[0])
        clickSong(songs[1])

        downloadAction().assertDoesNotExist()

        clickSong(songs[2])

        downloadAction().assertIsEnabled()

        clickSong(songs[2])

        downloadAction().assertDoesNotExist()
    }

    private fun createAndShowPlaylist(): List<SongItem> {
        val token = UUID.randomUUID().toString().take(8)
        val baseId = System.currentTimeMillis() * 1_000L
        val songs = List(3) { index ->
            SongItem(
                id = baseId + index,
                name = "download-$token-song-${index + 1}",
                artist = "synthetic artist",
                album = "user edited album",
                albumId = 42L,
                durationMs = 180_000L,
                coverUrl = null,
                mediaUri = if (index == 0) "content://neriplayer.test.documents/document/$token" else null,
                localFilePath = if (index == 1) "/synthetic/$token/song.flac" else null
            )
        }
        val playlist = runBlocking {
            withTimeout(20_000L) {
                assertTrue("local playlist repository failed to initialize", repository.awaitInitialized())
                repository.createPlaylistWithPreparedSongs("DL$token", songs)
            }
        }
        ownedPlaylistId = playlist.id
        screenVisible.value = true
        composeRule.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides AdvancedGlassOverscrollFactory) {
                MaterialTheme {
                    val navController = rememberNavController()
                    NavHost(navController, startDestination = "download-selection") {
                        composable("download-selection") {
                            if (screenVisible.value) {
                                LocalPlaylistDetailScreen(
                                    playlistId = playlist.id,
                                    onBack = { screenVisible.value = false },
                                    onDeleted = { screenVisible.value = false },
                                    offlineMode = true
                                )
                            }
                        }
                    }
                }
            }
        }
        screenMounted = true
        composeRule.waitUntil(20_000L) {
            composeRule.onAllNodesWithText(playlist.name).fetchSemanticsNodes().isNotEmpty()
        }
        return playlist.songs
    }

    private fun longPressSong(song: SongItem) {
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(song.name))
        composeRule.onNodeWithText(song.name).performTouchInput { longClick() }
    }

    private fun clickSong(song: SongItem) {
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(song.name))
        composeRule.onNodeWithText(song.name).performClick()
    }

    private fun downloadAction() = composeRule.onNodeWithContentDescription(
        context.getString(CoreCommonR.string.cd_download_selected)
    )
}
