package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.stats.*
import moe.ouom.neriplayer.data.model.sync.*
import moe.ouom.neriplayer.platform.bilibili.skip.BiliVideoSkipRepository
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsPersistenceSnapshot
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class SyncLocalHostTest {
    @After fun clearMapper() { CoverUrlMapper.installForTest(null) }

    @Test
    fun `snapshot emits absent playlist tombstones and excludes local history`() {
        val fixture = Fixture()
        CoverUrlMapper.installForTest(CoverUrlMapper.createForTest())
        val empty = fixture.builder.build(fixture.context)
        assertTrue(empty.playlists.isEmpty())
        assertTrue(empty.recentPlays.isEmpty())
        val remote = SongItem(1, "song", "artist", "netease", 1, 10, "https://cover.test/a", channelId = "netease", audioId = "1")
        fixture.playlists.value = listOf(LocalPlaylist(10, "playlist", mutableListOf(remote)))
        `when`(fixture.storage.getDeletedPlaylistTimestamps()).thenReturn(mapOf(10L to 20L, 11L to 30L))
        `when`(fixture.favorites.getSyncSnapshots()).thenReturn(listOf(FavoritePlaylist(20, "favorite", null, 1, "netease", songs = listOf(remote))))
        val entry = PlayedEntry(1, "song", "artist", "netease", 1, 10, coverUrl = null, playedAt = 50)
        fixture.history.value = listOf(entry, entry.copy(id = 2, mediaUri = "content://local/2"), entry.copy(id = 3, localFilePath = "/local/3"), entry.copy(id = 4, localFilePath = " "))
        val snapshot = fixture.builder.build(fixture.context)
        assertEquals("device", snapshot.deviceId)
        assertEquals(listOf(10L, 11L), snapshot.playlists.map { it.id })
        assertTrue(snapshot.playlists.last().isDeleted)
        assertEquals(30L, snapshot.playlists.last().modifiedAt)
        assertEquals(listOf(1L, 4L), snapshot.recentPlays.map { it.songId })
        assertTrue(snapshot.recentPlays.all { it.deviceId == "device" })
        assertEquals(20L, snapshot.favoritePlaylists.single().id)
    }

    @Test
    fun `statistics snapshot stays complete when live state is cleared during sync capture`() {
        val fixture = Fixture()
        val track = TrackStat(
            id = 7, name = "song", artist = "artist", album = "netease", albumId = 8,
            coverUrl = null, durationMs = 180_000, totalListenMs = 40_000, playCount = 1,
            lastPlayedAt = 200, firstPlayedAt = 100, mediaUri = null, localFilePath = null,
            localFileName = null, customName = null, customArtist = null, customCoverUrl = null,
            identityKey = "track|7"
        )
        val bucket = PlaybackStatBucket(
            dayStartAt = 100, id = track.id, name = track.name, artist = track.artist,
            album = track.album, albumId = track.albumId, coverUrl = null, durationMs = track.durationMs,
            totalListenMs = track.totalListenMs, playCount = track.playCount,
            lastPlayedAt = track.lastPlayedAt, firstPlayedAt = track.firstPlayedAt,
            mediaUri = null, localFilePath = null, localFileName = null, customName = null,
            customArtist = null, customCoverUrl = null, identityKey = track.identityKey
        )
        val shard = SyncPlaybackCounterShard("device", 50, 40_000, 1, 100, 200)
        val counters = PlaybackStatsSyncCounterSnapshot(
            trackShardsByIdentity = mapOf(track.identityKey to listOf(shard)),
            dailyShardsByBucketKey = mapOf(
                PlaybackStatsSyncCounterSnapshot.dailyCounterKey(bucket.dayStartAt, bucket.identityKey) to listOf(shard)
            )
        )
        val persisted = PlaybackStatsPersistenceSnapshot(listOf(track), listOf(bucket), counters, 50, 50)
        val liveStats = MutableStateFlow(listOf(track))
        val liveBuckets = MutableStateFlow(listOf(bucket))
        val liveClearedAt = MutableStateFlow(50L)
        `when`(fixture.stats.statsFlow).thenReturn(liveStats)
        `when`(fixture.stats.dailyStatsFlow).thenReturn(liveBuckets)
        `when`(fixture.stats.statsClearedAtFlow).thenReturn(liveClearedAt)
        `when`(fixture.stats.syncSnapshot()).thenAnswer {
            liveStats.value = emptyList()
            liveBuckets.value = emptyList()
            liveClearedAt.value = 300
            persisted
        }

        val snapshot = fixture.builder.build(fixture.context)

        assertEquals(40_000L, snapshot.playbackStats.single().totalListenMs)
        assertEquals(listOf(shard), snapshot.playbackStats.single().counterShards)
        assertEquals(40_000L, snapshot.playbackStatBuckets.single().totalListenMs)
        assertEquals(listOf(shard), snapshot.playbackStatBuckets.single().counterShards)
        assertEquals(50L, snapshot.playbackStatsClearedAt)
        assertTrue(liveStats.value.isEmpty())
        assertEquals(300L, liveClearedAt.value)
    }

    @Test
    fun `history eligibility and mutation rejection retain exact repository contracts`() = runTest {
        val fixture = Fixture()
        val remote = SyncRecentPlay(1, SyncSong(id = 1, album = "netease", albumId = 1), playedAt = 20)
        `when`(fixture.playHistory.updateHistoryIfUnchanged(anyList(), anyLong())).thenReturn(false)
        assertTrue(fixture.applier.applyHistory(SyncData(), false, 7))
        verify(fixture.playHistory, never()).updateHistoryIfUnchanged(anyList(), anyLong())
        assertFalse(fixture.applier.applyHistory(SyncData(recentPlays = listOf(remote)), false, 7))
        `when`(fixture.playHistory.updateHistoryIfUnchanged(anyList(), anyLong())).thenReturn(true)
        assertTrue(fixture.applier.applyHistory(SyncData(recentPlays = listOf(remote)), true, 7))
        fixture.history.value = listOf(PlayedEntry(2, "old", "artist", "netease", 1, 10, coverUrl = null, playedAt = 5))
        assertTrue(fixture.applier.applyHistory(SyncData(recentPlays = listOf(remote)), false, 7))
        verify(fixture.playHistory, times(2)).updateHistoryIfUnchanged(anyList(), eq(7L))
        `when`(fixture.playlist.applySyncedPlaylistsIfUnchanged(anyList(), anyLong())).thenReturn(false)
        assertFalse(fixture.applier.applyPlaylists(SyncData(), 7))
        `when`(fixture.playlist.applySyncedPlaylistsIfUnchanged(anyList(), anyLong())).thenReturn(true)
        assertTrue(fixture.applier.applyPlaylists(SyncData(), 7))
        assertFalse(fixture.applier.applyDeletions(SyncData(), 7))
        `when`(fixture.storage.setDeletionStateIfMutationVersion(7, emptyList(), emptyList())).thenReturn(true)
        assertTrue(fixture.applier.applyDeletions(SyncData(), 7))
        `when`(fixture.favorites.replaceFavoritesFromSyncIfUnchanged(emptyList(), 7)).thenReturn(false)
        assertFalse(fixture.applier.applyFavorites(SyncData(), 7))
        `when`(fixture.favorites.replaceFavoritesFromSyncIfUnchanged(emptyList(), 7)).thenReturn(true)
        assertTrue(fixture.applier.applyFavorites(SyncData(), 7))
        `when`(fixture.skip.replaceFromSyncIfUnchanged(emptyList(), 7)).thenReturn(false)
        assertFalse(fixture.applier.applyVideoSkipRules(SyncData(), 7))
        `when`(fixture.skip.replaceFromSyncIfUnchanged(emptyList(), 7)).thenReturn(true)
        assertTrue(fixture.applier.applyVideoSkipRules(SyncData(), 7))
    }

    private class Fixture {
        val context = mock(Context::class.java)
        val storage = mock(SecureTokenStorage::class.java)
        val playlist = mock(LocalPlaylistRepository::class.java)
        val favorites = mock(FavoritePlaylistRepository::class.java)
        val playHistory = mock(PlayHistoryRepository::class.java)
        val stats = mock(PlaybackStatsRepository::class.java)
        val usage = mock(PlaylistUsageRepository::class.java)
        val localStats = mock(LocalPlaylistPlaybackStatsRepository::class.java)
        val skip = mock(BiliVideoSkipRepository::class.java)
        val playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
        val history = MutableStateFlow<List<PlayedEntry>>(emptyList())
        init {
            `when`(playlist.playlists).thenReturn(playlists)
            `when`(playHistory.historyFlow).thenReturn(history)
            `when`(storage.getOrCreateDeviceId()).thenReturn("device")
            `when`(storage.getDeletedPlaylistTimestamps()).thenReturn(emptyMap())
            `when`(storage.getRecentPlayDeletions()).thenReturn(emptyList())
            `when`(storage.getPlaylistSongDeletions()).thenReturn(emptyList())
            `when`(favorites.getSyncSnapshots()).thenReturn(emptyList())
            `when`(stats.statsFlow).thenReturn(MutableStateFlow(emptyList()))
            `when`(stats.dailyStatsFlow).thenReturn(MutableStateFlow(emptyList()))
            `when`(stats.statsClearedAtFlow).thenReturn(MutableStateFlow(0L))
            `when`(stats.syncSnapshot()).thenReturn(PlaybackStatsPersistenceSnapshot(
                emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0L, 0L
            ))
            `when`(usage.syncStats()).thenReturn(emptyList())
            `when`(localStats.syncSnapshot()).thenReturn(LocalPlaylistPlaybackSyncSnapshot(emptyList(), emptyList()))
            `when`(skip.snapshot()).thenReturn(emptyList())
        }
        val builder = AndroidSyncSnapshotBuilder(storage, playlist, favorites, playHistory, stats, usage, localStats, skip)
        val applier = AndroidSyncLocalApplyHost(context, storage, playlist, favorites, playHistory, stats, usage, localStats, skip) { context }
    }
}
