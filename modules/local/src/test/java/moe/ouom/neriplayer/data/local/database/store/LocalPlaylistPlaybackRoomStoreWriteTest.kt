package moe.ouom.neriplayer.data.local.database.store

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.dao.stats.LocalPlaylistPlaybackDao
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsSnapshotDao
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.LocalPlaylistPlaybackBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.LocalPlaylistPlaybackCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.LocalPlaylistPlaybackStatEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsEventReceiptEntity
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistPlaybackRoomStore.Companion.CUTOVER_STATE_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistPlaybackRoomStore.Companion.IMPORT_SCHEMA_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistPlaybackRoomStore.Companion.LEGACY_JSON_STATE
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistPlaybackRoomStore.Companion.ROOM_PRIMARY_STATE
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlayBucket
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

class LocalPlaylistPlaybackRoomStoreWriteTest {
    private val room = InlineTransactionDatabase()
    private val playback = InMemoryLocalPlaylistPlaybackDao()
    private val store = LocalPlaylistPlaybackRoomStore(room.database)

    init {
        doReturn(playback).`when`(room.database).localPlaylistPlaybackDao()
    }

    @Test
    fun `legacy stats with missing lists are imported and read back once room is primary`() = runTest {
        assertNull(store.readIfRoomPrimary())
        store.markLegacyJsonPrimary(now = 5)
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE, 5), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
        assertNull(store.readIfRoomPrimary())

        store.importLegacyAndPromote(legacyStats(LEGACY_JSON), now = 1_000)

        assertEquals(
            listOf(
                LocalPlaylistPlaybackStatEntity(1, 5, 10, 90, 2),
                LocalPlaylistPlaybackStatEntity(2, 1, 50, 50, 0)
            ),
            playback.getStats()
        )
        assertEquals(
            listOf(LocalPlaylistPlaybackBucketEntity(1, DAY, 4, 10, 90, 1), LocalPlaylistPlaybackBucketEntity(1, 2 * DAY, 1, 0, 0, 0)),
            playback.getBuckets()
        )
        assertEquals(
            listOf(
                LocalPlaylistPlaybackCounterShardEntity(1, 0, "phone", 1, 3, 10, 90),
                LocalPlaylistPlaybackCounterShardEntity(1, DAY, "phone", 1, 2, 10, 60)
            ),
            playback.getCounterShards()
        )
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, 1_000), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
        assertEquals(MigrationMetadataEntity(IMPORT_SCHEMA_METADATA_KEY, "1", 1_000), room.metadata.rows[IMPORT_SCHEMA_METADATA_KEY])
        assertEquals(
            listOf(
                LocalPlaylistPlaybackStat(
                    playlistId = 1,
                    totalPlayCount = 5,
                    firstPlayedAt = 10,
                    lastPlayedAt = 90,
                    counterBasePlayCount = 2,
                    counterShards = listOf(SyncPlaybackCounterShard("phone", 1, 0, 3, 10, 90)),
                    dailyPlayBuckets = listOf(
                        LocalPlaylistPlayBucket(DAY, 4, 10, 90, 1, listOf(SyncPlaybackCounterShard("phone", 1, 0, 2, 10, 60))),
                        LocalPlaylistPlayBucket(2 * DAY, 1)
                    )
                ),
                LocalPlaylistPlaybackStat(playlistId = 2, totalPlayCount = 1, firstPlayedAt = 50, lastPlayedAt = 50)
            ),
            store.readIfRoomPrimary()
        )
    }

    @Test
    fun `incremental writes rewrite changed playlists and delete removed ones`() = runTest {
        val kept = LocalPlaylistPlaybackStat(playlistId = 1, totalPlayCount = 2, dailyPlayBuckets = listOf(LocalPlaylistPlayBucket(DAY, 2)))
        val changed = LocalPlaylistPlaybackStat(playlistId = 2, totalPlayCount = 1, dailyPlayBuckets = listOf(LocalPlaylistPlayBucket(DAY, 1)))
        val removed = LocalPlaylistPlaybackStat(
            playlistId = 3,
            totalPlayCount = 1,
            counterShards = listOf(SyncPlaybackCounterShard("phone", 1, 0, 1, 5, 5)),
            dailyPlayBuckets = listOf(LocalPlaylistPlayBucket(DAY, 1))
        )
        store.replaceAll(listOf(kept, changed, removed), now = 1)
        val rewritten = changed.copy(totalPlayCount = 2, dailyPlayBuckets = listOf(LocalPlaylistPlayBucket(2 * DAY, 1)))
        val added = LocalPlaylistPlaybackStat(playlistId = 4, totalPlayCount = 1)
        playback.deletions.clear()

        store.writeIncremental(listOf(kept, changed, removed), listOf(kept, rewritten, added), now = 2)

        assertEquals(listOf("shards[3]", "buckets[3]", "stats[3]", "shards[2, 4]", "buckets[2, 4]"), playback.deletions)
        assertEquals(listOf(kept, rewritten, added), store.readIfRoomPrimary())
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, 2), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
    }

    @Test
    fun `an event receipt applies a play once and rejects reuse with another payload`() = runTest {
        val receipts = linkedMapOf<String, PlaybackStatsEventReceiptEntity>()
        val snapshots = mock(PlaybackStatsSnapshotDao::class.java)
        doAnswer { receipts[it.getArgument(0)] }.`when`(snapshots).receipt(anyString())
        doAnswer { receipts[it.getArgument<PlaybackStatsEventReceiptEntity>(0).id] = it.getArgument(0) }
            .`when`(snapshots).insertReceipt(any(PlaybackStatsEventReceiptEntity::class.java) ?: PlaybackStatsEventReceiptEntity("", 0, ""))
        doReturn(snapshots).`when`(room.database).playbackStatsSnapshotDao()
        val played = LocalPlaylistPlaybackStat(playlistId = 1, totalPlayCount = 1, firstPlayedAt = 10, lastPlayedAt = 10)

        assertTrue(store.writeIncrementalOnce(emptyList(), listOf(played), "evt", "hash", now = 10))
        assertFalse(store.writeIncrementalOnce(listOf(played), listOf(played.copy(totalPlayCount = 2)), "evt", "hash", now = 11))
        room.transactionLog.clear()
        expectFailure<LocalPlaylistPlaybackEventConflictException> {
            store.writeIncrementalOnce(listOf(played), listOf(played.copy(totalPlayCount = 2)), "evt", "other", now = 12)
        }

        assertEquals(listOf("begin", "end"), room.transactionLog)
        assertEquals(mapOf("local-playlist-play:evt" to PlaybackStatsEventReceiptEntity("local-playlist-play:evt", 10, "hash")), receipts)
        assertEquals(listOf(played), store.readIfRoomPrimary())
        verify(snapshots, times(1)).pruneCompletedReceipts(PlaybackStatsRoomStore.RETAINED_EVENT_RECEIPTS)
    }

    private fun legacyStats(json: String): List<LocalPlaylistPlaybackStat> =
        Gson().fromJson(json, object : TypeToken<List<LocalPlaylistPlaybackStat>>() {}.type)

    private companion object {
        const val DAY = 86_400_000L
        val LEGACY_JSON = """
            [
              {"playlistId": 2, "totalPlayCount": 1, "firstPlayedAt": 50, "lastPlayedAt": 50},
              {
                "playlistId": 1, "totalPlayCount": 5, "firstPlayedAt": 10, "lastPlayedAt": 90, "counterBasePlayCount": 2,
                "counterShards": [{"deviceId": "phone", "epochStartedAt": 1, "playCount": 3, "firstPlayedAt": 10, "lastPlayedAt": 90}],
                "dailyPlayBuckets": [
                  {
                    "dayStartAt": $DAY, "playCount": 4, "firstPlayedAt": 10, "lastPlayedAt": 90, "counterBasePlayCount": 1,
                    "counterShards": [{"deviceId": "phone", "epochStartedAt": 1, "playCount": 2, "firstPlayedAt": 10, "lastPlayedAt": 60}]
                  },
                  {"dayStartAt": ${2 * DAY}, "playCount": 1}
                ]
              }
            ]
        """.trimIndent()
    }
}

