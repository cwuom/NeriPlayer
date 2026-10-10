package moe.ouom.neriplayer.data.local.database.store.stats

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsDao
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsSnapshotDao
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsSummaryRow
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatDailyCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsPendingDeltaEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotTrackEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toSnapshotData
import moe.ouom.neriplayer.data.local.database.store.InlineTransactionDatabase
import moe.ouom.neriplayer.data.local.database.store.expectFailure
import moe.ouom.neriplayer.data.local.database.store.invokedMethods
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.CLEARED_AT_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.COUNTER_EPOCH_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.CUTOVER_STATE_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.IMPORT_SCHEMA_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.LEGACY_JSON_STATE
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.RETAINED_EVENT_RECEIPTS
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.REVISION_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.ROOM_PRIMARY_STATE
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.stats.playbackStatsDayStartAt
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.stats.PlaybackStatsCursor
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PlaybackStatsRoomStoreSnapshotLifecycleTest {
    private val room = InlineTransactionDatabase()
    private val metadata = room.metadata
    private val stats = mock(PlaybackStatsDao::class.java)
    private val snapshots = mock(PlaybackStatsSnapshotDao::class.java)
    private lateinit var store: PlaybackStatsRoomStore

    @Before
    fun setUp() {
        doReturn(stats).`when`(room.database).playbackStatsDao()
        doReturn(snapshots).`when`(room.database).playbackStatsSnapshotDao()
        `when`(stats.observeRevision()).thenReturn(MutableStateFlow(null))
        `when`(stats.observeClearedAt()).thenReturn(MutableStateFlow(null))
        store = PlaybackStatsRoomStore(room.database)
    }

    @Test
    fun `frozen snapshot copies every primary table under the current revision`() = runTest {
        primary(revision = 6, clearedAt = 20, epoch = 25)
        doReturn(0L).`when`(snapshots).pendingDeltaCount()

        val snapshot = store.freezeSnapshot()

        assertEquals(listOf(6L, 20L, 25L), listOf(snapshot.revision, snapshot.clearedAt, snapshot.counterEpochStartedAt))
        assertTrue(snapshot.sealed)
        assertFalse(snapshot.isDiff)
        verify(snapshots).upsertSnapshot(snapshot)
        assertEquals(
            listOf("pendingDeltaCount", "upsertSnapshot", "freezeTrack", "freezeBucket", "freezeCounter", "freezeDailyCounter"),
            invokedMethods(snapshots)
        )
    }

    @Test
    fun `snapshot creation that fails inside the transaction releases its copy`() = runTest {
        primary(revision = 6, clearedAt = 20, epoch = 25)
        doReturn(1L).`when`(snapshots).pendingDeltaCount()

        val failure = expectFailure<IllegalStateException> { store.freezeSnapshot() }

        assertEquals("Playback statistics have pending deltas", failure.message)
        assertEquals(
            listOf(
                "pendingDeltaCount", "deleteBucketTombstones", "deleteTrackTombstones", "deleteDailyCounter",
                "deleteCounter", "deleteBucket", "deleteTrack", "deleteSnapshot"
            ),
            invokedMethods(snapshots)
        )

        metadata.put(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE)
        val uninitialized = expectFailure<IllegalStateException> { store.beginDiffSnapshot(clearedAt = 0) }
        assertEquals("Playback statistics are not initialized", uninitialized.message)
    }

    @Test
    fun `diff snapshot raises its barrier to the newer clear without copying rows`() = runTest {
        primary(revision = 2, clearedAt = 100, epoch = 150)
        doReturn(0L).`when`(snapshots).pendingDeltaCount()

        val raised = store.beginDiffSnapshot(clearedAt = 300)
        val kept = store.beginDiffSnapshot(clearedAt = 50)

        assertEquals(listOf(2L, 300L, 300L), listOf(raised.revision, raised.clearedAt, raised.counterEpochStartedAt))
        assertEquals(listOf(2L, 100L, 150L), listOf(kept.revision, kept.clearedAt, kept.counterEpochStartedAt))
        assertTrue(raised.isDiff && kept.isDiff)
        assertFalse(raised.sealed || kept.sealed)
        verify(snapshots, never()).freezeTrack(raised.id)

        doReturn(4L).`when`(snapshots).pendingDeltaCount()
        val pending = expectFailure<IllegalStateException> { store.beginDiffSnapshot(clearedAt = 0) }
        assertEquals("Playback statistics have pending deltas", pending.message)
    }

    @Test
    fun `legacy import snapshot starts empty at the current revision`() = runTest {
        primary(revision = 11, clearedAt = 40, epoch = 40)

        val snapshot = store.beginLegacyImport()

        assertEquals(listOf(11L, 0L, 0L), listOf(snapshot.revision, snapshot.clearedAt, snapshot.counterEpochStartedAt))
        assertFalse(snapshot.sealed || snapshot.isDiff)
        assertEquals(listOf("upsertSnapshot"), invokedMethods(snapshots))
    }

    @Test
    fun `captured revision is current only while the journal is empty`() = runTest {
        primary(revision = 5, clearedAt = 0, epoch = 0)
        doReturn(0L).`when`(snapshots).pendingDeltaCount()

        assertTrue(store.checkCapturedRevision(5))
        assertFalse(store.checkCapturedRevision(4))
        doReturn(3L).`when`(snapshots).pendingDeltaCount()
        assertFalse(store.checkCapturedRevision(5))

        metadata.put(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE)
        val failure = expectFailure<IllegalStateException> { store.checkCapturedRevision(5) }
        assertEquals("Playback statistics are unavailable", failure.message)
    }

    @Test
    fun `frozen track pages are bounded and mapped back to domain stats`() = runTest {
        val frozen = statEntity("track|1")
        doReturn(listOf(PlaybackStatsSnapshotTrackEntity("frozen", frozen.toSnapshotData())))
            .`when`(snapshots).trackPage("frozen", "track|0", 2)

        assertEquals(listOf(frozen.toDomain()), store.readFrozenTracks("frozen", "track|0", 2))
        for (limit in listOf(0, 257)) {
            expectFailure<IllegalArgumentException> { store.readFrozenTracks("frozen", null, limit) }
        }
    }

    @Test
    fun `frozen snapshot replaces the primary rows only while the revision is unchanged`() = runTest {
        primary(revision = 8, clearedAt = 10, epoch = 10)
        doReturn(sealedSnapshot("frozen")).`when`(snapshots).getSnapshot("frozen")
        doReturn(0L).`when`(snapshots).pendingDeltaCount()

        assertFalse(store.commitFrozenSnapshot("frozen", expectedRevision = 7))
        doReturn(2L).`when`(snapshots).pendingDeltaCount()
        assertFalse(store.commitFrozenSnapshot("frozen", expectedRevision = 8))
        verify(stats, never()).deleteAllStats()

        doReturn(0L).`when`(snapshots).pendingDeltaCount()
        assertTrue(store.commitFrozenSnapshot("frozen", expectedRevision = 8))

        val order = inOrder(stats, snapshots)
        order.verify(stats).deleteAllDailyCounterShards()
        order.verify(stats).deleteAllCounterShards()
        order.verify(stats).deleteAllBuckets()
        order.verify(stats).deleteAllStats()
        order.verify(snapshots).publishTrack("frozen")
        order.verify(snapshots).publishBucket("frozen")
        order.verify(snapshots).publishCounter("frozen")
        order.verify(snapshots).publishDailyCounter("frozen")
        assertEquals(listOf("9", "40", "45"), listOf(REVISION_METADATA_KEY, CLEARED_AT_METADATA_KEY, COUNTER_EPOCH_METADATA_KEY).map(metadata::value))
    }

    @Test
    fun `only a sealed complete snapshot may replace the primary store`() = runTest {
        primary(revision = 8, clearedAt = 10, epoch = 10)

        val missing = expectFailure<IllegalStateException> { store.commitFrozenSnapshot("gone", 8) }
        doReturn(sealedSnapshot("diff").copy(isDiff = true)).`when`(snapshots).getSnapshot("diff")
        val diff = expectFailure<IllegalStateException> { store.commitFrozenSnapshot("diff", 8) }
        doReturn(sealedSnapshot("open").copy(sealed = false)).`when`(snapshots).getSnapshot("open")
        val open = expectFailure<IllegalStateException> { store.commitFrozenSnapshot("open", 8) }

        assertEquals("Playback snapshot no longer exists", missing.message)
        assertEquals("A playback diff cannot replace the complete primary store", diff.message)
        assertEquals("Playback snapshot is incomplete", open.message)
        verify(stats, never()).deleteAllStats()
        assertEquals("8", metadata.value(REVISION_METADATA_KEY))
    }

    @Test
    fun `journal delta merges the current rows and consumes the entry atomically`() = runTest {
        primary(revision = 1, clearedAt = 100, epoch = 100)
        val delta = delta("event", epoch = 100)
        val day = playbackStatsDayStartAt(delta.playedAt)
        val current = PlaybackStatsDeltaRows(statEntity("k"), bucketEntity(day, "k"),
            PlaybackStatCounterShardEntity("k", "phone", 100, 1, 1, 1, 1),
            PlaybackStatDailyCounterShardEntity(day, "k", "phone", 100, 1, 1, 1, 1))
        val merged = PlaybackStatsDeltaRows(statEntity("k").copy(playCount = 3), bucketEntity(day, "k").copy(playCount = 3),
            current.counter?.copy(playCount = 2), current.dailyCounter?.copy(playCount = 2))
        doReturn(delta).`when`(snapshots).pendingDelta("event")
        doReturn(current.track).`when`(stats).getTrack("k")
        doReturn(current.bucket).`when`(stats).getBucket(day, "k")
        doReturn(current.counter).`when`(stats).getOwnedCounter("k", "phone", 100)
        doReturn(current.dailyCounter).`when`(stats).getOwnedDailyCounter(day, "k", "phone", 100)
        var seen: PlaybackStatsDeltaRows? = null

        store.applyDelta(delta, "k") { rows -> seen = rows; merged }

        assertEquals(current, seen)
        verify(stats).upsertStats(listOf(checkNotNull(merged.track)))
        verify(stats).upsertBuckets(listOf(checkNotNull(merged.bucket)))
        verify(stats).upsertCounterShards(listOf(checkNotNull(merged.counter)))
        verify(stats).upsertDailyCounterShards(listOf(checkNotNull(merged.dailyCounter)))
        verify(snapshots).deletePendingDelta("event")
        verify(snapshots).pruneCompletedReceipts(RETAINED_EVENT_RECEIPTS)
        assertEquals(MigrationMetadataEntity(REVISION_METADATA_KEY, "2", delta.playedAt), metadata.rows[REVISION_METADATA_KEY])
        assertEquals(listOf("begin", "commit", "end"), room.transactionLog)
    }

    @Test
    fun `consumed or pre clear deltas never reach the counters`() = runTest {
        primary(revision = 1, clearedAt = 100, epoch = 100)
        val stale = delta("stale", epoch = 99)
        doReturn(stale).`when`(snapshots).pendingDelta("stale")

        store.applyDelta(delta("gone", epoch = 100), "k") { error("a consumed delta must not be merged") }
        store.applyDelta(stale, "k") { error("a delta from before the clear must not be merged") }

        verify(snapshots, never()).deletePendingDelta("gone")
        verify(snapshots).deletePendingDelta("stale")
        verify(snapshots).pruneCompletedReceipts(RETAINED_EVENT_RECEIPTS)
        verify(stats, never()).getTrack("k")
        assertEquals("1", metadata.value(REVISION_METADATA_KEY))
    }

    @Test
    fun `incomplete merge rows roll the delta transaction back`() = runTest {
        primary(revision = 1, clearedAt = 100, epoch = 100)
        val delta = delta("event", epoch = 100)
        val day = playbackStatsDayStartAt(delta.playedAt)
        doReturn(delta).`when`(snapshots).pendingDelta("event")
        val complete = PlaybackStatsDeltaRows(statEntity("k"), bucketEntity(day, "k"),
            PlaybackStatCounterShardEntity("k", "phone", 100, 1, 1, 1, 1),
            PlaybackStatDailyCounterShardEntity(day, "k", "phone", 100, 1, 1, 1, 1))

        for (rows in listOf(complete.copy(track = null), complete.copy(bucket = null),
            complete.copy(counter = null), complete.copy(dailyCounter = null))) {
            room.transactionLog.clear()
            expectFailure<IllegalStateException> { store.applyDelta(delta, "k") { rows } }
            assertEquals(listOf("begin", "end"), room.transactionLog)
        }
        verify(snapshots, never()).deletePendingDelta("event")
        assertEquals("1", metadata.value(REVISION_METADATA_KEY))
    }

    @Test
    fun `incremental write rewrites only keys whose stats, buckets or counter shards changed`() = runTest {
        metadata.put(REVISION_METADATA_KEY, "2")
        val day = 3 * DAY
        val unchangedBucket = bucket(day, "g", listen = 50)
        val previous = listOf(stat("a"), stat("b"), stat("d"), stat("e"), stat("g"))
        val next = listOf(stat("a"), stat("b", listen = 2_000), stat("c"), stat("e"), stat("g"))
        val previousDaily = listOf(bucket(day, "e", listen = 100), unchangedBucket)
        val nextDaily = listOf(bucket(day, "e", listen = 200), bucket(day, "c", listen = 300), unchangedBucket)
        val phoneOld = SyncPlaybackCounterShard("phone", 10, 100, 1, 50, 60)
        val phoneNew = phoneOld.copy(playCount = 2)
        val tablet = SyncPlaybackCounterShard("tablet", 10, 300, 1, 70, 80)
        val previousCounters = PlaybackStatsSyncCounterSnapshot(trackShardsByIdentity = mapOf("a" to listOf(phoneOld)))
        val nextCounters = PlaybackStatsSyncCounterSnapshot(
            trackShardsByIdentity = mapOf("a" to listOf(phoneNew)),
            dailyShardsByBucketKey = mapOf(PlaybackStatsSyncCounterSnapshot.dailyCounterKey(day, "c") to listOf(tablet))
        )

        store.writeIncremental(previous, next, previousDaily, nextDaily, previousCounters, nextCounters,
            counterEpochStartedAt = 15, clearedAt = 12, now = 900)

        val rewritten = listOf("a", "b", "d", "e", "c")
        verify(stats).deleteDailyCounterShards(rewritten)
        verify(stats).deleteCounterShards(rewritten)
        verify(stats).deleteBuckets(rewritten)
        verify(stats).deleteStats(listOf("d"))
        verify(stats).upsertStats(listOf(stat("a"), stat("b", listen = 2_000), stat("e"), stat("c")).map(TrackStat::toEntity))
        verify(stats).upsertBuckets(listOf(bucket(day, "e", listen = 200), bucket(day, "c", listen = 300)).map(PlaybackStatBucket::toEntity))
        verify(stats).upsertCounterShards(listOf(phoneNew.toTrackEntity("a")))
        verify(stats).upsertDailyCounterShards(listOf(tablet.toDailyEntity(day, "c")))
        assertEquals(
            listOf(ROOM_PRIMARY_STATE, "1", "12", "15", "3"),
            listOf(CUTOVER_STATE_METADATA_KEY, IMPORT_SCHEMA_METADATA_KEY, CLEARED_AT_METADATA_KEY,
                COUNTER_EPOCH_METADATA_KEY, REVISION_METADATA_KEY).map(metadata::value)
        )
    }

    @Test
    fun `summary reports a legacy breakdown only for bounded periods with legacy rows`() = runTest {
        val queries = mutableListOf<SupportSQLiteQuery>()
        doAnswer { invocation -> queries += invocation.getArgument<SupportSQLiteQuery>(0); PlaybackStatsSummaryRow(3, 7, 9_000) }
            .`when`(stats).querySummary(anyQuery())
        doReturn(true).`when`(stats).hasBuckets()
        doReturn(true).`when`(stats).hasStats()
        doReturn(false).`when`(stats).hasLegacyStats()

        val all = store.readSummary(PlaybackStatsQuery(period = PlaybackStatsPeriod.ALL, nowMillis = NOW))
        doReturn(true).`when`(stats).hasLegacyStats()
        val week = store.readSummary(PlaybackStatsQuery(period = PlaybackStatsPeriod.WEEK, nowMillis = NOW))
        doReturn(false).`when`(stats).hasStats()
        val empty = store.readSummary(PlaybackStatsQuery(period = PlaybackStatsPeriod.WEEK, nowMillis = NOW))

        assertEquals(PlaybackStatsSummary(3, 7, 9_000, usesLegacyBreakdown = false, hasAnyStats = true), all)
        assertEquals(PlaybackStatsSummary(3, 7, 9_000, usesLegacyBreakdown = true, hasAnyStats = true), week)
        assertEquals(PlaybackStatsSummary(3, 7, 9_000, usesLegacyBreakdown = false, hasAnyStats = false), empty)
        assertFalse(queries[0].sql.contains("UNION ALL"))
        assertTrue(queries[1].sql.contains("UNION ALL"))
    }

    @Test
    fun `pages over fetch one row to decide which neighbouring cursors exist`() = runTest {
        val query = PlaybackStatsQuery(sort = PlaybackStatsSort.PLAY_COUNT, nowMillis = NOW)
        val first = listOf(stat("a", plays = 9), stat("b", plays = 5), stat("c", plays = 1))
        val earlier = listOf(stat("y", plays = 20), stat("x", plays = 30), stat("w", plays = 40))
        doReturn(false).`when`(stats).hasBuckets()
        doReturn(first.map(TrackStat::toEntity)).doReturn(earlier.map(TrackStat::toEntity))
            .doReturn(first.take(2).map(TrackStat::toEntity))
            .`when`(stats).queryStats(anyQuery())

        val forward = store.readPage(query, after = null, limit = 2)
        val backward = store.readPage(query, after = PlaybackStatsCursor(9, "a"), limit = 2, before = true)
        val last = store.readPage(query, after = PlaybackStatsCursor(30, "x"), limit = 2)

        assertEquals(listOf("a", "b"), forward.tracks.map { it.identityKey })
        assertEquals(PlaybackStatsCursor(5, "b", 1_000, 300), forward.nextCursor)
        assertNull(forward.previousCursor)
        assertEquals(listOf("x", "y"), backward.tracks.map { it.identityKey })
        assertEquals(PlaybackStatsCursor(20, "y", 1_000, 300), backward.nextCursor)
        assertEquals(PlaybackStatsCursor(30, "x", 1_000, 300), backward.previousCursor)
        assertNull(last.nextCursor)
        assertEquals(PlaybackStatsCursor(9, "a", 1_000, 300), last.previousCursor)
        for (limit in listOf(0, 257)) {
            expectFailure<IllegalArgumentException> { store.readPage(query, after = null, limit = limit) }
        }
    }

    private fun primary(revision: Long, clearedAt: Long, epoch: Long) {
        metadata.put(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE)
        metadata.put(REVISION_METADATA_KEY, revision.toString())
        metadata.put(CLEARED_AT_METADATA_KEY, clearedAt.toString())
        metadata.put(COUNTER_EPOCH_METADATA_KEY, epoch.toString())
    }

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_760_000_000_000L

        fun anyQuery(): SupportSQLiteQuery = any(SupportSQLiteQuery::class.java) ?: SimpleSQLiteQuery("")

        fun sealedSnapshot(id: String) = PlaybackStatsSnapshotEntity(id, 8, 40, 45, 1, true, "owner")

        fun delta(id: String, epoch: Long) = PlaybackStatsPendingDeltaEntity(id, 1, "{}", 500, 1, 10 * DAY + 3_600_000, epoch, "phone")

        fun statEntity(key: String) = PlaybackStatEntity(
            key, 7, "song", "artist", "album", 3, null, 180_000, 1_000, 2, 300, 100,
            null, null, null, null, null, null
        )

        fun bucketEntity(day: Long, key: String) = PlaybackStatBucketEntity(
            day, key, 7, "song", "artist", "album", 3, null, 180_000, 1_000, 2, 300, 100,
            null, null, null, null, null, null
        )

        fun stat(key: String, listen: Long = 1_000, plays: Int = 2) = TrackStat(
            7, "song $key", "artist", "album", 3, null, 180_000, listen, plays, 300, 100,
            null, null, null, null, null, null, key
        )

        fun bucket(day: Long, key: String, listen: Long) = PlaybackStatBucket(
            day, 7, "song $key", "artist", "album", 3, null, 180_000, listen, 1, day + 10, day + 5,
            null, null, null, null, null, null, key
        )
    }
}
