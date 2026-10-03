package moe.ouom.neriplayer.data.local.database.store

import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStatsRoomStoreTest {
    @Test
    fun statsBucketsAndCounterShardsRoundTrip() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val stat = testTrackStat()
            val bucket = PlaybackStatBucket(
                dayStartAt = 86_400_000L,
                id = stat.id,
                name = stat.name,
                artist = stat.artist,
                album = stat.album,
                albumId = stat.albumId,
                coverUrl = stat.coverUrl,
                durationMs = stat.durationMs,
                totalListenMs = 30_000L,
                playCount = 1,
                lastPlayedAt = 200L,
                firstPlayedAt = 200L,
                mediaUri = stat.mediaUri,
                localFilePath = stat.localFilePath,
                localFileName = stat.localFileName,
                customName = stat.customName,
                customArtist = stat.customArtist,
                customCoverUrl = stat.customCoverUrl,
                identityKey = stat.identityKey
            )
            val shard = SyncPlaybackCounterShard(
                deviceId = "device-a",
                epochStartedAt = 10L,
                totalListenMs = 30_000L,
                playCount = 1,
                firstPlayedAt = 200L,
                lastPlayedAt = 200L
            )
            val counters = PlaybackStatsSyncCounterSnapshot(
                trackShardsByIdentity = mapOf(stat.identityKey to listOf(shard)),
                dailyShardsByBucketKey = mapOf(
                    PlaybackStatsSyncCounterSnapshot.dailyCounterKey(
                        dayStartAt = bucket.dayStartAt,
                        identityKey = bucket.identityKey
                    ) to listOf(shard)
                )
            )
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(
                stats = listOf(stat),
                dailyStats = listOf(bucket),
                counterSnapshot = counters,
                counterEpochStartedAt = 10L,
                clearedAt = 5L
            )

            val snapshot = store.readIfRoomPrimary()
            assertNotNull(snapshot)
            assertEquals(listOf(stat), snapshot?.stats)
            assertEquals(listOf(bucket), snapshot?.dailyStats)
            assertEquals(counters, snapshot?.counterSnapshot)
            assertEquals(5L, snapshot?.clearedAt)
        } finally {
            database.close()
        }
    }

    @Test
    fun incrementalWriteRemovesDeletedTrackAndItsBuckets() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val stat = testTrackStat()
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(
                stats = listOf(stat),
                dailyStats = emptyList(),
                counterSnapshot = PlaybackStatsSyncCounterSnapshot(),
                counterEpochStartedAt = 0L,
                clearedAt = 0L
            )
            store.writeIncremental(
                previousStats = listOf(stat),
                nextStats = emptyList(),
                previousDailyStats = emptyList(),
                nextDailyStats = emptyList(),
                previousCounterSnapshot = PlaybackStatsSyncCounterSnapshot(),
                counterSnapshot = PlaybackStatsSyncCounterSnapshot(),
                counterEpochStartedAt = 0L,
                clearedAt = 0L
            )

            val snapshot = store.readIfRoomPrimary()
            assertEquals(emptyList<TrackStat>(), snapshot?.stats)
            assertEquals(emptyList<PlaybackStatBucket>(), snapshot?.dailyStats)
        } finally {
            database.close()
        }
    }

    @Test
    fun incrementalWritePersistsCounterOnlyChanges() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val stat = testTrackStat()
            val previousCounter = PlaybackStatsSyncCounterSnapshot(
                trackShardsByIdentity = mapOf(
                    stat.identityKey to listOf(
                        SyncPlaybackCounterShard(
                            deviceId = "device-a",
                            epochStartedAt = 0L,
                            totalListenMs = 30_000L,
                            playCount = 1,
                            firstPlayedAt = 100L,
                            lastPlayedAt = 100L
                        )
                    )
                )
            )
            val nextCounter = PlaybackStatsSyncCounterSnapshot(
                trackShardsByIdentity = mapOf(
                    stat.identityKey to listOf(
                        SyncPlaybackCounterShard(
                            deviceId = "device-a",
                            epochStartedAt = 0L,
                            totalListenMs = 30_000L,
                            playCount = 2,
                            firstPlayedAt = 100L,
                            lastPlayedAt = 200L
                        )
                    )
                )
            )
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(
                stats = listOf(stat),
                dailyStats = emptyList(),
                counterSnapshot = previousCounter,
                counterEpochStartedAt = 0L,
                clearedAt = 0L
            )

            store.writeIncremental(
                previousStats = listOf(stat),
                nextStats = listOf(stat),
                previousDailyStats = emptyList(),
                nextDailyStats = emptyList(),
                previousCounterSnapshot = previousCounter,
                counterSnapshot = nextCounter,
                counterEpochStartedAt = 0L,
                clearedAt = 0L
            )

            assertEquals(nextCounter, store.readIfRoomPrimary()?.counterSnapshot)
        } finally {
            database.close()
        }
    }

    @Test
    fun incrementalWritePreservesDailyCounterChangesAcrossBatchesAndDeletesChildren() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val stats = (0L..503L).map { index ->
                testTrackStat().copy(id = index + 7, identityKey = "track|${index + 7}")
            }
            val buckets = stats.map(::testBucket)
            val shard = testCounter()
            val counters = PlaybackStatsSyncCounterSnapshot(
                trackShardsByIdentity = stats.associate { it.identityKey to listOf(shard) },
                dailyShardsByBucketKey = buckets.associate { it.counterKey() to listOf(shard) }
            )
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(stats, buckets, counters, counterEpochStartedAt = 10, clearedAt = 5)
            val nextStats = stats.drop(1)
            val nextBuckets = buckets.drop(1)
            val unchangedIdentity = nextStats.last().identityKey
            val nextCounters = PlaybackStatsSyncCounterSnapshot(
                trackShardsByIdentity = counters.trackShardsByIdentity - stats.first().identityKey,
                dailyShardsByBucketKey = nextBuckets.associate { bucket ->
                    bucket.counterKey() to listOf(
                        if (bucket.identityKey == unchangedIdentity) shard else shard.copy(playCount = 2, lastPlayedAt = 300)
                    )
                }
            )

            store.writeIncremental(stats, nextStats, buckets, nextBuckets, counters, nextCounters, 10, 5)

            val saved = requireNotNull(store.readIfRoomPrimary())
            assertEquals(nextStats.associateBy(TrackStat::identityKey), saved.stats.associateBy(TrackStat::identityKey))
            assertEquals(nextBuckets.associateBy { it.counterKey() }, saved.dailyStats.associateBy { it.counterKey() })
            assertEquals(nextCounters, saved.counterSnapshot)
            assertEquals(10L, saved.counterEpochStartedAt)
            assertEquals(5L, saved.clearedAt)
        } finally {
            database.close()
        }
    }

    @Test
    fun failedIncrementalWriteRollsBackBucketsCountersAndClearMetadata() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val stat = testTrackStat()
            val bucket = testBucket(stat)
            val shard = testCounter()
            val counters = PlaybackStatsSyncCounterSnapshot(
                trackShardsByIdentity = mapOf(stat.identityKey to listOf(shard)),
                dailyShardsByBucketKey = mapOf(bucket.counterKey() to listOf(shard))
            )
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(listOf(stat), listOf(bucket), counters, 10, 5)
            val before = requireNotNull(store.readIfRoomPrimary())
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_stat_update BEFORE UPDATE ON playback_stat " +
                    "BEGIN SELECT RAISE(ABORT, 'injected write failure'); END"
            )
            val failure = runCatching {
                store.writeIncremental(
                    previousStats = listOf(stat), nextStats = listOf(stat.copy(name = "updated")),
                    previousDailyStats = listOf(bucket), nextDailyStats = listOf(bucket),
                    previousCounterSnapshot = counters, counterSnapshot = counters,
                    counterEpochStartedAt = 20, clearedAt = 15
                )
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals(before, store.readIfRoomPrimary())
        } finally {
            database.close()
        }
    }

    @Test
    fun replaceAllPersistsLargeBucketAndShardGroupsWithoutOrphanRows() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val stats = (0L..1_000L).map { index ->
                testTrackStat().copy(id = index + 7, identityKey = "track|${index + 7}")
            }
            val stat = stats.first()
            val buckets = (0L..1_500L).map { day -> testBucket(stat).copy(dayStartAt = day * 86_400_000L) }
            val shards = (0..1_000).map { index ->
                testCounter().copy(deviceId = "device-${index.toString().padStart(4, '0')}")
            }
            val orphanBucket = testBucket(stat).copy(identityKey = "missing-track")
            val expectedCounters = PlaybackStatsSyncCounterSnapshot(
                trackShardsByIdentity = mapOf(stat.identityKey to shards),
                dailyShardsByBucketKey = buckets.associate { bucket ->
                    bucket.counterKey() to if (bucket == buckets.last()) shards else listOf(testCounter())
                }
            )
            val inputCounters = expectedCounters.copy(
                trackShardsByIdentity = expectedCounters.trackShardsByIdentity + ("missing-track" to shards),
                dailyShardsByBucketKey = expectedCounters.dailyShardsByBucketKey + (orphanBucket.counterKey() to shards)
            )
            val store = PlaybackStatsRoomStore(database)

            store.importLegacyAndPromote(stats, buckets + orphanBucket, inputCounters, 10, 5)

            val saved = requireNotNull(store.readIfRoomPrimary())
            assertEquals(stats.associateBy(TrackStat::identityKey), saved.stats.associateBy(TrackStat::identityKey))
            assertEquals(buckets, saved.dailyStats)
            assertEquals(expectedCounters, saved.counterSnapshot)
            assertEquals(10L, saved.counterEpochStartedAt)
            assertEquals(5L, saved.clearedAt)
        } finally {
            database.close()
        }
    }

    @Test
    fun incrementalWritePersistsSingleTrackBucketAndShardGroupsAcrossBatches() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val stat = testTrackStat()
            val initialBuckets = listOf(testBucket(stat))
            val buckets = (0L..1_500L).map { day -> testBucket(stat).copy(dayStartAt = day * 86_400_000L) }
            val shards = (0..1_000).map { index ->
                testCounter().copy(deviceId = "device-${index.toString().padStart(4, '0')}")
            }
            val counters = PlaybackStatsSyncCounterSnapshot(
                trackShardsByIdentity = mapOf(stat.identityKey to shards),
                dailyShardsByBucketKey = buckets.associate { bucket ->
                    bucket.counterKey() to if (bucket == buckets.last()) shards else listOf(testCounter())
                }
            )
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(listOf(stat), initialBuckets, PlaybackStatsSyncCounterSnapshot(), 0, 0)

            store.writeIncremental(
                listOf(stat), listOf(stat), initialBuckets, buckets,
                PlaybackStatsSyncCounterSnapshot(), counters, 10, 5
            )

            val saved = requireNotNull(store.readIfRoomPrimary())
            assertEquals(listOf(stat), saved.stats)
            assertEquals(buckets, saved.dailyStats)
            assertEquals(counters, saved.counterSnapshot)
            assertEquals(10L, saved.counterEpochStartedAt)
            assertEquals(5L, saved.clearedAt)
        } finally {
            database.close()
        }
    }

    @Test
    fun failedReplaceAllAfterFirstBucketBatchRollsBackEntireSnapshot() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val stat = testTrackStat()
            val initialBuckets = listOf(testBucket(stat))
            val counters = PlaybackStatsSyncCounterSnapshot(
                trackShardsByIdentity = mapOf(stat.identityKey to listOf(testCounter())),
                dailyShardsByBucketKey = initialBuckets.associate { it.counterKey() to listOf(testCounter()) }
            )
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(listOf(stat), initialBuckets, counters, 10, 5)
            val before = requireNotNull(store.readIfRoomPrimary())
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_later_bucket BEFORE INSERT ON playback_stat_bucket " +
                    "WHEN NEW.day_start_at = ${500L * 86_400_000L} " +
                    "BEGIN SELECT RAISE(ABORT, 'injected later bucket failure'); END"
            )
            val buckets = (0L..1_000L).map { day -> testBucket(stat).copy(dayStartAt = day * 86_400_000L) }

            val failure = runCatching {
                store.replaceAll(listOf(stat.copy(name = "updated")), buckets, counters, 20, 15)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals(before, store.readIfRoomPrimary())
        } finally {
            database.close()
        }
    }

    @Test
    fun failedIncrementalWriteAfterFirstBucketBatchRollsBackEntireSnapshot() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val stat = testTrackStat()
            val initialBuckets = listOf(testBucket(stat))
            val counters = PlaybackStatsSyncCounterSnapshot(
                trackShardsByIdentity = mapOf(stat.identityKey to listOf(testCounter())),
                dailyShardsByBucketKey = initialBuckets.associate { it.counterKey() to listOf(testCounter()) }
            )
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(listOf(stat), initialBuckets, counters, 10, 5)
            val before = requireNotNull(store.readIfRoomPrimary())
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_later_bucket BEFORE INSERT ON playback_stat_bucket " +
                    "WHEN NEW.day_start_at = ${500L * 86_400_000L} " +
                    "BEGIN SELECT RAISE(ABORT, 'injected later bucket failure'); END"
            )
            val buckets = (0L..1_000L).map { day -> testBucket(stat).copy(dayStartAt = day * 86_400_000L) }

            val failure = runCatching {
                store.writeIncremental(
                    listOf(stat), listOf(stat.copy(name = "updated")), initialBuckets, buckets,
                    counters, counters, 20, 15
                )
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals(before, store.readIfRoomPrimary())
        } finally {
            database.close()
        }
    }

    private fun testBucket(stat: TrackStat) = PlaybackStatBucket(
        dayStartAt = 86_400_000, id = stat.id, name = stat.name, artist = stat.artist,
        album = stat.album, albumId = stat.albumId, coverUrl = stat.coverUrl,
        durationMs = stat.durationMs, totalListenMs = stat.totalListenMs, playCount = stat.playCount,
        lastPlayedAt = stat.lastPlayedAt, firstPlayedAt = stat.firstPlayedAt,
        mediaUri = stat.mediaUri, localFilePath = stat.localFilePath, localFileName = stat.localFileName,
        customName = stat.customName, customArtist = stat.customArtist, customCoverUrl = stat.customCoverUrl,
        identityKey = stat.identityKey
    )

    private fun testCounter() = SyncPlaybackCounterShard(
        deviceId = "device-a", epochStartedAt = 10, totalListenMs = 30_000,
        playCount = 1, firstPlayedAt = 200, lastPlayedAt = 200
    )

    private fun PlaybackStatBucket.counterKey(): String = PlaybackStatsSyncCounterSnapshot.dailyCounterKey(dayStartAt, identityKey)

    private fun testTrackStat(): TrackStat {
        return TrackStat(
            id = 7L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 8L,
            coverUrl = "https://example.invalid/cover.jpg",
            durationMs = 180_000L,
            totalListenMs = 30_000L,
            playCount = 1,
            lastPlayedAt = 200L,
            firstPlayedAt = 200L,
            mediaUri = "https://example.invalid/song.mp3",
            localFilePath = null,
            localFileName = null,
            customName = null,
            customArtist = null,
            customCoverUrl = null,
            identityKey = "track|7"
        )
    }
}
