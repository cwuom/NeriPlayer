package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackCursor
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackBucketOrder
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsCaptureBarrier
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.platform.bilibili.skip.BiliVideoSkipRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer

class AndroidSyncLocalDataStoreRecoveryTest {
    @Test
    fun `runtime spool drains after repository recovery before the session mutation ticket is read`() = runTest {
        val fixture = Fixture().also { it.configureReady() }
        val events = mutableListOf<String>()
        var mutationVersion = 7L
        doAnswer { events += "initialized"; true }.`when`(fixture.stats).awaitInitialized()
        doAnswer { events += "room-flushed"; Unit }.`when`(fixture.stats).flushPendingWrites()
        `when`(fixture.storage.getSyncMutationVersion()).thenAnswer { mutationVersion }
        try {
            PlaybackStatsCaptureBarrier.install { context ->
                assertSame(fixture.context, context)
                events += "spool-flushed"
                mutationVersion++
            }
            assertTrue(fixture.store.awaitInitialized())
            assertEquals(listOf("initialized", "spool-flushed", "room-flushed"), events)
            assertEquals(8L, fixture.store.mutationVersion())
        } finally { PlaybackStatsCaptureBarrier.install {} }
    }

    @Test
    fun `unavailable repository never drains runtime spool and failed spool stops readiness`() = runTest {
        val fixture = Fixture().also { it.configureReady() }
        var calls = 0
        try {
            PlaybackStatsCaptureBarrier.install { calls++; throw java.io.IOException("spool unavailable") }
            `when`(fixture.stats.awaitInitialized()).thenReturn(false)
            assertFalse(fixture.store.awaitInitialized())
            assertEquals(0, calls)
            `when`(fixture.stats.awaitInitialized()).thenReturn(true)
            assertTrue(runCatching { fixture.store.awaitInitialized() }.exceptionOrNull() is java.io.IOException)
            assertEquals(1, calls)
            verify(fixture.stats, never()).flushPendingWrites()
        } finally { PlaybackStatsCaptureBarrier.install {} }
    }

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

        assertFalse(fixture.store.apply(unreadDataset(), true, 7L))
        verify(fixture.playlists, never()).applySyncedPlaylistsIfUnchanged(emptyList(), 7L)
        verify(fixture.favorites, never()).replaceFavoritesFromSyncIfUnchanged(emptyList(), 7L)
        verify(fixture.storage, never()).setLyricOverridesIfMutationVersion(7L, emptyList())
    }

    @Test
    fun `remote apply retains mutation checks after recovery succeeds`() = runTest {
        val fixture = Fixture().also { it.configureReady() }
        `when`(fixture.playlists.playlists).thenReturn(MutableStateFlow<List<LocalPlaylist>>(emptyList()))
        `when`(fixture.playlists.applySyncedPlaylistsIfUnchanged(emptyList(), 7L)).thenReturn(false)

        assertFalse(fixture.store.apply(unreadDataset(), true, 7L))
        val writes = inOrder(fixture.storage, fixture.playlists)
        writes.verify(fixture.storage).mergePlaylistUsageDeletionBarriersIfMutationVersion(7L, emptyList())
        writes.verify(fixture.storage).setLyricOverridesIfMutationVersion(7L, emptyList())
        writes.verify(fixture.storage).setPlaylistDeletionStateIfMutationVersion(7L, emptyList(), false)
        writes.verify(fixture.storage).setDeletionStateIfMutationVersion(7L, emptyList(), emptyList())
        writes.verify(fixture.playlists).applySyncedPlaylistsIfUnchanged(emptyList(), 7L)
        verify(fixture.favorites, never()).replaceFavoritesFromSyncIfUnchanged(emptyList(), 7L)
    }

    @Test
    fun `recovered repositories cannot bypass a rejected permanent lyric mutation ticket`() = runTest {
        val fixture = Fixture().also { it.configureReady() }
        `when`(fixture.storage.setLyricOverridesIfMutationVersion(7L, emptyList())).thenReturn(false)

        assertFalse(fixture.store.apply(unreadDataset(), true, 7L))
        verify(fixture.storage).mergePlaylistUsageDeletionBarriersIfMutationVersion(7L, emptyList())
        verify(fixture.storage).setLyricOverridesIfMutationVersion(7L, emptyList())
        verify(fixture.storage, never()).setDeletionStateIfMutationVersion(7L, emptyList(), emptyList())
        verify(fixture.playlists, never()).applySyncedPlaylistsIfUnchanged(emptyList(), 7L)
        verify(fixture.favorites, never()).replaceFavoritesFromSyncIfUnchanged(emptyList(), 7L)
    }

    private fun unreadDataset(): SyncDataset = SyncDataset(SyncData(), object : SyncPlaybackSource {
        override fun openTracks(): SyncPlaybackCursor<SyncTrackStat> = error("rejected apply must not read statistics")
        override fun openBuckets(order: SyncPlaybackBucketOrder): SyncPlaybackCursor<SyncPlaybackStatBucket> = error("rejected apply must not read statistics")
        override fun close() = Unit
    }, 0L)

    @Test
    fun `unavailable history usage and local playlist counters each block synchronization`() = runTest {
        for (index in 0..2) {
            val fixture = Fixture().also { it.configureReady() }
            when (index) {
                0 -> `when`(fixture.history.awaitInitialized()).thenReturn(false)
                1 -> `when`(fixture.usage.awaitInitialized()).thenReturn(false)
                else -> `when`(fixture.localStats.awaitInitialized()).thenReturn(false)
            }
            assertFalse(fixture.store.awaitInitialized())
            verify(fixture.stats, never()).flushPendingWrites()
        }
    }

    private class Fixture {
        val context = mock(Context::class.java)
        val playlists = mock(LocalPlaylistRepository::class.java)
        val favorites = mock(FavoritePlaylistRepository::class.java)
        val stats = mock(PlaybackStatsRepository::class.java)
        val storage = mock(SecureTokenStorage::class.java)
        val history = mock(PlayHistoryRepository::class.java)
        val usage = mock(PlaylistUsageRepository::class.java)
        val localStats = mock(LocalPlaylistPlaybackStatsRepository::class.java)
        val store = AndroidSyncLocalDataStore(
            context, storage, playlists, favorites,
            history, stats, usage, localStats,
            mock(BiliVideoSkipRepository::class.java),
            readLocalizedContext = { context }
        )

        suspend fun configureReady() {
            `when`(playlists.awaitInitialized()).thenReturn(true)
            `when`(favorites.awaitInitialized()).thenReturn(true)
            `when`(stats.awaitInitialized()).thenReturn(true)
            `when`(history.awaitInitialized()).thenReturn(true)
            `when`(usage.awaitInitialized()).thenReturn(true)
            `when`(localStats.awaitInitialized()).thenReturn(true)
            `when`(storage.mergePlaylistUsageDeletionBarriersIfMutationVersion(7L, emptyList())).thenReturn(true)
            `when`(storage.setLyricOverridesIfMutationVersion(7L, emptyList())).thenReturn(true)
            `when`(storage.setPlaylistDeletionStateIfMutationVersion(7L, emptyList())).thenReturn(true)
            `when`(storage.setPlaylistDeletionStateIfMutationVersion(7L, emptyList(), false)).thenReturn(true)
            `when`(storage.setDeletionStateIfMutationVersion(7L, emptyList(), emptyList())).thenReturn(true)
        }
    }
}
