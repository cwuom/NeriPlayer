package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExploreSongRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun moreMenuShowsDownloadAndHomeStyleSongActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var downloadCount = 0

        composeRule.setContent {
            MaterialTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                SongRow(
                    index = 1,
                    song = testSong(),
                    isFavorite = false,
                    favoriteActionEnabled = true,
                    offlineMode = true,
                    snackbarHostState = snackbarHostState,
                    onClick = {},
                    onPlayNow = {},
                    onPlayNext = {},
                    onAddToQueueEnd = {},
                    onDownload = { downloadCount += 1 },
                    onToggleFavorite = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            context.getString(R.string.cd_more_actions)
        ).performClick()

        listOf(
            R.string.search_result_play_keep_queue,
            R.string.local_playlist_play_next,
            R.string.search_result_add_to_current_queue,
            R.string.favorite_add,
            R.string.download_to_local,
            R.string.action_copy_song_info
        ).forEach { labelRes ->
            composeRule.onNodeWithText(context.getString(labelRes)).assertExists()
        }

        composeRule.onNodeWithText(context.getString(R.string.download_to_local)).performClick()
        composeRule.runOnIdle {
            assertEquals(1, downloadCount)
        }
    }

    @Test
    fun copiedSongInfoUsesTheHomeMenuFormat() {
        assertEquals("海屿你-马也_Crabbbit", buildExploreSongInfo(testSong()))
    }

    private fun testSong(): SongItem {
        return SongItem(
            id = 1L,
            name = "海屿你",
            artist = "马也_Crabbbit",
            album = "海屿你",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null
        )
    }
}
