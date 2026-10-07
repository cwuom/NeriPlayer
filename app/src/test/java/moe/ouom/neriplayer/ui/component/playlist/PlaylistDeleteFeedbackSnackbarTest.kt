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
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylistDeleteResult
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylistSongDeleteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDeleteFeedbackSnackbarTest {
    private val host = SnackbarHostState()
    private val repository = mock(LocalPlaylistRepository::class.java)
    private val context = feedbackContext()

    @Test
    fun `deleting one playlist offers an undo that restores it`() = runTest {
        val deleted = listOf(LocalPlaylistDeleteResult(LocalPlaylist(id = 7L, name = "Mix"), index = 0))
        doAnswer { true }.`when`(repository).restoreDeletedPlaylists(deleted)

        backgroundScope.showPlaylistDeleteResult(context, host, repository, Result.success(deleted))
        runCurrent()

        val prompt = host.currentSnackbarData!!.visuals
        assertEquals("playlists-deleted:1,Mix", prompt.message)
        assertEquals("Undo", prompt.actionLabel)
        assertTrue(prompt.withDismissAction)
        assertEquals(SnackbarDuration.Long, prompt.duration)

        host.currentSnackbarData!!.performAction()
        runCurrent()

        verify(repository).restoreDeletedPlaylists(deleted)
        assertEquals("playlists-restored:1,Mix", host.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun `deleting several playlists omits the name and dismissing keeps them deleted`() = runTest {
        val deleted = listOf(
            LocalPlaylistDeleteResult(LocalPlaylist(id = 7L, name = "Mix"), index = 0),
            LocalPlaylistDeleteResult(LocalPlaylist(id = 8L, name = "Focus"), index = 1)
        )

        backgroundScope.showPlaylistDeletedSnackbar(context, host, repository, deleted)
        runCurrent()
        assertEquals("playlists-deleted:2,", host.currentSnackbarData?.visuals?.message)

        host.currentSnackbarData!!.dismiss()
        runCurrent()

        verify(repository, never()).restoreDeletedPlaylists(anyList())
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun `failed playlist undo is reported`() = runTest {
        val deleted = listOf(LocalPlaylistDeleteResult(LocalPlaylist(id = 7L, name = "Mix"), index = 0))
        doAnswer { false }.`when`(repository).restoreDeletedPlaylists(deleted)

        backgroundScope.showPlaylistDeletedSnackbar(context, host, repository, deleted)
        runCurrent()
        host.currentSnackbarData!!.performAction()
        runCurrent()

        assertEquals("Could not restore playlists", host.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun `playlist undo that throws is reported as failed`() = runTest {
        val deleted = listOf(LocalPlaylistDeleteResult(LocalPlaylist(id = 7L, name = "Mix"), index = 0))
        doAnswer { throw IllegalStateException("database closed") }
            .`when`(repository).restoreDeletedPlaylists(deleted)

        backgroundScope.showPlaylistDeletedSnackbar(context, host, repository, deleted)
        runCurrent()
        host.currentSnackbarData!!.performAction()
        runCurrent()

        assertEquals("Could not restore playlists", host.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun `empty or failed playlist deletes report the failure`() = runTest {
        val emptyHost = SnackbarHostState()
        val failedHost = SnackbarHostState()

        backgroundScope.showPlaylistDeleteResult(context, emptyHost, repository, Result.success(emptyList()))
        backgroundScope.showPlaylistDeleteResult(
            context,
            failedHost,
            repository,
            Result.failure(IllegalStateException("locked"))
        )
        runCurrent()

        assertEquals("Could not delete playlists", emptyHost.currentSnackbarData?.visuals?.message)
        assertNull(emptyHost.currentSnackbarData?.visuals?.actionLabel)
        assertEquals("Could not delete playlists", failedHost.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun `deleting songs offers an undo that restores them`() = runTest {
        val deleted = listOf(
            LocalPlaylistSongDeleteResult(playlistId = 7L, song = song(1L), index = 0),
            LocalPlaylistSongDeleteResult(playlistId = 7L, song = song(2L), index = 1)
        )
        doAnswer { true }.`when`(repository).restoreDeletedSongs(deleted)

        backgroundScope.showPlaylistSongDeleteResult(context, host, repository, Result.success(deleted))
        runCurrent()

        val prompt = host.currentSnackbarData!!.visuals
        assertEquals("songs-deleted:2", prompt.message)
        assertEquals("Undo", prompt.actionLabel)
        assertEquals(SnackbarDuration.Long, prompt.duration)

        host.currentSnackbarData!!.performAction()
        runCurrent()

        verify(repository).restoreDeletedSongs(deleted)
        assertEquals("songs-restored:2", host.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun `failed song undo is reported and dismissing skips the undo`() = runTest {
        val deleted = listOf(LocalPlaylistSongDeleteResult(playlistId = 7L, song = song(1L), index = 0))
        val dismissedHost = SnackbarHostState()
        doAnswer { false }.`when`(repository).restoreDeletedSongs(deleted)

        backgroundScope.showPlaylistSongDeletedSnackbar(context, host, repository, deleted)
        backgroundScope.showPlaylistSongDeletedSnackbar(context, dismissedHost, repository, deleted)
        runCurrent()
        host.currentSnackbarData!!.performAction()
        dismissedHost.currentSnackbarData!!.dismiss()
        runCurrent()

        verify(repository).restoreDeletedSongs(deleted)
        assertEquals("Could not restore songs", host.currentSnackbarData?.visuals?.message)
        assertNull(dismissedHost.currentSnackbarData)
    }

    @Test
    fun `empty or failed song deletes report the failure`() = runTest {
        val emptyHost = SnackbarHostState()
        val failedHost = SnackbarHostState()

        backgroundScope.showPlaylistSongDeleteResult(context, emptyHost, repository, Result.success(emptyList()))
        backgroundScope.showPlaylistSongDeleteResult(
            context,
            failedHost,
            repository,
            Result.failure(IllegalStateException("locked"))
        )
        runCurrent()

        assertEquals("Could not delete songs", emptyHost.currentSnackbarData?.visuals?.message)
        assertEquals("Could not delete songs", failedHost.currentSnackbarData?.visuals?.message)
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
        stubPlural(resources, CoreCommonR.plurals.local_playlist_delete_snackbar, "playlists-deleted")
        stubPlural(resources, CoreCommonR.plurals.local_playlist_delete_undone, "playlists-restored")
        stubPlural(resources, CoreCommonR.plurals.local_playlist_delete_songs_snackbar, "songs-deleted")
        stubPlural(resources, CoreCommonR.plurals.local_playlist_delete_songs_undone, "songs-restored")

        val context = mock(Context::class.java)
        `when`(context.resources).thenReturn(resources)
        `when`(context.getString(CoreCommonR.string.playlist_batch_export_undo)).thenReturn("Undo")
        `when`(context.getString(CoreCommonR.string.local_playlist_delete_failed))
            .thenReturn("Could not delete playlists")
        `when`(context.getString(CoreCommonR.string.local_playlist_delete_undo_failed))
            .thenReturn("Could not restore playlists")
        `when`(context.getString(CoreCommonR.string.local_playlist_delete_songs_failed))
            .thenReturn("Could not delete songs")
        `when`(context.getString(CoreCommonR.string.local_playlist_delete_songs_undo_failed))
            .thenReturn("Could not restore songs")
        return context
    }

    private fun stubPlural(resources: Resources, pluralsId: Int, label: String) {
        `when`(resources.getQuantityString(eq(pluralsId), anyInt(), anyInt())).thenAnswer { invocation ->
            "$label:${invocation.arguments.drop(2).joinToString(",")}"
        }
        `when`(resources.getQuantityString(eq(pluralsId), anyInt(), anyInt(), anyString())).thenAnswer { invocation ->
            "$label:${invocation.arguments.drop(2).joinToString(",")}"
        }
    }
}
