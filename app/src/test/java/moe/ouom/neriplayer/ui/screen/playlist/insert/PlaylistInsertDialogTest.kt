package moe.ouom.neriplayer.ui.screen.playlist.insert

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.crash.ExceptionHandler
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaCovers
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaDownloads
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PlaylistInsertDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun bindMediaHost() {
        LocalMediaHostAccess.bind(
            downloads = AndroidLocalMediaDownloads,
            covers = AndroidLocalMediaCovers,
            crashLogs = CrashLogCleanup(ExceptionHandler::clearCrashLogs)
        )
    }

    @Test
    fun `position input validates previews and confirms the reordered keys`() {
        val songs = songs(12)
        val selected = setOf(songs[2].stableKey(), songs[3].stableKey())
        val confirmed = mutableListOf<PlaylistInsertPreview>()
        var dismissed = 0
        composeRule.setContent {
            MaterialTheme {
                PlaylistInsertDialog(
                    songs = songs,
                    selectedKeys = selected,
                    onDismiss = { dismissed++ },
                    onConfirm = { confirmed += it }
                )
            }
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_position_help, 11)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_preview_action)).assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("abc")
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_invalid_position, 11)).assertExists()

        composeRule.onNode(hasSetTextAction()).performTextReplacement("8")
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_position_summary, 8)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_preview_range, 8, 9)).assertExists()
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.playlist_insert_section_selected, 2)).assertExists()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(10)
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_section_after)).assertExists()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_section_before)).assertExists()
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.playlist_insert_omitted_before, 2)).assertExists()

        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_confirm_action)).performClick()
        assertEquals(8, confirmed.single().startPosition)
        assertEquals(
            listOf(0, 1, 4, 5, 6, 7, 8, 2, 3, 9, 10, 11).map { songs[it].stableKey() },
            confirmed.single().orderedKeys
        )

        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_edit_position)).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_preview_action)).assertIsEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_cancel)).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `boundary previews label the playlist start end and omitted songs`() {
        val songs = songs(20)
        composeRule.setContent {
            MaterialTheme {
                PlaylistInsertDialog(
                    songs = songs,
                    selectedKeys = setOf(songs[10].stableKey()),
                    onDismiss = {},
                    onConfirm = {},
                    offlineMode = true
                )
            }
        }

        composeRule.onNode(hasSetTextAction()).performTextReplacement("1")
        composeRule.onNode(hasSetTextAction()).performImeAction()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(9)
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.playlist_insert_omitted_after, 14)).assertExists()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_at_start)).assertExists()

        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_edit_position)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("20")
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.playlist_insert_omitted_before, 14)).assertExists()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(9)
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_at_end)).assertExists()
    }

    @Test
    fun `changed sources mark the preview stale and invalid selections cannot be previewed`() {
        var songs by mutableStateOf(songs(6))
        var selected by mutableStateOf(setOf(songs[1].stableKey()))
        composeRule.setContent {
            MaterialTheme {
                PlaylistInsertDialog(songs = songs, selectedKeys = selected, onDismiss = {}, onConfirm = {})
            }
        }

        composeRule.onNode(hasSetTextAction()).performTextReplacement("3")
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_position_summary, 3)).assertExists()

        songs = songs.dropLast(1)
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_stale)).assertExists()

        selected = setOf("missing")
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_invalid_selection)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_insert_preview_action)).assertIsNotEnabled()
    }

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun plural(id: Int, count: Int): String = context.resources.getQuantityString(id, count, count)

    private fun songs(count: Int) = (1..count).map { index ->
        SongItem(
            id = index.toLong(),
            name = "Song $index",
            artist = "Artist $index",
            album = "Album",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = if (index % 2 == 0) "https://example.com/$index.jpg" else null
        )
    }
}
