package moe.ouom.neriplayer.data.local.database.store.stats

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsDao
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsSnapshotDao
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatDailyCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatEntity
import moe.ouom.neriplayer.data.local.database.store.InlineTransactionDatabase
import moe.ouom.neriplayer.data.local.database.store.expectFailure
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.CAPTURE_DATABASE_INSTANCE_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.CLEARED_AT_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.COUNTER_EPOCH_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.CUTOVER_STATE_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.JOURNAL_SEQUENCE_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.LEGACY_JSON_STATE
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.REVISION_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.ROOM_PRIMARY_STATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PlaybackStatsRoomStoreMetadataTest {
    private val room = InlineTransactionDatabase()
    private val metadata = room.metadata
    private val stats = mock(PlaybackStatsDao::class.java)
    private val snapshots = mock(PlaybackStatsSnapshotDao::class.java)
    private val storedClearedAt = MutableStateFlow<String?>(null)
    private lateinit var store: PlaybackStatsRoomStore

    @Before
    fun setUp() {
        doReturn(stats).`when`(room.database).playbackStatsDao()
        doReturn(snapshots).`when`(room.database).playbackStatsSnapshotDao()
        `when`(stats.observeRevision()).thenReturn(MutableStateFlow("4"))
        `when`(stats.observeClearedAt()).thenReturn(storedClearedAt)
        store = PlaybackStatsRoomStore(room.database)
    }

    @Test
    fun `snapshot is read only after the cutover marker names the room store`() = runTest {
        assertNull(store.readIfRoomPrimary())
        metadata.put(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE)
        assertNull(store.readIfRoomPrimary())
        verify(stats, never()).getStats()

        metadata.put(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE)
        metadata.put(CLEARED_AT_METADATA_KEY, "12")
        metadata.put(COUNTER_EPOCH_METADATA_KEY, "30")
        doReturn(listOf(statEntity("track|1"))).`when`(stats).getStats()
        doReturn(listOf(bucketEntity(DAY, "track|1"))).`when`(stats).getBuckets()
        doReturn(listOf(counterEntity("track|1", "phone"), counterEntity("track|1", "tablet")))
            .`when`(stats).getCounterShards()
        doReturn(listOf(dailyEntity(DAY, "track|1", "phone"))).`when`(stats).getDailyCounterShards()

        val snapshot = checkNotNull(store.readIfRoomPrimary())

        assertEquals(listOf(statEntity("track|1").toDomain()), snapshot.stats)
        assertEquals(listOf(bucketEntity(DAY, "track|1").toDomain()), snapshot.dailyStats)
        assertEquals(listOf("phone", "tablet"), snapshot.counterSnapshot.trackShards("track|1").map { it.deviceId })
        assertEquals(setOf("$DAY|track|1"), snapshot.counterSnapshot.dailyShardsByBucketKey.keys)
        assertEquals(listOf("phone"), snapshot.counterSnapshot.dailyShards(DAY, "track|1").map { it.deviceId })
        assertEquals(12L, snapshot.clearedAt)
        assertEquals(30L, snapshot.counterEpochStartedAt)
    }

    @Test
    fun `snapshot clear metadata is clamped and an unreadable epoch falls back to the clear barrier`() = runTest {
        metadata.put(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE)
        doReturn(emptyList<PlaybackStatEntity>()).`when`(stats).getStats()
        doReturn(emptyList<PlaybackStatBucketEntity>()).`when`(stats).getBuckets()
        doReturn(emptyList<PlaybackStatCounterShardEntity>()).`when`(stats).getCounterShards()
        doReturn(emptyList<PlaybackStatDailyCounterShardEntity>()).`when`(stats).getDailyCounterShards()

        suspend fun barriers(clearedAt: String?, epoch: String?): Pair<Long, Long> {
            metadata.put(CLEARED_AT_METADATA_KEY, clearedAt)
            metadata.put(COUNTER_EPOCH_METADATA_KEY, epoch)
            val snapshot = checkNotNull(store.readIfRoomPrimary())
            return snapshot.clearedAt to snapshot.counterEpochStartedAt
        }

        assertEquals(0L to 0L, checkNotNull(store.readIfRoomPrimary()).let { it.clearedAt to it.counterEpochStartedAt })
        assertEquals(40L to 40L, barriers("40", "soon"))
        assertEquals(0L to 0L, barriers("-4", "-9"))
        assertEquals(0L to 70L, barriers(null, "70"))
        assertEquals(0L to 0L, barriers("garbage", null))
    }

    @Test
    fun `primary state accepts only known cutover markers`() = runTest {
        assertNull(store.readPrimaryState())
        metadata.put(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE)
        assertNull(store.readPrimaryState())

        metadata.put(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE)
        assertEquals(PlaybackStatsRoomState(0, 0, 0), store.readPrimaryState())

        metadata.put(CUTOVER_STATE_METADATA_KEY, "room_secondary")
        val failure = expectFailure<IOException> { store.readPrimaryState() }
        assertEquals("Unknown playback statistics primary marker: room_secondary", failure.message)
    }

    @Test
    fun `primary state defaults missing counters and rejects negative or non numeric ones`() = runTest {
        metadata.put(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE)
        metadata.put(REVISION_METADATA_KEY, null)
        metadata.put(CLEARED_AT_METADATA_KEY, "25")
        assertEquals(PlaybackStatsRoomState(0, 25, 25), store.readPrimaryState())

        metadata.put(COUNTER_EPOCH_METADATA_KEY, "40")
        metadata.put(REVISION_METADATA_KEY, "9")
        assertEquals(PlaybackStatsRoomState(9, 25, 40), store.readPrimaryState())

        for (invalid in listOf("-1", "nine")) {
            metadata.put(REVISION_METADATA_KEY, invalid)
            val failure = expectFailure<IOException> { store.readPrimaryState() }
            assertEquals("Invalid playback statistics metadata: $REVISION_METADATA_KEY", failure.message)
        }
    }

    @Test
    fun `capture stamp mints one canonical database instance and reuses it`() = runTest {
        metadata.put(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE)
        metadata.put(REVISION_METADATA_KEY, "3")
        metadata.put(CLEARED_AT_METADATA_KEY, "10")
        metadata.put(JOURNAL_SEQUENCE_METADATA_KEY, "9")
        doReturn(0L).`when`(snapshots).pendingDeltaCount()

        val first = store.readConfirmedCaptureStamp()
        val minted = checkNotNull(metadata.value(CAPTURE_DATABASE_INSTANCE_KEY))

        assertEquals(UUID.fromString(minted).toString(), minted)
        assertEquals(PlaybackStatsCaptureStamp(PlaybackStatsRoomState(3, 10, 10), 9, minted), first)
        assertEquals(first, store.readConfirmedCaptureStamp())
        assertEquals(minted, metadata.value(CAPTURE_DATABASE_INSTANCE_KEY))
    }

    @Test
    fun `capture stamp rejects a database instance that is not a canonical uuid`() = runTest {
        metadata.put(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE)
        doReturn(0L).`when`(snapshots).pendingDeltaCount()

        for (instance in listOf("550E8400-E29B-41D4-A716-446655440000", "not-a-uuid")) {
            metadata.put(CAPTURE_DATABASE_INSTANCE_KEY, instance)
            val failure = expectFailure<IOException> { store.readConfirmedCaptureStamp() }
            assertEquals("Invalid playback capture database instance", failure.message)
        }
    }

    @Test
    fun `capture stamp requires the room primary store without pending journal entries`() = runTest {
        val legacy = expectFailure<IOException> { store.readConfirmedCaptureStamp() }
        assertEquals("Playback capture requires the Room primary store", legacy.message)

        metadata.put(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE)
        doReturn(2L).`when`(snapshots).pendingDeltaCount()
        val pending = expectFailure<IOException> { store.readConfirmedCaptureStamp() }

        assertEquals("Playback capture contains pending journal entries", pending.message)
        assertNull(metadata.value(CAPTURE_DATABASE_INSTANCE_KEY))
    }

    @Test
    fun `removing tracks advances the revision once and refuses to wrap it`() = runTest {
        metadata.put(REVISION_METADATA_KEY, "4")

        store.removeTracks(setOf("a", "b"))
        store.removeTracks(emptySet())

        verify(stats).deleteStats(listOf("a", "b"))
        assertEquals("5", metadata.value(REVISION_METADATA_KEY))

        metadata.put(REVISION_METADATA_KEY, Long.MAX_VALUE.toString())
        room.transactionLog.clear()
        val failure = expectFailure<IllegalStateException> { store.removeTracks(setOf("c")) }

        assertEquals("Playback statistics revision exhausted", failure.message)
        assertEquals(listOf("begin", "end"), room.transactionLog)
        assertEquals(Long.MAX_VALUE.toString(), metadata.value(REVISION_METADATA_KEY))
    }

    @Test
    fun `clearing always moves the barrier forward even when the clock runs backwards`() = runTest {
        metadata.put(REVISION_METADATA_KEY, "3")
        metadata.put(CLEARED_AT_METADATA_KEY, "100")

        assertEquals(PlaybackStatsRoomState(4, 500, 500), store.clear(now = 500))
        assertEquals(PlaybackStatsRoomState(5, 501, 501), store.clear(now = 200))

        assertEquals(ROOM_PRIMARY_STATE, metadata.value(CUTOVER_STATE_METADATA_KEY))
        assertEquals(MigrationMetadataEntity(CLEARED_AT_METADATA_KEY, "501", 200), metadata.rows[CLEARED_AT_METADATA_KEY])
        verify(snapshots, org.mockito.Mockito.times(2)).deleteAllPendingDeltas()
        verify(stats, org.mockito.Mockito.times(2)).deleteAllStats()

        metadata.put(CLEARED_AT_METADATA_KEY, Long.MAX_VALUE.toString())
        val failure = expectFailure<IllegalStateException> { store.clear(now = 900) }
        assertEquals("Playback clear clock exhausted", failure.message)
    }

    @Test
    fun `cleared at flow treats missing metadata as never cleared and rejects corrupt values`() = runTest {
        assertEquals(0L, store.clearedAtFlow.first())
        storedClearedAt.value = "77"
        assertEquals(77L, store.clearedAtFlow.first())
        assertEquals(4L, store.revisionFlow.first())

        storedClearedAt.value = "-1"
        val failure = expectFailure<IOException> { store.clearedAtFlow.first() }
        assertEquals("Invalid playback statistics clear metadata", failure.message)
    }

    private companion object {
        const val DAY = 86_400_000L

        fun statEntity(key: String) = PlaybackStatEntity(
            key, 7, "song", "artist", "album", 3, null, 180_000, 1_000, 2, 300, 100,
            null, null, null, null, null, null
        )

        fun bucketEntity(day: Long, key: String) = PlaybackStatBucketEntity(
            day, key, 7, "song", "artist", "album", 3, null, 180_000, 1_000, 2, 300, 100,
            null, null, null, null, null, null
        )

        fun counterEntity(key: String, device: String) =
            PlaybackStatCounterShardEntity(key, device, 10, 500, 1, 100, 300)

        fun dailyEntity(day: Long, key: String, device: String) =
            PlaybackStatDailyCounterShardEntity(day, key, device, 10, 500, 1, 100, 300)
    }
}
