package moe.ouom.neriplayer.data.local.database.store.stats

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsDao
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsSnapshotDao
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsEventReceiptEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsPendingDeltaEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotEntity
import moe.ouom.neriplayer.data.local.database.store.InlineTransactionDatabase
import moe.ouom.neriplayer.data.local.database.store.expectFailure
import moe.ouom.neriplayer.data.local.database.store.invokedMethods
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.CLEARED_AT_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.COUNTER_EPOCH_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.CUTOVER_STATE_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.JOURNAL_SEQUENCE_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.LEGACY_JSON_STATE
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.REVISION_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.ROOM_PRIMARY_STATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`

class PlaybackStatsRoomStoreJournalTest {
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
        metadata.put(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE)
        metadata.put(REVISION_METADATA_KEY, "8")
        metadata.put(CLEARED_AT_METADATA_KEY, "100")
        metadata.put(COUNTER_EPOCH_METADATA_KEY, "100")
        runBlocking {
            doReturn(0L).`when`(snapshots).pendingDeltaCount()
            doReturn(false).`when`(snapshots).hasDiffRows(anyString())
        }
    }

    @Test
    fun `journal records every new event but only queues those after the clear`() = runTest {
        assertTrue(store.enqueueDelta("fresh", "{}", 500, 1, playedAt = 150, deviceId = "phone"))
        assertTrue(store.enqueueDelta("stale", "{}", 300, null, playedAt = 50, deviceId = "phone"))
        assertTrue(store.enqueueDelta("observed", "{}", 200, 1, playedAt = 50, deviceId = "tablet", observedClearedAt = 120))

        assertEquals(listOf("fresh", "stale", "observed"), arguments<PlaybackStatsEventReceiptEntity>("insertReceipt").map { it.id })
        assertEquals(
            listOf(
                PlaybackStatsPendingDeltaEntity("fresh", 1, "{}", 500, 1, 150, 100, "phone"),
                PlaybackStatsPendingDeltaEntity("observed", 3, "{}", 200, 1, 50, 120, "tablet")
            ),
            arguments<PlaybackStatsPendingDeltaEntity>("insertPendingDelta")
        )
        assertEquals("3", metadata.value(JOURNAL_SEQUENCE_METADATA_KEY))
        assertEquals(3, invokedMethods(snapshots).count { it == "pruneCompletedReceipts" })
    }

    @Test
    fun `replayed events are skipped and reused identities with new content are rejected`() = runTest {
        store.enqueueDelta("event", "{}", 500, 1, playedAt = 150, deviceId = "phone")
        val receipt = arguments<PlaybackStatsEventReceiptEntity>("insertReceipt").single()
        doReturn(receipt).`when`(snapshots).receipt("event")

        assertFalse(store.enqueueDelta("event", "{}", 500, 1, playedAt = 150, deviceId = "phone"))
        val reused = expectFailure<IOException> { store.enqueueDelta("event", "{}", 999, 1, playedAt = 150, deviceId = "phone") }

        assertEquals("Playback event identity reused with different content", reused.message)
        assertEquals("1", metadata.value(JOURNAL_SEQUENCE_METADATA_KEY))
    }

    @Test
    fun `journal refuses invalid clocks, an exhausted sequence and an unavailable store`() = runTest {
        expectFailure<IllegalArgumentException> {
            store.enqueueDelta("negative", "{}", 1, 1, playedAt = 150, deviceId = "phone", observedClearedAt = -1)
        }
        metadata.put(JOURNAL_SEQUENCE_METADATA_KEY, Long.MAX_VALUE.toString())
        val exhausted = expectFailure<IllegalStateException> { store.enqueueDelta("full", "{}", 1, 1, 150, "phone") }
        metadata.put(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE)
        val unavailable = expectFailure<IllegalStateException> { store.enqueueDelta("legacy", "{}", 1, 1, 150, "phone") }

        assertEquals("Playback journal sequence exhausted", exhausted.message)
        assertEquals("Playback statistics are unavailable", unavailable.message)
        assertEquals(emptyList<PlaybackStatsPendingDeltaEntity>(), arguments<PlaybackStatsPendingDeltaEntity>("insertPendingDelta"))
    }

    @Test
    fun `observed clear generations decide admission before the play time`() {
        assertEquals(PlaybackDeltaAdmission(true, 100), admitPlaybackDelta(100, playedAt = 150, observedClearedAt = null, observeCurrentClear = false))
        assertEquals(PlaybackDeltaAdmission(false, 100), admitPlaybackDelta(100, playedAt = 50, observedClearedAt = null, observeCurrentClear = false))
        assertEquals(PlaybackDeltaAdmission(true, 100), admitPlaybackDelta(100, playedAt = 50, observedClearedAt = null, observeCurrentClear = true))
        assertEquals(PlaybackDeltaAdmission(false, 100), admitPlaybackDelta(100, playedAt = 150, observedClearedAt = 90, observeCurrentClear = true))
        assertEquals(PlaybackDeltaAdmission(true, 120), admitPlaybackDelta(100, playedAt = 50, observedClearedAt = 120, observeCurrentClear = false))
    }

    @Test
    fun `diff commit publishes staged rows only while the revision is current`() = runTest {
        doReturn(diff("rows")).`when`(snapshots).getSnapshot("rows")
        doReturn(true).`when`(snapshots).hasDiffRows("rows")

        assertFalse(store.commitDiffSnapshot("rows", expectedRevision = 7))
        doReturn(1L).`when`(snapshots).pendingDeltaCount()
        assertFalse(store.commitDiffSnapshot("rows", expectedRevision = 8))
        assertFalse("applyBucketTombstones" in invokedMethods(snapshots))

        doReturn(0L).`when`(snapshots).pendingDeltaCount()
        assertTrue(store.commitDiffSnapshot("rows", expectedRevision = 8))

        assertEquals(
            listOf("applyBucketTombstones", "applyTrackTombstones", "deleteReplacedDailyCounters", "deleteReplacedCounters",
                "updateDiffTracks", "insertDiffTracks", "updateDiffBuckets", "insertDiffBuckets", "publishCounter", "publishDailyCounter"),
            invokedMethods(snapshots).dropWhile { it != "applyBucketTombstones" }
        )
        assertEquals("9", metadata.value(REVISION_METADATA_KEY))
    }

    @Test
    fun `empty diff at the current barrier commits without touching the primary rows`() = runTest {
        doReturn(diff("empty")).`when`(snapshots).getSnapshot("empty")
        doReturn(diff("raised", clearedAt = 140, epoch = 160)).`when`(snapshots).getSnapshot("raised")

        assertTrue(store.commitDiffSnapshot("empty", expectedRevision = 8))
        assertEquals("8", metadata.value(REVISION_METADATA_KEY))
        assertFalse("publishCounter" in invokedMethods(snapshots))

        assertTrue(store.commitDiffSnapshot("raised", expectedRevision = 8))
        assertEquals(listOf("9", "140", "160"), listOf(REVISION_METADATA_KEY, CLEARED_AT_METADATA_KEY, COUNTER_EPOCH_METADATA_KEY).map(metadata::value))
    }

    @Test
    fun `only an existing sealed diff can be committed to an available store`() = runTest {
        doReturn(diff("open").copy(sealed = false)).`when`(snapshots).getSnapshot("open")
        doReturn(diff("full").copy(isDiff = false)).`when`(snapshots).getSnapshot("full")
        doReturn(diff("ready")).`when`(snapshots).getSnapshot("ready")

        val missing = expectFailure<IllegalStateException> { store.commitDiffSnapshot("gone", 8) }
        val open = expectFailure<IllegalStateException> { store.commitDiffSnapshot("open", 8) }
        val full = expectFailure<IllegalStateException> { store.commitDiffSnapshot("full", 8) }
        metadata.put(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE)
        val unavailable = expectFailure<IllegalStateException> { store.commitDiffSnapshot("ready", 8) }

        assertEquals("Playback diff no longer exists", missing.message)
        assertEquals(listOf("Playback diff is incomplete", "Playback diff is incomplete"), listOf(open.message, full.message))
        assertEquals("Playback statistics are unavailable", unavailable.message)
    }

    private fun diff(id: String, clearedAt: Long = 100, epoch: Long = 100) =
        PlaybackStatsSnapshotEntity(id, 8, clearedAt, epoch, 1, sealed = true, ownerProcessId = "owner", isDiff = true)

    private inline fun <reified T> arguments(method: String): List<T> =
        mockingDetails(snapshots).invocations.filter { it.method.name == method }.map { it.arguments.first() as T }
}
