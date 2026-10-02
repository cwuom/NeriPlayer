package moe.ouom.neriplayer.data.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.local.database.store.stats.toDomain
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSink
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackCursor
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackBucketOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PlaybackStatsDatasetApplyTest {
    @Test
    fun emptyManagedSourceRemovesOldStatisticsAtSameClearAndPreservesLocalAudio() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val remote = track(7).copy(firstPlayedAt = 1, totalListenMs = 1_000_000)
            val local = track(8).copy(localFilePath = "/audio.flac", customName = "my title")
            store.importLegacyAndPromote(listOf(remote, local), emptyList(), PlaybackStatsSyncCounterSnapshot(), 50, 50)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val revision = checkNotNull(store.readPrimaryState()).revision
            assertTrue(repository.applySyncSnapshot(source { emptyList() }, 50, revision))
            assertEquals(null, store.readTrack(remote.identityKey))
            assertEquals(local, store.readTrack(local.identityKey))
        } finally { database.close() }
    }

    @Test
    fun finalCursorReadFailureLeavesPrimaryRowsAndRevisionUntouched() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val original = track(7).copy(customName = "my title")
            store.importLegacyAndPromote(listOf(original), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val revision = checkNotNull(store.readPrimaryState()).revision
            var read = 0
            val source = source {
                if (read++ == 0) listOf(SyncTrackStat(original.identityKey, "new name", "artist", "netease", 50_000, 2, 300, 100, null, 180_000, null, 7, 0))
                else throw IOException("injected final cursor hash failure")
            }
            assertTrue(runCatching { repository.applySyncSnapshot(source, 0, revision) }.exceptionOrNull() is IOException)
            assertEquals(original, store.readTrack(original.identityKey))
            assertEquals(revision, store.readPrimaryState()?.revision)
        } finally { database.close() }
    }

    @Test
    fun oneChangedTrackCommitsWithoutRewritingAnyUnchangedPrimaryRows() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val original = track(7).copy(customName = "my title")
            val untouched = track(8).copy(customArtist = "my artist")
            store.importLegacyAndPromote(listOf(original, untouched), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val revision = checkNotNull(store.readPrimaryState()).revision
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_unchanged_stat_delete BEFORE DELETE ON playback_stat WHEN OLD.identity_key = 'track|8' BEGIN SELECT RAISE(ABORT, 'unchanged row deleted'); END")
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_unchanged_stat_update BEFORE UPDATE ON playback_stat WHEN OLD.identity_key = 'track|8' BEGIN SELECT RAISE(ABORT, 'unchanged row updated'); END")
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_unchanged_stat_insert BEFORE INSERT ON playback_stat WHEN NEW.identity_key = 'track|8' BEGIN SELECT RAISE(ABORT, 'unchanged row inserted'); END")
            var read = 0
            val source = source {
                if (read++ == 0) listOf(syncTrack(original).copy(totalListenMs = 50_000, playCount = 2), syncTrack(untouched))
                else emptyList()
            }
            assertTrue(repository.applySyncSnapshot(source, 0, revision))
            assertEquals(original.copy(totalListenMs = 50_000, playCount = 2), store.readTrack(original.identityKey))
            assertEquals(untouched, store.readTrack(untouched.identityKey))
            assertEquals(revision + 1, store.readPrimaryState()?.revision)
        } finally { database.close() }
    }

    @Test
    fun concurrentPrimaryRevisionChangeRejectsThePreparedApplyWithoutOverwritingNewRows() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val original = track(7)
            store.importLegacyAndPromote(listOf(original), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val revision = checkNotNull(store.readPrimaryState()).revision
            var read = 0
            val newLocal = original.copy(totalListenMs = 99_000, customName = "new local metadata")
            val source = source {
                if (read++ == 0) {
                    store.replaceAll(listOf(newLocal), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
                    listOf(syncTrack(original).copy(totalListenMs = 50_000))
                } else emptyList()
            }
            assertFalse(repository.applySyncSnapshot(source, 0, revision))
            assertEquals(newLocal, store.readTrack(original.identityKey))
            assertEquals(revision + 1, store.readPrimaryState()?.revision)
        } finally { database.close() }
    }

    @Test
    fun duplicateManagedSourceKeysAreRejectedBeforeAnyPrimaryCommit() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val original = track(7)
            store.importLegacyAndPromote(listOf(original), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val revision = checkNotNull(store.readPrimaryState()).revision
            var read = 0
            val source = source {
                if (read++ == 0) listOf(syncTrack(original), syncTrack(original).copy(totalListenMs = 99_000)) else emptyList()
            }
            assertTrue(runCatching { repository.applySyncSnapshot(source, 0, revision) }.exceptionOrNull() is IOException)
            assertEquals(original, store.readTrack(original.identityKey))
            assertEquals(revision, store.readPrimaryState()?.revision)
        } finally { database.close() }
    }

    @Test
    fun oneChangedBucketPreservesUntouchedParentsBucketsAndLocalMetadata() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val tracks = listOf(track(7), track(8).copy(customArtist = "my artist"))
            val buckets = tracks.map { bucket(it) }
            store.importLegacyAndPromote(tracks, buckets, PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val revision = checkNotNull(store.readPrimaryState()).revision
            for (table in listOf("playback_stat", "playback_stat_bucket")) {
                database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_${table}_delete BEFORE DELETE ON $table WHEN OLD.identity_key = 'track|8' BEGIN SELECT RAISE(ABORT, 'unchanged row deleted'); END")
                database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_${table}_update BEFORE UPDATE ON $table WHEN OLD.identity_key = 'track|8' BEGIN SELECT RAISE(ABORT, 'unchanged row updated'); END")
                database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_${table}_insert BEFORE INSERT ON $table WHEN NEW.identity_key = 'track|8' BEGIN SELECT RAISE(ABORT, 'unchanged row inserted'); END")
            }
            val source = listedSource(tracks.map(::syncTrack), buckets.map { SyncPlaybackStatMapper.fromPlaybackStatBucket(it) }
                .map { if (it.identityKey == "track|7") it.copy(name = "new bucket name") else it })
            assertTrue(repository.applySyncSnapshot(source, 0, revision))
            assertEquals("new bucket name", database.playbackStatsDao().getBucket(0, "track|7")?.name)
            assertEquals(tracks.last(), store.readTrack("track|8"))
            assertEquals(buckets.last().customArtist, database.playbackStatsDao().getBucket(0, "track|8")?.customArtist)
        } finally { database.close() }
    }

    @Test
    fun advancedClearRetainsOnlyNewLocalShardsAndPreservesLocalAudioMetadata() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val local = track(8).copy(localFilePath = "/audio.flac", customName = "my title", totalListenMs = 150, playCount = 3, firstPlayedAt = 50)
            val old = SyncPlaybackCounterShard("device", 0, 100, 2, 50, 99)
            val fresh = SyncPlaybackCounterShard("device", 100, 50, 1, 100, 200)
            val counters = PlaybackStatsSyncCounterSnapshot(mapOf(local.identityKey to listOf(old, fresh)), mapOf("0|${local.identityKey}" to listOf(old, fresh)))
            store.importLegacyAndPromote(listOf(local), listOf(bucket(local)), counters, 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val revision = checkNotNull(store.readPrimaryState()).revision
            assertTrue(repository.applySyncSnapshot(listedSource(emptyList(), emptyList()), 100, revision))
            val retained = store.readTrack(local.identityKey)
            assertEquals(local.copy(totalListenMs = 50, playCount = 1, firstPlayedAt = 100), retained)
            assertEquals(listOf(fresh), database.playbackStatsDao().getTrackCounters(local.identityKey).map { it.toDomain() })
            assertEquals(50L, database.playbackStatsDao().getBucket(0, local.identityKey)?.totalListenMs)
            assertEquals(100L, store.readPrimaryState()?.clearedAt)
        } finally { database.close() }
    }

    @Test
    fun capturedLocalAudioExcludedFromSyncRetainsCountersWhenRemoteHasTheSameIdentity() = runTest {
        verifyExcludedLocalIdentityMerge(backgroundScope, clearedAt = 0)
    }

    @Test
    fun capturedLocalAudioCollisionHonorsAdvancedClearAndRetainsNewLocalCounters() = runTest {
        verifyExcludedLocalIdentityMerge(backgroundScope, clearedAt = 100)
    }

    @Test
    fun unchangedManagedSourceDoesNotAdvanceDurableRevision() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val original = track(7)
            val shard = SyncPlaybackCounterShard("device", 0, original.totalListenMs, original.playCount,
                original.firstPlayedAt, original.lastPlayedAt)
            val counters = PlaybackStatsSyncCounterSnapshot(mapOf(original.identityKey to listOf(shard)),
                mapOf("0|${original.identityKey}" to listOf(shard)))
            store.importLegacyAndPromote(listOf(original), listOf(bucket(original)), counters, 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val revision = checkNotNull(store.readPrimaryState()).revision
            val source = listedSource(listOf(SyncPlaybackStatMapper.fromTrackStat(original, listOf(shard))),
                listOf(SyncPlaybackStatMapper.fromPlaybackStatBucket(bucket(original), listOf(shard))))
            assertTrue(repository.applySyncSnapshot(source, 0, revision))
            assertEquals(revision, store.readPrimaryState()?.revision)
            assertEquals(original, store.readTrack(original.identityKey))
            assertEquals(bucket(original), database.playbackStatsDao().getBucket(0, original.identityKey)?.toDomain())
        } finally { database.close() }
    }

    private suspend fun verifyExcludedLocalIdentityMerge(scope: CoroutineScope, clearedAt: Long) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val local = track(8).copy(localFilePath = "/audio.flac", localFileName = "audio.flac",
                customName = "my title", customArtist = "my artist", customCoverUrl = "https://cover.example/custom",
                totalListenMs = 150, playCount = 3, firstPlayedAt = 50)
            val old = SyncPlaybackCounterShard("local", 0, 100, 2, 50, 99)
            val fresh = SyncPlaybackCounterShard("local", 100, 50, 1, 100, 200)
            val remote = SyncPlaybackCounterShard("remote", 100, 25, 1, 150, 250)
            val counters = PlaybackStatsSyncCounterSnapshot(mapOf(local.identityKey to listOf(old, fresh)),
                mapOf("0|${local.identityKey}" to listOf(old, fresh)))
            store.importLegacyAndPromote(listOf(local), listOf(bucket(local)), counters, 0, 0)
            val repository = PlaybackStatsRepository(context, store, scope) { "local" }
            assertTrue(repository.awaitInitialized())
            val capturedTracks = mutableListOf<SyncTrackStat>()
            val capturedBuckets = mutableListOf<SyncPlaybackStatBucket>()
            val sink = object : SyncPlaybackSink {
                override suspend fun appendTracks(page: List<SyncTrackStat>) { capturedTracks.addAll(page) }
                override suspend fun appendBuckets(page: List<SyncPlaybackStatBucket>) { capturedBuckets.addAll(page) }
                override suspend fun seal() = listedSource(capturedTracks, capturedBuckets)
                override fun close() = Unit
            }
            val captured = repository.captureSyncSnapshot(sink)
            assertTrue(capturedTracks.isEmpty())
            assertTrue(capturedBuckets.isEmpty())
            val remoteTrack = local.copy(localFilePath = null, localFileName = null,
                totalListenMs = 25, playCount = 1, firstPlayedAt = 150, lastPlayedAt = 250)
            val source = listedSource(listOf(SyncPlaybackStatMapper.fromTrackStat(remoteTrack, listOf(remote))),
                listOf(SyncPlaybackStatMapper.fromPlaybackStatBucket(bucket(remoteTrack), listOf(remote))))
            val expectedShards = if (clearedAt > 0) listOf(fresh, remote) else listOf(old, fresh, remote)
            val expected = local.copy(totalListenMs = if (clearedAt > 0) 75 else 175,
                playCount = if (clearedAt > 0) 2 else 4, firstPlayedAt = if (clearedAt > 0) 100 else 50, lastPlayedAt = 250)
            assertTrue(repository.applySyncSnapshot(source, clearedAt, captured.revision))
            assertEquals(expected, store.readTrack(local.identityKey))
            assertEquals(bucket(expected), database.playbackStatsDao().getBucket(0, local.identityKey)?.toDomain())
            assertEquals(expectedShards, database.playbackStatsDao().getTrackCounters(local.identityKey).map { it.toDomain() })
            assertEquals(expectedShards, database.playbackStatsDao().getBucketCounters(0, local.identityKey).map { it.toDomain() })
            val unchangedRevision = checkNotNull(store.readPrimaryState()).revision
            assertTrue(repository.applySyncSnapshot(source, clearedAt, unchangedRevision))
            assertEquals(unchangedRevision, store.readPrimaryState()?.revision)
            assertEquals(expected, store.readTrack(local.identityKey))
            assertEquals(bucket(expected), database.playbackStatsDao().getBucket(0, local.identityKey)?.toDomain())
        } finally { database.close() }
    }

    private fun source(readTracks: suspend () -> List<SyncTrackStat>) = object : SyncPlaybackSource {
        override fun openTracks() = object : SyncPlaybackCursor<SyncTrackStat> {
            override suspend fun nextPage() = readTracks()
            override fun close() = Unit
        }
        override fun openBuckets(order: SyncPlaybackBucketOrder) = object : SyncPlaybackCursor<SyncPlaybackStatBucket> {
            override suspend fun nextPage() = emptyList<SyncPlaybackStatBucket>()
            override fun close() = Unit
        }
        override fun close() = Unit
    }
    private fun track(id: Long) = TrackStat(id, "song", "artist", "netease", 0, null, 180_000, 30_000, 1, 200, 100,
        null, null, null, null, null, null, "track|$id")
    private fun syncTrack(track: TrackStat) = SyncTrackStat(track.identityKey, track.name, track.artist, track.album,
        track.totalListenMs, track.playCount, track.lastPlayedAt, track.firstPlayedAt, track.coverUrl,
        track.durationMs, track.mediaUri, track.id, track.albumId)

    private fun bucket(track: TrackStat) = PlaybackStatBucket(0, track.id, track.name, track.artist, track.album, track.albumId,
        track.coverUrl, track.durationMs, track.totalListenMs, track.playCount, track.lastPlayedAt, track.firstPlayedAt,
        track.mediaUri, track.localFilePath, track.localFileName, track.customName, track.customArtist, track.customCoverUrl, track.identityKey)

    private fun listedSource(tracks: List<SyncTrackStat>, buckets: List<SyncPlaybackStatBucket>) = object : SyncPlaybackSource {
        override fun openTracks() = listedCursor(tracks)
        override fun openBuckets(order: SyncPlaybackBucketOrder) = listedCursor(when (order) {
            SyncPlaybackBucketOrder.DAY_IDENTITY -> buckets.sortedWith(compareBy<SyncPlaybackStatBucket> { it.dayStartAt }.thenBy { it.identityKey })
            SyncPlaybackBucketOrder.IDENTITY_DAY -> buckets.sortedWith(compareBy<SyncPlaybackStatBucket> { it.identityKey }.thenBy { it.dayStartAt })
        })
        override fun close() = Unit
    }

    private fun <T> listedCursor(rows: List<T>) = object : SyncPlaybackCursor<T> {
        private var offset = 0
        override suspend fun nextPage(): List<T> {
            val end = minOf(offset + 256, rows.size)
            return rows.subList(offset, end).also { offset = end }
        }
        override fun close() = Unit
    }
}
