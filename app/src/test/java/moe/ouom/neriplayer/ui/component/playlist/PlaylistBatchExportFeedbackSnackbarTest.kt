package moe.ouom.neriplayer.ui.component.playlist

import android.content.Context
import android.content.res.Resources
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylistSongAddResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistBatchExportFeedbackSnackbarTest {
    private val host = SnackbarHostState()
    private val repository = mock(LocalPlaylistRepository::class.java)
    private val context = feedbackContext()

    @Test
    fun `exporting into a new playlist offers an undo that deletes it`() = runTest {
        val playlist = LocalPlaylist(id = 9L, name = "Road trip", songs = mutableListOf(song(1L), song(2L)))
        doAnswer { true }.`when`(repository).deletePlaylist(9L)

        backgroundScope.showPlaylistBatchExportCreatedResult(context, host, repository, Result.success(playlist))
        runCurrent()

        val prompt = host.currentSnackbarData!!.visuals
        assertEquals("exported:2,Road trip", prompt.message)
        assertEquals("Undo", prompt.actionLabel)
        assertTrue(prompt.withDismissAction)
        assertEquals(SnackbarDuration.Long, prompt.duration)

        host.currentSnackbarData!!.performAction()
        runCurrent()

        verify(repository).deletePlaylist(9L)
        assertEquals("Undid export to Road trip", host.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun `failed undo of a new playlist is reported`() = runTest {
        val playlist = LocalPlaylist(id = 9L, name = "Road trip", songs = mutableListOf(song(1L)))
        doAnswer { false }.`when`(repository).deletePlaylist(9L)

        backgroundScope.showPlaylistBatchExportCreatedResult(context, host, repository, Result.success(playlist))
        runCurrent()
        host.currentSnackbarData!!.performAction()
        runCurrent()

        assertEquals("Could not undo export", host.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun `adding into an existing playlist undoes only the added songs`() = runTest {
        val added = listOf(song(3L), song(4L))

        backgroundScope.showPlaylistBatchExportAddedResult(
            context = context,
            snackbarHostState = host,
            repository = repository,
            targetPlaylistId = 5L,
            targetPlaylistName = "Favorites",
            result = Result.success(LocalPlaylistSongAddResult(added))
        )
        runCurrent()
        assertEquals("exported:2,Favorites", host.currentSnackbarData?.visuals?.message)

        host.currentSnackbarData!!.performAction()
        runCurrent()

        verify(repository).removeSongsFromPlaylistByIdentity(5L, added)
        assertEquals("Undid export to Favorites", host.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun `undoing an export that added nothing leaves the playlist untouched`() = runTest {
        backgroundScope.showPlaylistBatchExportAddedSongs(
            context = context,
            snackbarHostState = host,
            repository = repository,
            targetPlaylistId = 5L,
            targetPlaylistName = "Favorites",
            addedSongs = emptyList()
        )
        runCurrent()
        assertEquals("exported:0,Favorites", host.currentSnackbarData?.visuals?.message)

        host.currentSnackbarData!!.performAction()
        runCurrent()

        verify(repository, never()).removeSongsFromPlaylistByIdentity(anyLong(), anyList())
        assertEquals("Undid export to Favorites", host.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun `dismissing the export snackbar keeps the exported songs`() = runTest {
        val playlist = LocalPlaylist(id = 9L, name = "Road trip", songs = mutableListOf(song(1L)))

        backgroundScope.showPlaylistBatchExportCreatedPlaylist(context, host, repository, playlist)
        runCurrent()
        host.currentSnackbarData!!.dismiss()
        runCurrent()

        verify(repository, never()).deletePlaylist(anyLong())
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun `failed exports report the failure`() = runTest {
        val createdHost = SnackbarHostState()
        val addedHost = SnackbarHostState()

        backgroundScope.showPlaylistBatchExportCreatedResult(
            context,
            createdHost,
            repository,
            Result.failure(IllegalStateException("full"))
        )
        backgroundScope.showPlaylistBatchExportAddedResult(
            context = context,
            snackbarHostState = addedHost,
            repository = repository,
            targetPlaylistId = 5L,
            targetPlaylistName = "Favorites",
            result = Result.failure(IllegalStateException("full"))
        )
        runCurrent()

        assertEquals("Export failed", createdHost.currentSnackbarData?.visuals?.message)
        assertNull(createdHost.currentSnackbarData?.visuals?.actionLabel)
        assertEquals("Export failed", addedHost.currentSnackbarData?.visuals?.message)
    }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null
    )

    private fun feedbackContext(): Context {
        val resources = mock(Resources::class.java)
        `when`(
            resources.getQuantityString(
                eq(CoreCommonR.plurals.playlist_batch_export_success),
                anyInt(),
                anyInt(),
                anyString()
            )
        ).thenAnswer { invocation -> "exported:${invocation.arguments.drop(2).joinToString(",")}" }

        val context = mock(Context::class.java)
        `when`(context.resources).thenReturn(resources)
        `when`(context.getString(CoreCommonR.string.playlist_batch_export_undo)).thenReturn("Undo")
        `when`(context.getString(CoreCommonR.string.playlist_export_failed)).thenReturn("Export failed")
        `when`(context.getString(CoreCommonR.string.playlist_batch_export_undo_failed))
            .thenReturn("Could not undo export")
        `when`(context.getString(eq(CoreCommonR.string.playlist_batch_export_undone), anyString()))
            .thenAnswer { invocation -> "Undid export to ${invocation.getArgument<String>(1)}" }
        return context
    }
}