/** Playlist playback tables keyed like their Room primary keys and returned in the DAO's query order. */
private class InMemoryLocalPlaylistPlaybackDao : LocalPlaylistPlaybackDao {
    private val stats = linkedMapOf<Long, LocalPlaylistPlaybackStatEntity>()
    private val buckets = linkedMapOf<List<Any>, LocalPlaylistPlaybackBucketEntity>()
    private val shards = linkedMapOf<List<Any>, LocalPlaylistPlaybackCounterShardEntity>()
    val deletions = mutableListOf<String>()

    override suspend fun getStats() = stats.values.sortedBy { it.playlistId }

    override suspend fun getBuckets() = buckets.values.sortedWith(compareBy({ it.playlistId }, { it.dayStartAt }))

    override suspend fun getCounterShards() =
        shards.values.sortedWith(compareBy({ it.playlistId }, { it.dayStartAt }, { it.deviceId }))

    override suspend fun upsertStats(stats: List<LocalPlaylistPlaybackStatEntity>) {
        stats.forEach { this.stats[it.playlistId] = it }
    }

    override suspend fun upsertBuckets(buckets: List<LocalPlaylistPlaybackBucketEntity>) {
        buckets.forEach { this.buckets[listOf(it.playlistId, it.dayStartAt)] = it }
    }

    override suspend fun upsertCounterShards(shards: List<LocalPlaylistPlaybackCounterShardEntity>) {
        shards.forEach { this.shards[listOf(it.playlistId, it.dayStartAt, it.deviceId, it.epochStartedAt)] = it }
    }

    override suspend fun deleteCounterShards(playlistIds: List<Long>) {
        deletions.add("shards$playlistIds")
        shards.values.removeAll { it.playlistId in playlistIds }
    }

    override suspend fun deleteBuckets(playlistIds: List<Long>) {
        deletions.add("buckets$playlistIds")
        buckets.values.removeAll { it.playlistId in playlistIds }
    }

    override suspend fun deleteStats(playlistIds: List<Long>) {
        deletions.add("stats$playlistIds")
        stats.keys.removeAll(playlistIds.toSet())
    }

    override suspend fun deleteAllCounterShards() = shards.clear()

    override suspend fun deleteAllBuckets() = buckets.clear()

    override suspend fun deleteAllStats() = stats.clear()
}
