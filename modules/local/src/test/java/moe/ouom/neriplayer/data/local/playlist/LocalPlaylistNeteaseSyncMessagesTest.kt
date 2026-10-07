package moe.ouom.neriplayer.data.local.playlist

import android.content.Context
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseLikeSyncPlan
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseLikeSyncResult
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`

class LocalPlaylistNeteaseSyncMessagesTest : LocalPlaylistRepositoryTestSupport() {
    private val client = mock(NeteaseClient::class.java)
    private val nothingToSync = NeteaseLikeSyncResult(
        totalSongs = 0,
        supportedSongs = 0,
        skippedUnsupported = 0,
        skippedExisting = 0,
        added = 0,
        failed = 0,
        message = "Nothing to sync"
    )

    @Test
    fun `syncing favorites without a favorites playlist reports that there is nothing to sync`() = runTest {
        val repository = repository(
            LocalPlaylist(id = 1L, name = "Road trip", songs = mutableListOf(remoteNeteaseSong(id = 11L)), modifiedAt = 1_000L)
        )

        assertEquals(nothingToSync, repository.syncFavoritesToNeteaseLiked(client))
        verifyNoInteractions(client)
    }

    @Test
    fun `syncing an empty favorites playlist reports that there is nothing to sync`() = runTest {
        val repository = repository(
            LocalPlaylist(id = FavoritesPlaylist.SYSTEM_ID, name = "Favorites", songs = mutableListOf(), modifiedAt = 1_000L)
        )

        assertEquals(nothingToSync, repository.syncFavoritesToNeteaseLiked(client))
        verifyNoInteractions(client)
    }

    @Test
    fun `liked sync plans explain unsupported songs and a missing login before any request`() = runTest {
        val repository = repository()

        val unsupported = repository.prepareNeteaseLikeSyncPlan(client, listOf(localSong(1)))
        val loggedOut = repository.prepareNeteaseLikeSyncPlan(client, listOf(remoteNeteaseSong(id = 42L)))

        assertEquals(plan(supported = 0, unsupported = 1, message = "No NetEase songs"), unsupported)
        assertEquals(plan(supported = 1, unsupported = 0, message = "Login required"), loggedOut)
        verify(client).hasLogin()
        verifyNoMoreInteractions(client)
    }

    private fun plan(supported: Int, unsupported: Int, message: String) = NeteaseLikeSyncPlan(
        totalSongs = 1,
        supportedSongs = supported,
        skippedUnsupported = unsupported,
        skippedExisting = 0,
        pendingSongs = emptyList(),
        compareSucceeded = false,
        message = message
    )

    private fun repository(vararg playlists: LocalPlaylist): LocalPlaylistRepository {
        val context: Context = mockContext()
        `when`(context.getString(CoreCommonR.string.local_playlist_sync_netease_empty)).thenReturn("Nothing to sync")
        `when`(context.getString(CoreCommonR.string.local_playlist_sync_netease_no_supported)).thenReturn("No NetEase songs")
        `when`(context.getString(CoreCommonR.string.playback_login_required)).thenReturn("Login required")
        return LocalPlaylistRepository.createForTest(
            context = context,
            file = File(tempFolder.root, "netease_sync_messages.json"),
            storage = RecordingStorage(primary = Gson().toJson(playlists.toList()))
        )
    }
}
