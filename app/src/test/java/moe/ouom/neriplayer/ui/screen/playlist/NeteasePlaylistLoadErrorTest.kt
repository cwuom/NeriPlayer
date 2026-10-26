package moe.ouom.neriplayer.ui.screen.playlist

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.netease.collection.NeteaseCollectionDetailUiState
import moe.ouom.neriplayer.data.model.netease.collection.NeteaseCollectionHeader
import moe.ouom.neriplayer.data.model.playlist.PlaylistLoadFailure
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import moe.ouom.neriplayer.R as AppR

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class NeteasePlaylistLoadErrorTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a failed playlist blocks cached content and exposes explicit retry and return`() {
        var retries = 0
        var returns = 0
        val state = NeteaseCollectionDetailUiState(
            loading = false,
            error = PlaylistLoadFailure.Unknown,
            header = NeteaseCollectionHeader(11L, false, "Cached playlist", "", 0L, 1),
            tracks = listOf(SongItem(1L, "Cached song", "Artist", "Album", 0L, 1000L, null))
        )
        composeRule.setContent {
            MaterialTheme {
                DetailScreen(
                    ui = state,
                    playlistId = 11L,
                    playlistSource = "netease",
                    onRetry = { retries++ },
                    onBack = { returns++ }
                )
            }
        }

        composeRule.onNodeWithText(context.getString(AppR.string.playlist_error_title)).assertExists()
        composeRule.onNodeWithText("private diagnostics", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Cached song").assertDoesNotExist()
        composeRule.onNodeWithText("Cached playlist").assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_retry)).performClick()
        composeRule.runOnIdle { assertEquals(1, retries); assertEquals(0, returns) }
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_back)).performClick()
        composeRule.runOnIdle { assertEquals(1, retries); assertEquals(1, returns) }
    }
}
