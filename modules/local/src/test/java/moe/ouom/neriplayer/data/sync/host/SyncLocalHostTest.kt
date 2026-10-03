package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipRule
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlaybackSyncSnapshot
import moe.ouom.neriplayer.data.model.sync.*
import moe.ouom.neriplayer.platform.bilibili.skip.BiliVideoSkipRepository
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsCapturedState
import moe.ouom.neriplayer.data.stats.PlaybackStatsSyncCapture
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackCursor
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackBucketOrder
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.runtime.SyncLocalDataApplier
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class SyncLocalHostTest {
    private val fixtures = ArrayList<Fixture>()
    private val playbackStoreMatcherFallback = mock(FileSyncPlaybackDatasetStore::class.java)
    private val snapshotReaderMatcherFallback: () -> SyncData = { SyncData() }
    private fun anyPlaybackStore(): FileSyncPlaybackDatasetStore =
        any(FileSyncPlaybackDatasetStore::class.java) ?: playbackStoreMatcherFallback
    private fun anySnapshotReader(): () -> SyncData = any<() -> SyncData>() ?: snapshotReaderMatcherFallback
    @After fun clearMapper() { CoverUrlMapper.installForTest(null); fixtures.forEach { it.directory.deleteRecursively() } }

    @Test
    fun `statistics apply awaits checked usage persistence and stops on write failure or cancellation`() = runTest {
        val fixture = Fixture()
        val data = SyncData(playlistUsageStats = listOf(SyncPlaylistUsageStat(playlistKey = "netease:1", id = 1)))
        for (failure in listOf(java.io.IOException("usage marker failed"), CancellationException("usage cancelled"))) {
            doAnswer { throw failure }.`when`(fixture.usage).applyMergedStatsAndPersist(data.playlistUsageStats, data.playlistUsageDeletions)
            assertSame(failure, runCatching { fixture.applier.applyStatistics(data) }.exceptionOrNull())
        }
        verify(fixture.usage, times(2)).applyMergedStatsAndPersist(data.playlistUsageStats, data.playlistUsageDeletions)
        verify(fixture.usage, never()).applyMergedStats(anyList())
        verifyNoInteractions(fixture.localStats)
        doAnswer { Unit }.`when`(fixture.usage).applyMergedStatsAndPersist(data.playlistUsageStats, data.playlistUsageDeletions)
        fixture.applier.applyStatistics(data)
        verify(fixture.localStats).applyMergedStats(emptyList(), emptyList())
    }

    @Test
    fun `snapshot emits absent playlist tombstones and excludes local history`() {
        val fixture = Fixture()
        CoverUrlMapper.installForTest(CoverUrlMapper.createForTest())
        val empty = fixture.builder.build(fixture.context, 0L)
        assertTrue(empty.playlists.isEmpty())
        assertTrue(empty.recentPlays.isEmpty())
        val remote = SongItem(1, "song", "artist", "netease", 1, 10, "https://cover.test/a", channelId = "netease", audioId = "1")
        fixture.playlists.value = listOf(LocalPlaylist(10, "playlist", mutableListOf(remote)))
        `when`(fixture.storage.getDeletedPlaylistTimestamps()).thenReturn(mapOf(10L to 20L, 11L to 30L))
        `when`(fixture.favorites.getSyncSnapshots()).thenReturn(listOf(FavoritePlaylist(20, "favorite", null, 1, "netease", songs = listOf(remote))))
        val entry = PlayedEntry(1, "song", "artist", "netease", 1, 10, coverUrl = null, playedAt = 50)
        fixture.history.value = listOf(entry, entry.copy(id = 2, mediaUri = "content://local/2"), entry.copy(id = 3, localFilePath = "/local/3"), entry.copy(id = 4, localFilePath = " "))
        val snapshot = fixture.builder.build(fixture.context, 0L)
        assertEquals("device", snapshot.deviceId)
        assertEquals(listOf(10L, 11L), snapshot.playlists.map { it.id })
        assertTrue(snapshot.playlists.last().isDeleted)
        assertEquals(30L, snapshot.playlists.last().modifiedAt)
        assertEquals(listOf(1L, 4L), snapshot.recentPlays.map { it.songId })
        assertTrue(snapshot.recentPlays.all { it.deviceId == "device" })
        assertEquals(20L, snapshot.favoritePlaylists.single().id)
    }

    @Test
    fun `statistics capture uses frozen pages and revision while the live clear barrier changes`() = runTest {
        val fixture = Fixture()
        fixture.configureReady()
        var liveClear = 50L
        val shard = SyncPlaybackCounterShard("device", 50, 40_000, 1, 100, 200)
        val track = SyncTrackStat(identityKey = "track|7", id = 7, album = "netease", totalListenMs = 40_000, counterShards = listOf(shard))
        val bucket = SyncPlaybackStatBucket(identityKey = track.identityKey, dayStartAt = 100, id = 7, album = "netease", totalListenMs = 40_000, counterShards = listOf(shard))
        doAnswer { invocation ->
            runBlocking { invocation.getArgument<FileSyncPlaybackDatasetStore>(0).newOrderedSink().use { sink ->
                sink.appendTracks(listOf(track)); sink.appendBuckets(listOf(bucket))
                liveClear = 300L
                PlaybackStatsSyncCapture(PlaybackStatsCapturedState(17L, 50L), sink.seal())
            } }
        }.`when`(fixture.stats).borrowSyncCapture(anyPlaybackStore())

        fixture.store.snapshot().use { dataset ->
            assertTrue(dataset.data.playbackStats.isEmpty())
            assertTrue(dataset.data.playbackStatBuckets.isEmpty())
            assertEquals(17L, dataset.capturedPlaybackRevision)
            assertEquals(50L, dataset.data.playbackStatsClearedAt)
            dataset.playback.openTracks().use { cursor -> assertEquals(track, cursor.nextPage().single()); assertTrue(cursor.nextPage().isEmpty()) }
            dataset.playback.openBuckets().use { cursor -> assertEquals(bucket, cursor.nextPage().single()); assertTrue(cursor.nextPage().isEmpty()) }
        }
        assertEquals(300L, liveClear)
        verify(fixture.stats, times(1)).borrowSyncCapture(anyPlaybackStore())
    }

    @Test
    fun `statistics capture preserves matching counter shards in Room binary key order`() = runTest {
        val fixture = Fixture()
        fixture.configureReady()
        val first = SyncTrackStat(identityKey = "track|7", id = 7, album = "netease", counterShards = listOf(SyncPlaybackCounterShard("device", 50, 10_000, 1, 100, 200)))
        val last = first.copy(identityKey = "track|10", id = 10, counterShards = listOf(first.counterShards.single().copy(totalListenMs = 20_000)))
        doAnswer { invocation ->
            runBlocking { invocation.getArgument<FileSyncPlaybackDatasetStore>(0).newOrderedSink().use { sink ->
                sink.appendTracks(listOf(last)); sink.appendTracks(listOf(first))
                PlaybackStatsSyncCapture(PlaybackStatsCapturedState(3L, 0L), sink.seal())
            } }
        }.`when`(fixture.stats).borrowSyncCapture(anyPlaybackStore())
        fixture.store.snapshot().use { dataset ->
            dataset.playback.openTracks().use { cursor ->
                assertEquals(listOf(last, first), cursor.nextPage())
                assertTrue(cursor.nextPage().isEmpty())
            }
            assertEquals(3L, dataset.capturedPlaybackRevision)
        }
    }

    @Test
    fun `statistics capture rejects unordered primary pages and releases staging files`() = runTest {
        val fixture = Fixture()
        fixture.configureReady()
        doAnswer { invocation ->
            runBlocking { invocation.getArgument<FileSyncPlaybackDatasetStore>(0).newOrderedSink().use { sink ->
                sink.appendTracks(listOf(SyncTrackStat(identityKey = "track|7")))
                sink.appendTracks(listOf(SyncTrackStat(identityKey = "track|10")))
                PlaybackStatsSyncCapture(PlaybackStatsCapturedState(3L, 0L), sink.seal())
            } }
        }.`when`(fixture.stats).borrowSyncCapture(anyPlaybackStore())
        assertTrue(runCatching { fixture.store.snapshot() }.exceptionOrNull() is IllegalArgumentException)
        val staging = java.io.File(fixture.directory, "sync-v3/playback-staging")
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        verifyNoInteractions(fixture.usage, fixture.localStats, fixture.skip)
    }

    @Test
    fun `cancelled statistics capture propagates and releases every staging file`() = runTest {
        val fixture = Fixture()
        fixture.configureReady()
        val cancellation = CancellationException("statistics capture cancelled")
        doAnswer { invocation ->
            runBlocking { invocation.getArgument<FileSyncPlaybackDatasetStore>(0).newOrderedSink().use { sink ->
                sink.appendTracks(listOf(SyncTrackStat(identityKey = "partial")))
                throw cancellation
            } }
        }.`when`(fixture.stats).borrowSyncCapture(anyPlaybackStore())
        val result = runCatching { fixture.store.snapshot() }
        assertSame(cancellation, result.exceptionOrNull())
        verifyNoInteractions(fixture.usage, fixture.localStats, fixture.skip)
        val staging = java.io.File(fixture.directory, "sync-v3/playback-staging")
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `core capture failure releases the borrowed playback source`() = runTest {
        val fixture = Fixture()
        fixture.configureReady()
        doAnswer { invocation ->
            runBlocking { invocation.getArgument<FileSyncPlaybackDatasetStore>(0).newOrderedSink().use { sink ->
                sink.appendTracks(listOf(SyncTrackStat(identityKey = "owned")))
                PlaybackStatsSyncCapture(PlaybackStatsCapturedState(3L, 0L), sink.seal())
            } }
        }.`when`(fixture.stats).borrowSyncCapture(anyPlaybackStore())
        val failure = java.io.IOException("usage durable state unavailable")
        doAnswer { throw failure }.`when`(fixture.usage).syncStatsAndDeletions()
        assertSame(failure, runCatching { fixture.store.snapshot() }.exceptionOrNull())
        val staging = java.io.File(fixture.directory, "sync-v3/playback-staging")
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `host builds every container and deletion receipt inside the committed playlist boundary`() = runTest {
        val fixture = Fixture()
        fixture.configureReady()
        fixture.configureSealedPlaybackCapture()
        var insideBoundary = false
        doAnswer { invocation ->
            assertFalse(insideBoundary)
            insideBoundary = true
            try { invocation.getArgument<() -> SyncData>(0).invoke() }
            finally { insideBoundary = false }
        }.`when`(fixture.playlist).withCommittedSyncSnapshot(anySnapshotReader())
        `when`(fixture.playlist.playlists).thenAnswer { assertTrue(insideBoundary); fixture.playlists }
        `when`(fixture.storage.getDeletedPlaylistTimestamps()).thenAnswer { assertTrue(insideBoundary); mapOf(7L to 90L) }
        `when`(fixture.storage.getRecentPlayDeletions()).thenAnswer { assertTrue(insideBoundary); emptyList<SyncRecentPlayDeletion>() }
        `when`(fixture.storage.getPlaylistSongDeletions()).thenAnswer { assertTrue(insideBoundary); emptyList<SyncPlaylistSongDeletion>() }
        `when`(fixture.favorites.getSyncSnapshots()).thenAnswer { assertTrue(insideBoundary); emptyList<FavoritePlaylist>() }
        `when`(fixture.playHistory.syncSnapshot()).thenAnswer { assertTrue(insideBoundary); emptyList<PlayedEntry>() }
        `when`(fixture.usage.syncStatsAndDeletions()).thenAnswer {
            assertTrue(insideBoundary)
            emptyList<SyncPlaylistUsageStat>() to emptyList<SyncPlaylistUsageDeletion>()
        }
        `when`(fixture.localStats.syncSnapshot()).thenAnswer { assertTrue(insideBoundary); LocalPlaylistPlaybackSyncSnapshot(emptyList(), emptyList()) }
        `when`(fixture.skip.snapshot()).thenAnswer { assertTrue(insideBoundary); emptyList<BiliVideoSkipRule>() }
        `when`(fixture.storage.getLyricOverrides()).thenAnswer { assertTrue(insideBoundary); emptyList<SyncSong>() }

        fixture.store.snapshot().use { dataset ->
            assertFalse(insideBoundary)
            val deletion = dataset.data.playlists.single()
            assertEquals(7L, deletion.id)
            assertTrue(deletion.isDeleted)
            assertEquals(90L, deletion.modifiedAt)
            assertEquals(3L, dataset.capturedPlaybackRevision)
        }
        assertTrue(java.io.File(fixture.directory, "sync-v3/playback-staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `playlist confirmation failure rejects capture and releases the borrowed statistics files`() = runTest {
        for (failure in listOf(java.io.IOException("playlist checked confirmation rejected"), CancellationException("playlist confirmation cancelled"))) {
            val fixture = Fixture()
            fixture.configureReady()
            fixture.configureSealedPlaybackCapture()
            doAnswer { throw failure }.`when`(fixture.playlist).withCommittedSyncSnapshot(anySnapshotReader())

            val actual = checkNotNull(runCatching { fixture.store.snapshot() }.exceptionOrNull())

            assertTrue(generateSequence(actual) { it.cause }.any { it === failure })
            verify(fixture.storage, never()).getDeletedPlaylistTimestamps()
            verifyNoInteractions(fixture.favorites, fixture.playHistory, fixture.usage, fixture.localStats, fixture.skip)
            assertTrue(java.io.File(fixture.directory, "sync-v3/playback-staging").listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `cancelled ownership return after building core still releases its borrowed statistics source`() = runTest {
        val fixture = Fixture()
        fixture.configureReady()
        fixture.configureSealedPlaybackCapture()
        val cancelled = CancellationException("core capture cancelled before ownership return")
        doAnswer { invocation ->
            invocation.getArgument<() -> SyncData>(0).invoke()
            throw cancelled
        }.`when`(fixture.playlist).withCommittedSyncSnapshot(anySnapshotReader())

        val actual = checkNotNull(runCatching { fixture.store.snapshot() }.exceptionOrNull())

        assertTrue(generateSequence(actual) { it.cause }.any { it === cancelled })
        verify(fixture.usage).syncStatsAndDeletions()
        assertTrue(java.io.File(fixture.directory, "sync-v3/playback-staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `unchanged playback checks its captured revision without rewriting the main tables`() = runTest {
        val fixture = Fixture()
        fixture.configureReady()
        `when`(fixture.storage.setDeletionStateIfMutationVersion(7L, emptyList(), emptyList())).thenReturn(true)
        `when`(fixture.playlist.applySyncedPlaylistsIfUnchanged(emptyList(), 7L)).thenReturn(true)
        `when`(fixture.favorites.replaceFavoritesFromSyncIfUnchanged(emptyList(), 7L)).thenReturn(true)
        `when`(fixture.skip.replaceFromSyncIfUnchanged(emptyList(), 7L)).thenReturn(true)
        `when`(fixture.stats.checkCapturedRevision(17L)).thenReturn(true, false)
        val source = object : SyncPlaybackSource {
            override fun openTracks(): SyncPlaybackCursor<SyncTrackStat> = error("matching playback was already fully verified during merge")
            override fun openBuckets(order: SyncPlaybackBucketOrder): SyncPlaybackCursor<SyncPlaybackStatBucket> = error("matching playback was already fully verified during merge")
            override fun close() = Unit
        }
        val dataset = SyncDataset(SyncData(), source, 17L, playbackMatchesCaptured = true)

        assertTrue(fixture.store.apply(dataset, false, 7L))
        assertFalse(fixture.store.apply(dataset, false, 7L))

        verify(fixture.stats, times(2)).checkCapturedRevision(17L)
        verify(fixture.stats, never()).applySyncSnapshot(any(SyncPlaybackSource::class.java) ?: source, anyLong(), anyLong())
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
        `when`(fixture.storage.setLyricOverridesIfMutationVersion(7, emptyList())).thenReturn(false)
        assertFalse(fixture.applier.applyDeletions(SyncData(), 7))
        `when`(fixture.storage.setLyricOverridesIfMutationVersion(7, emptyList())).thenReturn(true)
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

    @Test
    fun `rejected or failed usage deletion barrier prevents partial container or statistic application`() = runTest {
        for (throwing in listOf(false, true)) {
            val fixture = Fixture()
            val data = SyncData(playlistUsageDeletions = listOf(SyncPlaylistUsageDeletion("netease:1", deletedAt = 2)))
            val guarded = `when`(fixture.storage.mergePlaylistUsageDeletionBarriersIfMutationVersion(7L, data.playlistUsageDeletions))
            if (throwing) guarded.thenThrow(IllegalStateException("usage barrier commit failed")) else guarded.thenReturn(false)
            val result = runCatching { SyncLocalDataApplier(fixture.applier).apply(data, true, 7L) }
            if (throwing) assertTrue(result.exceptionOrNull() is IllegalStateException) else assertEquals(false, result.getOrThrow())
            verifyNoInteractions(fixture.playlist, fixture.favorites, fixture.playHistory, fixture.stats, fixture.usage, fixture.localStats, fixture.skip)
            verify(fixture.storage, never()).setLyricOverridesIfMutationVersion(anyLong(), anyList())
            verify(fixture.storage, never()).setPlaylistDeletionStateIfMutationVersion(anyLong(), anyList(), anyBoolean())
        }
    }

    @Test
    fun `rejected or failed permanent lyric registry leaves every repository untouched`() = runTest {
        for (throwing in listOf(false, true)) {
            val fixture = Fixture()
            val reset = SyncSong(id = 1L, lyricSyncEdited = false, lyricSyncRevision = 20L)
            val data = SyncData(lyricOverrides = listOf(reset))
            val guarded = `when`(fixture.storage.setLyricOverridesIfMutationVersion(7L, data.lyricOverrides))
            if (throwing) guarded.thenThrow(IllegalStateException("registry commit failed")) else guarded.thenReturn(false)
            val result = runCatching { SyncLocalDataApplier(fixture.applier).apply(data, true, 7L) }
            if (throwing) assertTrue(result.exceptionOrNull() is IllegalStateException) else assertEquals(false, result.getOrThrow())
            verifyNoInteractions(fixture.playlist, fixture.favorites, fixture.playHistory, fixture.stats, fixture.usage, fixture.localStats, fixture.skip)
            verify(fixture.storage, never()).setDeletionStateIfMutationVersion(anyLong(), anyList(), anyList())
            verify(fixture.storage, never()).setPlaylistDeletionStateIfMutationVersion(anyLong(), anyList(), anyBoolean())
        }
    }

    @Test
    fun `rejected or failed playlist tombstone persistence stops every container write`() = runTest {
        for (throwing in listOf(false, true)) {
            val fixture = Fixture()
            val data = SyncData(playlists = listOf(SyncPlaylist(id = 7, modifiedAt = 20, isDeleted = true)))
            val guarded = `when`(fixture.storage.setPlaylistDeletionStateIfMutationVersion(7L, data.playlists, false))
            if (throwing) guarded.thenThrow(IllegalStateException("playlist tombstone commit failed")) else guarded.thenReturn(false)

            val result = runCatching { SyncLocalDataApplier(fixture.applier).apply(data, true, 7L) }

            if (throwing) assertTrue(result.exceptionOrNull() is IllegalStateException) else assertEquals(false, result.getOrThrow())
            val writes = inOrder(fixture.storage)
            writes.verify(fixture.storage).setLyricOverridesIfMutationVersion(7L, emptyList())
            writes.verify(fixture.storage).setPlaylistDeletionStateIfMutationVersion(7L, data.playlists, false)
            verify(fixture.storage, never()).setDeletionStateIfMutationVersion(anyLong(), anyList(), anyList())
            verifyNoInteractions(fixture.playlist, fixture.favorites, fixture.playHistory, fixture.stats, fixture.usage, fixture.localStats, fixture.skip)
        }
    }

    @Test
    fun `active restoration cannot clear tombstones before its playlist container is committed`() = runTest {
        val fixture = Fixture()
        val data = SyncData(playlists = listOf(SyncPlaylist(id = 7, modifiedAt = 30)))
        `when`(fixture.storage.setDeletionStateIfMutationVersion(anyLong(), anyList(), anyList())).thenReturn(true)
        `when`(fixture.playlist.applySyncedPlaylistsIfUnchanged(anyList(), anyLong())).thenReturn(false)

        assertFalse(SyncLocalDataApplier(fixture.applier).apply(data, true, 7L))

        verify(fixture.storage).setPlaylistDeletionStateIfMutationVersion(7L, data.playlists, false)
        verify(fixture.storage, never()).setPlaylistDeletionStateIfMutationVersion(7L, data.playlists, true)
        clearInvocations(fixture.storage, fixture.playlist)
        `when`(fixture.playlist.applySyncedPlaylistsIfUnchanged(anyList(), anyLong())).thenReturn(true)
        `when`(fixture.storage.setPlaylistDeletionStateIfMutationVersion(7L, data.playlists, true)).thenReturn(false)

        assertFalse(SyncLocalDataApplier(fixture.applier).apply(data, true, 7L))

        val writes = inOrder(fixture.playlist, fixture.storage)
        writes.verify(fixture.storage).setPlaylistDeletionStateIfMutationVersion(7L, data.playlists, false)
        writes.verify(fixture.playlist).applySyncedPlaylistsIfUnchanged(anyList(), eq(7L))
        writes.verify(fixture.storage).setPlaylistDeletionStateIfMutationVersion(7L, data.playlists, true)
        verifyNoInteractions(fixture.favorites, fixture.playHistory, fixture.stats, fixture.usage, fixture.localStats, fixture.skip)
    }

    private inner class Fixture {
        val directory = kotlin.io.path.createTempDirectory("sync-local-host").toFile()
        val context = mock(Context::class.java)
        val languagePreferences = mock(SharedPreferences::class.java)
        val resources = mock(Resources::class.java)
        val configuration = mock(Configuration::class.java)
        val locales = mock(LocaleList::class.java)
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
            fixtures.add(this)
            `when`(context.cacheDir).thenReturn(directory)
            `when`(context.getSharedPreferences("language_settings", Context.MODE_PRIVATE)).thenReturn(languagePreferences)
            `when`(languagePreferences.getString("selected_language", "")).thenReturn("")
            `when`(context.resources).thenReturn(resources)
            `when`(resources.configuration).thenReturn(configuration)
            `when`(configuration.locales).thenReturn(locales)
            `when`(locales.size()).thenReturn(1)
            `when`(locales[0]).thenReturn(Locale.getDefault())
            `when`(context.getString(anyInt())).thenReturn("resource")
            `when`(playHistory.syncSnapshot()).thenAnswer { history.value }
            `when`(playlist.playlists).thenReturn(playlists)
            `when`(playHistory.historyFlow).thenReturn(history)
            `when`(storage.getOrCreateDeviceId()).thenReturn("device")
            `when`(storage.getDeletedPlaylistTimestamps()).thenReturn(emptyMap())
            `when`(storage.getRecentPlayDeletions()).thenReturn(emptyList())
            `when`(storage.getPlaylistSongDeletions()).thenReturn(emptyList())
            `when`(storage.getLyricOverrides()).thenReturn(emptyList())
            `when`(storage.setLyricOverridesIfMutationVersion(anyLong(), anyList())).thenReturn(true)
            `when`(storage.mergePlaylistUsageDeletionBarriersIfMutationVersion(anyLong(), anyList())).thenReturn(true)
            `when`(storage.setPlaylistDeletionStateIfMutationVersion(anyLong(), anyList(), anyBoolean())).thenReturn(true)
            `when`(favorites.getSyncSnapshots()).thenReturn(emptyList())
            `when`(favorites.favorites).thenReturn(MutableStateFlow(emptyList()))
            `when`(usage.syncStatsAndDeletions()).thenReturn(emptyList<SyncPlaylistUsageStat>() to emptyList())
            `when`(localStats.syncSnapshot()).thenReturn(LocalPlaylistPlaybackSyncSnapshot(emptyList(), emptyList()))
            `when`(skip.snapshot()).thenReturn(emptyList())
        }
        val store by lazy { AndroidSyncLocalDataStore(context, storage, playlist, favorites, playHistory, stats, usage, localStats, skip) { context } }
        suspend fun configureReady() {
            doAnswer { invocation -> invocation.getArgument<() -> SyncData>(0).invoke() }
                .`when`(playlist).withCommittedSyncSnapshot(anySnapshotReader())
            `when`(playlist.awaitInitialized()).thenReturn(true)
            `when`(favorites.awaitInitialized()).thenReturn(true)
            `when`(stats.awaitInitialized()).thenReturn(true)
            `when`(playHistory.awaitInitialized()).thenReturn(true)
            `when`(usage.awaitInitialized()).thenReturn(true)
            `when`(localStats.awaitInitialized()).thenReturn(true)
        }
        suspend fun configureSealedPlaybackCapture() {
            doAnswer { invocation ->
                runBlocking { invocation.getArgument<FileSyncPlaybackDatasetStore>(0).newOrderedSink().use { sink ->
                    sink.appendTracks(listOf(SyncTrackStat(identityKey = "owned")))
                    PlaybackStatsSyncCapture(PlaybackStatsCapturedState(3L, 0L), sink.seal())
                } }
            }.`when`(stats).borrowSyncCapture(anyPlaybackStore())
        }
        val builder = AndroidSyncSnapshotBuilder(storage, playlist, favorites, playHistory, usage, localStats, skip)
        val applier = AndroidSyncLocalApplyHost(context, storage, playlist, favorites, playHistory, usage, localStats, skip) { context }
    }
}
