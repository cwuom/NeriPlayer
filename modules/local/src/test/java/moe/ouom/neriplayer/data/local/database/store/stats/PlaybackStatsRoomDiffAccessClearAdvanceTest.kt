package moe.ouom.neriplayer.data.local.database.store.stats

import android.content.Context
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDeletedBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDeletedTrackEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toSnapshotData
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.CLEARED_AT_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.CUTOVER_STATE_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.ROOM_PRIMARY_STATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class PlaybackStatsRoomDiffAccessClearAdvanceTest {
    private val harness = PlaybackStatsSnapshotHarness()
    private val staged = harness.staged
    private val context = mock(Context::class.java)

    @Before
    fun setUp() {
        harness.room.metadata.put(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE)
        harness.room.metadata.put(CLEARED_AT_METADATA_KEY, "100")
    }

    @Test
    fun `a newer clear drops stale device rows and lifts tracks to the remote day totals`() = runTest {
        staged.upsertSnapshot(stagedSnapshot(clearedAt = 400, sealed = false, isDiff = true))
        PrimaryPlaybackTables(
            tracks = listOf(deviceRow("l", 900, 3, first = 450, last = 600), deviceRow("o", 300, 1, first = 50, last = 90))
        ).serve(harness.primary)
        val buckets = listOf(
            remoteBucket(DAY_MS, "r", 300, 1, first = 0, last = 500),
            remoteBucket(2 * DAY_MS, "r", 400, 2, first = 450, last = 500),
            remoteBucket(3 * DAY_MS, "r", 100, 1, first = 0, last = 300),
            remoteBucket(4 * DAY_MS, "r", 50, 1, first = 420, last = 800),
            remoteBucket(DAY_MS, "s", 60, 1, first = 500, last = 510)
        )
        val source = PagedPlaybackSource.ordered(listOf(remoteTrack("r", 100, 1, first = 420, last = 800)), buckets, pageSize = 256)

        PlaybackStatsRoomDiffAccess(harness.store).build(SNAPSHOT_ID, source, context)

        assertEquals(
            listOf(remoteRow("r", 850, 5, first = 420, last = 800), remoteRow("s", 60, 1, first = 500, last = 510)).map { it.toSnapshotData() },
            staged.storedTracks(SNAPSHOT_ID)
        )
        assertEquals(setOf(PlaybackStatsSnapshotDeletedTrackEntity(SNAPSHOT_ID, "o")), staged.deletedTracks)
        assertEquals(
            listOf(
                remoteBucketRow(DAY_MS, "r", 300, 1, first = 0, last = 500),
                remoteBucketRow(DAY_MS, "s", 60, 1, first = 500, last = 510),
                remoteBucketRow(2 * DAY_MS, "r", 400, 2, first = 450, last = 500),
                remoteBucketRow(3 * DAY_MS, "r", 100, 1, first = 0, last = 300),
                remoteBucketRow(4 * DAY_MS, "r", 50, 1, first = 420, last = 800)
            ).map { it.toSnapshotData() },
            staged.storedBuckets(SNAPSHOT_ID)
        )
        assertTrue(staged.deletedBuckets.isEmpty())
        assertEquals(stagedSnapshot(clearedAt = 400, sealed = true, isDiff = true), staged.getSnapshot(SNAPSHOT_ID))
        assertEquals(listOf("tracks", "buckets:DAY_IDENTITY", "buckets:IDENTITY_DAY"), source.closedCursors)
    }

    @Test
    fun `a newer clear with nothing remote only tombstones the stale device rows`() = runTest {
        staged.upsertSnapshot(stagedSnapshot(clearedAt = 400, sealed = false, isDiff = true))
        PrimaryPlaybackTables(
            tracks = listOf(deviceRow("o", 300, 1, first = 50, last = 90)),
            buckets = listOf(deviceBucketRow(DAY_MS, "o", 300, 1, first = 50, last = 90))
        ).serve(harness.primary)
        val source = PagedPlaybackSource.ordered(emptyList(), emptyList(), pageSize = 256)

        PlaybackStatsRoomDiffAccess(harness.store).build(SNAPSHOT_ID, source, context)

        assertTrue(staged.storedTracks(SNAPSHOT_ID).isEmpty() && staged.storedBuckets(SNAPSHOT_ID).isEmpty())
        assertEquals(setOf(PlaybackStatsSnapshotDeletedTrackEntity(SNAPSHOT_ID, "o")), staged.deletedTracks)
        assertEquals(setOf(PlaybackStatsSnapshotDeletedBucketEntity(SNAPSHOT_ID, DAY_MS, "o")), staged.deletedBuckets)
        assertEquals(stagedSnapshot(clearedAt = 400, sealed = true, isDiff = true), staged.getSnapshot(SNAPSHOT_ID))
    }
}
