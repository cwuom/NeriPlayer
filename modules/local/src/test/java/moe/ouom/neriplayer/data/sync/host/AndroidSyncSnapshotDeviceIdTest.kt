package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlaybackSyncSnapshot
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.platform.bilibili.skip.BiliVideoSkipRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

class AndroidSyncSnapshotDeviceIdTest {
    @Before
    fun installCoverMapper() {
        CoverUrlMapper.installForTest(CoverUrlMapper.createForTest())
    }

    @After
    fun clearCoverMapper() {
        CoverUrlMapper.installForTest(null)
    }

    @Test
    fun `snapshot confirms one device identity for every retained history entry`() {
        val fixture = Fixture()
        val history = (1L..1501L).map(::entry)
        `when`(fixture.history.syncSnapshot()).thenReturn(history)

        val snapshot = fixture.builder.build(fixture.context, 0L)

        assertEquals("device", snapshot.deviceId)
        assertEquals(history.map { it.id }, snapshot.recentPlays.map { it.songId })
        assertEquals(setOf("device"), snapshot.recentPlays.map { it.deviceId }.toSet())
        verify(fixture.storage, times(1)).getOrCreateDeviceId()
    }

    @Test
    fun `each new snapshot confirms its current device identity`() {
        val fixture = Fixture()
        `when`(fixture.history.syncSnapshot()).thenReturn(listOf(entry(1L), entry(2L)))
        `when`(fixture.storage.getOrCreateDeviceId()).thenReturn("first-device", "second-device")

        val first = fixture.builder.build(fixture.context, 0L)
        val second = fixture.builder.build(fixture.context, 0L)

        assertEquals("first-device", first.deviceId)
        assertEquals(setOf("first-device"), first.recentPlays.map { it.deviceId }.toSet())
        assertEquals("second-device", second.deviceId)
        assertEquals(setOf("second-device"), second.recentPlays.map { it.deviceId }.toSet())
        verify(fixture.storage, times(2)).getOrCreateDeviceId()
    }

    @Test
    fun `failed device identity confirmation cannot produce a snapshot`() {
        val fixture = Fixture()
        val failure = IllegalStateException("Device identity was not persisted")
        `when`(fixture.storage.getOrCreateDeviceId()).thenThrow(failure)

        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            fixture.builder.build(fixture.context, 0L)
        })
        verify(fixture.storage, times(1)).getOrCreateDeviceId()
    }

    private fun entry(id: Long) = PlayedEntry(
        id = id, name = "song $id", artist = "artist", album = "netease", albumId = 1L,
        durationMs = 100L, coverUrl = null, playedAt = id
    )

    private class Fixture {
        val context = mock(Context::class.java)
        val storage = mock(SecureTokenStorage::class.java)
        val history = mock(PlayHistoryRepository::class.java)
        private val playlists = mock(LocalPlaylistRepository::class.java)
        private val favorites = mock(FavoritePlaylistRepository::class.java)
        private val usage = mock(PlaylistUsageRepository::class.java)
        private val localStats = mock(LocalPlaylistPlaybackStatsRepository::class.java)
        private val biliSkip = mock(BiliVideoSkipRepository::class.java)
        val builder = AndroidSyncSnapshotBuilder(storage, playlists, favorites, history, usage, localStats, biliSkip)

        init {
            `when`(playlists.playlists).thenReturn(MutableStateFlow<List<LocalPlaylist>>(emptyList()))
            `when`(storage.getOrCreateDeviceId()).thenReturn("device")
            `when`(storage.getLyricOverrides()).thenReturn(emptyList())
            `when`(storage.getLegacyLyricCandidates()).thenReturn(emptyList())
            `when`(favorites.getSyncSnapshots()).thenReturn(emptyList())
            `when`(history.syncSnapshot()).thenReturn(emptyList())
            `when`(usage.syncStatsAndDeletions()).thenReturn(emptyList<moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageStat>() to emptyList())
            `when`(localStats.syncSnapshot()).thenReturn(LocalPlaylistPlaybackSyncSnapshot(emptyList(), emptyList()))
            `when`(biliSkip.snapshot()).thenReturn(emptyList())
        }
    }
}
