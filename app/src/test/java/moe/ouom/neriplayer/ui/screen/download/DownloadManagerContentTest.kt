package moe.ouom.neriplayer.ui.screen.download

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import moe.ouom.neriplayer.data.model.download.DownloadedSongDeletePhase
import moe.ouom.neriplayer.data.model.download.DownloadedSongDeleteProgress
import moe.ouom.neriplayer.data.model.download.DownloadedSongDeleteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class DownloadManagerContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val alpha = downloadedSong(1, name = "Alpha Track", artist = "Aurora", album = "Northern")
    private val beta = downloadedSong(2, name = "Beta Track", artist = "Basil", album = "Southern")
    private val gamma = downloadedSong(3, name = "Gamma Track", artist = "Cyan", album = "Northern")

    private class DeleteCall(
        val songs: List<DownloadedSong>,
        val deleteEntireLibrary: Boolean,
        val onResult: (DownloadedSongDeleteResult) -> Unit
    )

    private class Callbacks {
        var backCount = 0
        var openProgressCount = 0
        var refreshCount = 0
        val played = mutableListOf<DownloadedSong>()
        val dismissedFailures = mutableListOf<Long>()
        val deleteCalls = mutableListOf<DeleteCall>()
    }

    private fun setContent(
        songs: List<DownloadedSong>,
        legacyPreviewClips: Map<String, Long> = emptyMap(),
        isRefreshing: Boolean = false,
        deleteProgress: DownloadedSongDeleteProgress? = null,
        callbacks: Callbacks = Callbacks()
    ): Callbacks {
        composeRule.setContent {
            DownloadManagerContent(
                downloadedSongs = songs,
                legacyPreviewClips = legacyPreviewClips,
                isRefreshing = isRefreshing,
                deleteProgress = deleteProgress,
                deleteFailureDismissed = false,
                listState = LazyListState(),
                offlineMode = true,
                onBack = { callbacks.backCount++ },
                onOpenDownloadProgress = { callbacks.openProgressCount++ },
                onRefresh = { callbacks.refreshCount++ },
                onDismissDeleteFailure = { callbacks.dismissedFailures += it },
                onDeleteSongs = { selected, deleteEntireLibrary, onResult ->
                    callbacks.deleteCalls += DeleteCall(selected, deleteEntireLibrary, onResult)
                },
                onPlaySong = { callbacks.played += it }
            )
        }
        return callbacks
    }

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun plural(id: Int, count: Int, vararg args: Any): String =
        context.resources.getQuantityString(id, count, *args)

    @Test
    fun `renders library stats and songs and routes toolbar actions`() {
        val callbacks = setContent(
            songs = listOf(alpha, beta),
            legacyPreviewClips = mapOf(beta.filePath to beta.fileSize)
        )

        composeRule.onNodeWithText("Alpha Track").assertIsDisplayed()
        composeRule.onNodeWithText("Basil").assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()
        composeRule.onNodeWithText(string(CoreCommonR.string.download_legacy_preview_clip))
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.action_back)).performClick()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.download_progress)).performClick()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.action_refresh)).performClick()
        composeRule.onNodeWithText("Alpha Track").performClick()

        assertEquals(1, callbacks.backCount)
        assertEquals(1, callbacks.openProgressCount)
        assertEquals(1, callbacks.refreshCount)
        assertEquals(listOf(alpha), callbacks.played)
    }

    @Test
    fun `empty library and empty search show different hints`() {
        setContent(songs = listOf(alpha, beta))

        composeRule.onNodeWithText(string(CoreCommonR.string.download_search_hint))
            .performTextInput("northern")
        composeRule.onNodeWithText("Alpha Track").assertIsDisplayed()
        composeRule.onNodeWithText("Beta Track").assertDoesNotExist()

        composeRule.onNodeWithText("northern").performTextInput("-missing")
        composeRule.onNodeWithText(string(CoreCommonR.string.download_no_match)).assertIsDisplayed()
        composeRule.onNodeWithText(string(CoreCommonR.string.download_try_other_keywords))
            .assertIsDisplayed()
    }

    @Test
    fun `empty library shows download hint`() {
        setContent(songs = emptyList())

        composeRule.onNodeWithText(string(CoreCommonR.string.download_no_songs)).assertIsDisplayed()
        composeRule.onNodeWithText(string(CoreCommonR.string.download_songs_hint)).assertIsDisplayed()
    }

    @Test
    fun `selecting every song deletes the entire library and reports the result`() {
        val callbacks = setContent(songs = listOf(alpha, beta))

        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.action_multi_select))
            .performClick()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.action_select_all))
            .performClick()
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.download_selected_count, 2, 2))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.download_delete_selected))
            .performClick()
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.download_delete_selected_confirm, 2, 2))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_delete)).performClick()

        val call = callbacks.deleteCalls.single()
        assertEquals(listOf(alpha, beta), call.songs)
        assertTrue(call.deleteEntireLibrary)
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.action_multi_select))
            .assertIsDisplayed()

        composeRule.runOnIdle {
            call.onResult(DownloadedSongDeleteResult(deletedSongs = call.songs, failedSongs = emptyList()))
        }
        composeRule.waitForIdle()
        assertEquals(
            plural(CoreCommonR.plurals.local_files_delete_downloaded_success, 2, 2),
            ShadowToast.getTextOfLatestToast()
        )
    }

    @Test
    fun `long press selection deletes only chosen songs without full library flag`() {
        val callbacks = setContent(songs = listOf(alpha, beta, gamma))

        composeRule.onNodeWithText("Alpha Track").performTouchInput { longClick() }
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.download_selected_count, 1, 1))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Gamma Track").performClick()
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.download_selected_count, 2, 2))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.download_delete_selected))
            .performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_delete)).performClick()

        val call = callbacks.deleteCalls.single()
        assertEquals(listOf(alpha, gamma), call.songs)
        assertFalse(call.deleteEntireLibrary)
    }

    @Test
    fun `select all toggle clears the selection and exit leaves selection mode`() {
        setContent(songs = listOf(alpha, beta))

        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.action_multi_select))
            .performClick()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.action_select_all))
            .performClick()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.action_deselect_all))
            .performClick()
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.download_selected_count, 0, 0))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.download_exit_selection))
            .performClick()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.action_multi_select))
            .assertIsDisplayed()
    }

    @Test
    fun `cancelling multi delete keeps the selection`() {
        val callbacks = setContent(songs = listOf(alpha, beta))

        composeRule.onNodeWithText("Beta Track").performTouchInput { longClick() }
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.download_delete_selected))
            .performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_cancel)).performClick()

        assertTrue(callbacks.deleteCalls.isEmpty())
        composeRule.onNodeWithText(plural(CoreCommonR.plurals.download_selected_count, 1, 1))
            .assertIsDisplayed()
    }

    @Test
    fun `single delete confirms one song and shows partial failure message`() {
        val callbacks = setContent(songs = listOf(alpha, beta))

        composeRule.onNodeWithText("Alpha Track").assertIsDisplayed()
        firstSongDeleteButton().performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.download_delete_confirm, alpha.name))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_delete)).performClick()

        val call = callbacks.deleteCalls.single()
        assertEquals(listOf(alpha), call.songs)
        assertFalse(call.deleteEntireLibrary)

        composeRule.runOnIdle {
            call.onResult(
                DownloadedSongDeleteResult(deletedSongs = listOf(alpha), failedSongs = listOf(beta))
            )
        }
        composeRule.waitForIdle()
        assertEquals(
            plural(CoreCommonR.plurals.local_files_delete_downloaded_partial, 1, 1, 1),
            ShadowToast.getTextOfLatestToast()
        )
    }

    @Test
    fun `cancelling single delete does not delete`() {
        val callbacks = setContent(songs = listOf(alpha))

        firstSongDeleteButton().performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_cancel)).performClick()

        assertTrue(callbacks.deleteCalls.isEmpty())
        composeRule.onNodeWithText(string(CoreCommonR.string.download_delete_confirm, alpha.name))
            .assertDoesNotExist()
    }

    @Test
    fun `running deletion disables delete buttons`() {
        setContent(
            songs = listOf(alpha),
            deleteProgress = DownloadedSongDeleteProgress(
                deleteId = 7L,
                phase = DownloadedSongDeletePhase.DELETING_REFERENCES,
                requestedSongCount = 1,
                totalReferenceCount = 2,
                completedReferenceCount = 1,
                failedReferenceCount = 0
            )
        )

        firstSongDeleteButton().assertIsNotEnabled()
    }

    @Test
    fun `delete result messages cover cleanup success partial and failure`() {
        val resources = context.resources
        assertEquals(
            plural(CoreCommonR.plurals.local_files_delete_downloaded_cleanup_pending, 2, 2),
            downloadDeleteResultMessage(
                resources,
                DownloadedSongDeleteResult(listOf(alpha), listOf(beta), physicalCleanupPending = true)
            )
        )
        assertEquals(
            plural(CoreCommonR.plurals.local_files_delete_downloaded_success, 1, 1),
            downloadDeleteResultMessage(resources, DownloadedSongDeleteResult(listOf(alpha), emptyList()))
        )
        assertEquals(
            plural(CoreCommonR.plurals.local_files_delete_downloaded_partial, 2, 2, 1),
            downloadDeleteResultMessage(
                resources,
                DownloadedSongDeleteResult(listOf(alpha, gamma), listOf(beta))
            )
        )
        assertEquals(
            string(CoreCommonR.string.local_files_delete_downloaded_failed),
            downloadDeleteResultMessage(resources, DownloadedSongDeleteResult(emptyList(), listOf(beta)))
        )
    }

    @Test
    fun `filter matches custom and original metadata ignoring case`() {
        val renamed = alpha.copy(customName = "Renamed Song", customArtist = "Custom Singer")
        val songs = listOf(renamed, beta, gamma)

        assertEquals(songs, filterDownloadedSongs(songs, "  "))
        assertEquals(listOf(renamed), filterDownloadedSongs(songs, "renamed"))
        assertEquals(listOf(renamed), filterDownloadedSongs(songs, "custom singer"))
        assertEquals(listOf(renamed), filterDownloadedSongs(songs, "alpha"))
        assertEquals(listOf(beta), filterDownloadedSongs(songs, "BASIL"))
        assertEquals(listOf(renamed, gamma), filterDownloadedSongs(songs, "northern"))
    }

    @Test
    fun `selection state drops songs that disappeared from the library`() {
        val state = DownloadManagerSelectionState()
        state.startSelectionFrom(alpha)
        state.toggleSong(beta.deletionIdentity(), selected = true)

        state.sanitize(listOf(beta))
        assertTrue(state.selectionMode)
        assertEquals(setOf(beta.deletionIdentity()), state.selectedSongKeys)

        state.sanitize(emptyList())
        assertFalse(state.selectionMode)
        assertTrue(state.selectedSongKeys.isEmpty())
    }

    @Test
    fun `selection state ignores delete requests without selected songs`() {
        val state = DownloadManagerSelectionState()
        state.enterSelectionMode()

        state.requestDeleteSelected(listOf(alpha))
        assertFalse(state.showMultiDeleteDialog)

        state.toggleSong("missing", selected = true)
        state.requestDeleteSelected(listOf(alpha))
        assertFalse(state.showMultiDeleteDialog)
        assertTrue(state.songsPendingDelete.isEmpty())

        state.confirmSingleDelete { error("no song pending") }
        assertNull(state.songToDelete)
        assertEquals(0, state.deletingSongCount)
    }

    private fun firstSongDeleteButton() =
        composeRule.onAllNodesWithContentDescription(string(CoreCommonR.string.download_delete))[0]

    private fun downloadedSong(
        id: Long,
        name: String,
        artist: String,
        album: String
    ) = DownloadedSong(
        id = id,
        name = name,
        artist = artist,
        album = album,
        filePath = "/music/$id.flac",
        fileSize = 1_000L * id,
        downloadTime = 1_700_000_000_000L + id
    )
}
