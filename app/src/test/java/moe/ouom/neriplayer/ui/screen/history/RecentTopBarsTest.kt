package moe.ouom.neriplayer.ui.screen.history

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class RecentTopBarsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val songs = (1L..3L).map { id ->
        SongItem(
            id = id,
            name = "Song $id",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 60_000L,
            coverUrl = null
        )
    }
    private val events = mutableListOf<String>()

    @Test
    fun `browse bar plays shuffles clears and disables playback actions without songs`() {
        var displayed by mutableStateOf(songs)
        var hasHistory by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                RecentBrowseTopBar(
                    displayedSongs = displayed,
                    hasHistory = hasHistory,
                    onBack = { events += "back" },
                    onToggleSearch = { events += "search" },
                    onSongClick = { list, index -> events += "play:${list.size}:$index" },
                    onRequestClear = { events += "clear" }
                )
            }
        }

        clickAction(CoreCommonR.string.cd_back)
        clickAction(CoreCommonR.string.cd_search)
        clickAction(CoreCommonR.string.cd_play_all)
        clickAction(CoreCommonR.string.cd_shuffle)
        clickAction(CoreCommonR.string.cd_clear)

        assertEquals(listOf("back", "search", "play:3:0"), events.take(3))
        val shuffled = events[3].removePrefix("play:3:").toInt()
        assertTrue(shuffled in songs.indices)
        assertEquals("clear", events[4])

        displayed = emptyList()
        hasHistory = false
        listOf(CoreCommonR.string.cd_play_all, CoreCommonR.string.cd_shuffle, CoreCommonR.string.cd_clear)
            .forEach { res ->
                composeRule.onNodeWithContentDescription(context.getString(res))
                    .assertIsNotEnabled()
                    .performClick()
            }
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_search))
            .assertIsEnabled()
        assertEquals(5, events.size)
    }

    @Test
    fun `selection bar toggles select all and acts only on visible selected songs`() {
        var selected by mutableStateOf(setOf(songs[1].stableKey()))
        val deleted = mutableListOf<List<SongItem>>()
        val played = mutableListOf<List<SongItem>>()
        composeRule.setContent {
            MaterialTheme {
                RecentSelectionTopBar(
                    displayedSongs = songs,
                    selectedKeys = selected,
                    onExitSelection = { events += "exit" },
                    onSelectedKeysChange = { selected = it },
                    onRequestDelete = { deleted += it },
                    onPlaySelected = { played += it }
                )
            }
        }

        composeRule.onNodeWithText(selectedCount(1)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.action_delete))
            .performClick()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.player_play_selected))
            .performClick()
        assertEquals(listOf(listOf(songs[1])), deleted)
        assertEquals(listOf(listOf(songs[1])), played)

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_select_all)).performClick()
        assertEquals(songs.map { it.stableKey() }.toSet(), selected)
        composeRule.onNodeWithText(selectedCount(3)).assertExists()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_deselect_all)).performClick()
        assertEquals(emptySet<String>(), selected)
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.action_delete))
            .assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.player_play_selected))
            .assertIsNotEnabled()

        selected = setOf("hidden-by-search")
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.action_delete))
            .performClick()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.player_play_selected))
            .performClick()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_exit_select))
            .performClick()

        assertEquals(1, deleted.size)
        assertEquals(1, played.size)
        assertEquals(listOf("exit"), events)
    }

    @Test
    fun `toggling the last selected song ends selection mode`() {
        assertEquals(setOf("a", "b"), recentSelectionAfterToggle(true, setOf("a"), "b"))
        assertEquals(setOf("b"), recentSelectionAfterToggle(true, setOf("a", "b"), "a"))
        assertNull(recentSelectionAfterToggle(true, setOf("a"), "a"))
        assertEquals(emptySet<String>(), recentSelectionAfterToggle(false, setOf("a"), "a"))
    }

    private fun clickAction(contentDescriptionRes: Int) {
        composeRule.onNodeWithContentDescription(context.getString(contentDescriptionRes)).performClick()
    }

    private fun selectedCount(count: Int): String =
        context.resources.getQuantityString(CoreCommonR.plurals.common_selected_count, count, count)
}
