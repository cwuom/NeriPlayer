package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.platform.bilibili.skip.BiliVideoSkipRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class AndroidSyncLocalDataStoreRecoveryTest {
    @Test
    fun `unavailable playlists stop sync before other repositories are read`() = runTest {
        val fixture = Fixture().also { it.configureReady() }
        `when`(fixture.playlists.awaitInitialized()).thenReturn(false)

        assertFalse(fixture.store.awaitInitialized())
        verify(fixture.favorites, never()).awaitInitialized()
        verify(fixture.stats, never()).flushPendingWrites()
    }

    @Test
    fun `unavailable favorites stop sync without uploading an empty collection`() = runTest {
        val fixture = Fixture().also { it.configureReady() }
        `when`(fixture.favorites.awaitInitialized()).thenReturn(false)

        assertFalse(fixture.store.awaitInitialized())
        verify(fixture.stats, never()).awaitInitialized()
        verify(fixture.stats, never()).flushPendingWrites()
    }

    @Test
    fun `unavailable statistics stop sync before persistence`() = runTest {
        val fixture = Fixture().also { it.configureReady() }
        `when`(fixture.stats.awaitInitialized()).thenReturn(false)

        assertFalse(fixture.store.awaitInitialized())
        verify(fixture.stats, never()).flushPendingWrites()
    }

    @Test
    fun `failed pending write prevents sync and a later retry can proceed`() = runTest {
        val fixture = Fixture().also { it.configureReady() }
        `when`(fixture.stats.hasPendingWrites()).thenReturn(true, false)

        assertFalse(fixture.store.awaitInitialized())
        assertTrue(fixture.store.awaitInitialized())
        verify(fixture.stats, times(2)).flushPendingWrites()
    }

    @Test
    fun `remote apply cannot replace local rows when recovery is unavailable`() = runTest {
        val fixture = Fixture().also { it.configureReady() }
        `when`(fixture.favorites.awaitInitialized()).thenReturn(false)

        assertFalse(fixture.store.apply(SyncData(), true, 7L))
        verify(fixture.playlists, never()).applySyncedPlaylistsIfUnchanged(emptyList(), 7L)
        verify(fixture.favorites, never()).replaceFavoritesFromSyncIfUnchanged(emptyList(), 7L)
    }

    @Test
    fun `remote apply retains mutation checks after recovery succeeds`() = runTest {
        val fixture = Fixture().also { it.configureReady() }
        `when`(fixture.playlists.playlists).thenReturn(MutableStateFlow<List<LocalPlaylist>>(emptyList()))
        `when`(fixture.playlists.applySyncedPlaylistsIfUnchanged(emptyList(), 7L)).thenReturn(false)

        assertFalse(fixture.store.apply(SyncData(), true, 7L))
        verify(fixture.playlists).applySyncedPlaylistsIfUnchanged(emptyList(), 7L)
        verify(fixture.storage, never()).setDeletionStateIfMutationVersion(7L, emptyList(), emptyList())
    }

    private class Fixture {
        val context = mock(Context::class.java)
        val playlists = mock(LocalPlaylistRepository::class.java)
        val favorites = mock(FavoritePlaylistRepository::class.java)
        val stats = mock(PlaybackStatsRepository::class.java)
        val storage = mock(SecureTokenStorage::class.java)
        val store = AndroidSyncLocalDataStore(
            context, storage, playlists, favorites,
            mock(PlayHistoryRepository::class.java), stats,
            mock(PlaylistUsageRepository::class.java),
            mock(LocalPlaylistPlaybackStatsRepository::class.java),
            mock(BiliVideoSkipRepository::class.java),
            readLocalizedContext = { context }
        )

        suspend fun configureReady() {
            `when`(playlists.awaitInitialized()).thenReturn(true)
            `when`(favorites.awaitInitialized()).thenReturn(true)
            `when`(stats.awaitInitialized()).thenReturn(true)
        }
    }
}
