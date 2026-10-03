package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlaybackSyncSnapshot
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.platform.bilibili.skip.BiliVideoSkipRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class AndroidSyncSnapshotLegacyLyricsTest {
    @Test
    fun `snapshot includes durable old lyrics even after their container payload was omitted`() {
        val fixture = Fixture()
        val legacy = SyncSong(id = 7, album = "Netease", matchedLyric = "old edited lyrics", originalLyric = "baseline")
        `when`(fixture.storage.getLegacyLyricCandidates()).thenReturn(listOf(legacy))
        val snapshot = fixture.builder.build(fixture.context, 0L)
        val override = snapshot.lyricOverrides.single()
        assertEquals(legacy.matchedLyric, override.matchedLyric)
        assertEquals(legacy.originalLyric, override.originalLyric)
        assertEquals(true, override.lyricSyncEdited)
        assertEquals(1L, override.lyricSyncRevision)
    }

    @Test
    fun `explicit reset remains newer than an old durable lyric candidate`() {
        val fixture = Fixture()
        val legacy = SyncSong(id = 7, album = "Netease", matchedLyric = "old edited lyrics")
        val reset = legacy.copy(matchedLyric = null, lyricSyncEdited = false, lyricSyncRevision = 2)
        `when`(fixture.storage.getLyricOverrides()).thenReturn(listOf(reset))
        `when`(fixture.storage.getLegacyLyricCandidates()).thenReturn(listOf(legacy))
        assertEquals(listOf(reset), fixture.builder.build(fixture.context, 0L).lyricOverrides)
    }

    @Test
    fun `old global optimization no longer omits unknown lyrics from new snapshots`() {
        val fixture = Fixture()
        val legacy = SyncSong(id = 7, album = "Netease", matchedLyric = "unknown old lyrics")
        val bilibili = legacy.copy(id = 8, album = "Bilibili", lyricSyncEdited = false)
        val edited = legacy.copy(id = 9, lyricSyncEdited = true, lyricSyncRevision = 10)
        `when`(fixture.storage.isLegacyLyricOptimizationEnabled()).thenReturn(true)
        `when`(fixture.storage.getLegacyLyricCandidates()).thenReturn(listOf(legacy))
        `when`(fixture.storage.getLyricOverrides()).thenReturn(listOf(bilibili, edited))
        val overrides = fixture.builder.build(fixture.context, 0L).lyricOverrides
        assertEquals(setOf(legacy.id, bilibili.id, edited.id), overrides.map { it.id }.toSet())
        assertEquals(legacy.matchedLyric, overrides.single { it.id == legacy.id }.matchedLyric)
        assertEquals(true, overrides.single { it.id == legacy.id }.lyricSyncEdited)
        assertEquals(bilibili.matchedLyric, overrides.single { it.id == bilibili.id }.matchedLyric)
        assertEquals(1L, overrides.single { it.id == bilibili.id }.lyricSyncRevision)
        assertEquals(edited, overrides.single { it.id == edited.id })
        verify(fixture.storage, never()).isLegacyLyricOptimizationEnabled()
    }

    private class Fixture {
        val context = mock(Context::class.java)
        val storage = mock(SecureTokenStorage::class.java)
        private val playlists = mock(LocalPlaylistRepository::class.java)
        private val favorites = mock(FavoritePlaylistRepository::class.java)
        private val history = mock(PlayHistoryRepository::class.java)
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
