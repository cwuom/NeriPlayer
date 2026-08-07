package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.stats.TrackStat
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackCounterShard
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
