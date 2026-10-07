package moe.ouom.neriplayer.data.local.database.store.stats

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatDailyCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDeletedBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDeletedTrackEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toSnapshotData
import moe.ouom.neriplayer.data.local.database.store.expectFailure
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.CLEARED_AT_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.CUTOVER_STATE_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.LEGACY_JSON_STATE
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.ROOM_PRIMARY_STATE
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class PlaybackStatsRoomDiffAccessStagingTest {
    private val harness = PlaybackStatsSnapshotHarness()
    private val staged = harness.staged
    private val context = mock(Context::class.java)

    @Test
    fun `diff stages remote changes and deletions while leaving equal and device only rows alone`() = runTest {
        primaryState(clearedAt = 100)
        staged.upsertSnapshot(stagedSnapshot(clearedAt = 100, sealed = false, isDiff = true))
        val phoneEarly = SyncPlaybackCounterShard("phone", 10, totalListenMs = 500, playCount = 1, firstPlayedAt = 200, lastPlayedAt = 400)
        val phoneLate = SyncPlaybackCounterShard("phone", 20, totalListenMs = 800, playCount = 1, firstPlayedAt = 700, lastPlayedAt = 900)
        val tablet = SyncPlaybackCounterShard("tablet", 5, totalListenMs = 700, playCount = 1, firstPlayedAt = 150, lastPlayedAt = 300)
        PrimaryPlaybackTables(
            tracks = listOf(
                remoteRow("a", 1_000, 2, first = 100, last = 500, customName = "Custom a"),
                remoteRow("b", 1_500, 2, first = 150, last = 600, customName = "Custom b"),
                remoteRow("d", 100, 1, first = 10, last = 20),
                deviceRow("l", 400, 1, first = 300, last = 350),
                deviceRow("m", 600, 2, first = 150, last = 700),
                remoteRow("x", 10, 1, first = 1, last = 2)
            ),
            buckets = listOf(
                remoteBucketRow(DAY_MS, "a", 1_000, 2, first = 100, last = 500, customName = "Custom a"),
                remoteBucketRow(DAY_MS, "b", 1_500, 2, first = 150, last = 600, customName = "Custom b"),
                remoteBucketRow(DAY_MS, "d", 100, 1, first = 10, last = 20),
                deviceBucketRow(DAY_MS, "l", 400, 1, first = 300, last = 350)
            ),
            counters = listOf(PlaybackStatCounterShardEntity("b", "phone", 10, 500, 1, 200, 400)),
            dailyCounters = listOf(PlaybackStatDailyCounterShardEntity(DAY_MS, "b", "phone", 10, 500, 1, 200, 400))
        ).serve(harness.primary)
        val source = PagedPlaybackSource.ordered(
            tracks = listOf(
                remoteTrack("a", 1_000, 2, first = 100, last = 500),
                remoteTrack("b", 2_000, 3, first = 150, last = 900, shards = listOf(phoneLate, tablet, phoneEarly)),
                remoteTrack("c", 300, 1, first = 50, last = 60),
                remoteTrack("m", 900, 4, first = 200, last = 1_000)
            ),
            buckets = listOf(
                remoteBucket(DAY_MS, "a", 1_000, 2, first = 100, last = 500),
                remoteBucket(DAY_MS, "b", 2_000, 3, first = 150, last = 900, shards = listOf(phoneLate, phoneEarly)),
                remoteBucket(2 * DAY_MS, "c", 300, 1, first = 50, last = 60)
            ),
            pageSize = 2
        )

        PlaybackStatsRoomDiffAccess(harness.store).build(SNAPSHOT_ID, source, context)

        assertEquals(
            listOf(
                remoteRow("b", 2_000, 3, first = 150, last = 900, customName = "Custom b"),
                remoteRow("c", 300, 1, first = 50, last = 60),
                remoteRow("m", 900, 4, first = 150, last = 1_000, customName = "Custom m", localFilePath = "/storage/emulated/0/Music/m.flac")
            ).map { it.toSnapshotData() },
            staged.storedTracks(SNAPSHOT_ID)
        )
        assertEquals(
            listOf(
                PlaybackStatCounterShardEntity("b", "phone", 10, 500, 1, 200, 400),
                PlaybackStatCounterShardEntity("b", "phone", 20, 800, 1, 700, 900),
                PlaybackStatCounterShardEntity("b", "tablet", 5, 700, 1, 150, 300)
            ).map { it.toSnapshotData() },
            staged.storedCounters(SNAPSHOT_ID)
        )
        assertEquals(setOf(PlaybackStatsSnapshotDeletedTrackEntity(SNAPSHOT_ID, "d"), PlaybackStatsSnapshotDeletedTrackEntity(SNAPSHOT_ID, "x")), staged.deletedTracks)
        assertEquals(
            listOf(
                remoteBucketRow(DAY_MS, "b", 2_000, 3, first = 150, last = 900, customName = "Custom b"),
                remoteBucketRow(2 * DAY_MS, "c", 300, 1, first = 50, last = 60)
            ).map { it.toSnapshotData() },
            staged.storedBuckets(SNAPSHOT_ID)
        )
        assertEquals(
            listOf(
                PlaybackStatDailyCounterShardEntity(DAY_MS, "b", "phone", 10, 500, 1, 200, 400),
                PlaybackStatDailyCounterShardEntity(DAY_MS, "b", "phone", 20, 800, 1, 700, 900)
            ).map { it.toSnapshotData() },
            staged.storedDailyCounters(SNAPSHOT_ID)
        )
        assertEquals(setOf(PlaybackStatsSnapshotDeletedBucketEntity(SNAPSHOT_ID, DAY_MS, "d")), staged.deletedBuckets)
        assertEquals(stagedSnapshot(clearedAt = 100, sealed = true, isDiff = true), staged.getSnapshot(SNAPSHOT_ID))
        assertEquals(listOf("tracks", "buckets:DAY_IDENTITY"), source.closedCursors)
    }

    @Test
    fun `diff refuses remote pages that are unordered, repeated or over budget`() = runTest {
        primaryState(clearedAt = 0)
        PrimaryPlaybackTables().serve(harness.primary)
        val unordered = listOf(listOf(remoteTrack("b", 1, 1, first = 1, last = 2), remoteTrack("a", 1, 1, first = 1, last = 2)))
        val repeated = listOf(listOf(remoteTrack("a", 1, 1, first = 1, last = 2)), listOf(remoteTrack("a", 2, 1, first = 1, last = 2)))

        for (pages in listOf(unordered, repeated)) {
            staged.upsertSnapshot(stagedSnapshot(sealed = false, isDiff = true))
            val failure = expectFailure<IOException> {
                PlaybackStatsRoomDiffAccess(harness.store).build(SNAPSHOT_ID, PagedPlaybackSource(pages), context)
            }
            assertEquals("Playback source is not strictly ordered and unique", failure.message)
        }
        val oversized = PagedPlaybackSource(listOf(List(SYNC_PLAYBACK_PAGE_RECORDS + 1) { remoteTrack("t%03d".format(it), 1, 1, first = 1, last = 2) }))

        val budget = expectFailure<IllegalArgumentException> { PlaybackStatsRoomDiffAccess(harness.store).build(SNAPSHOT_ID, oversized, context) }

        assertEquals("Playback page exceeds its record budget", budget.message)
        assertEquals(listOf("tracks"), oversized.closedCursors)
        assertFalse(checkNotNull(staged.getSnapshot(SNAPSHOT_ID)).sealed)
        assertTrue(staged.storedTracks(SNAPSHOT_ID).isEmpty())
    }

    @Test
    fun `diff only builds into an open diff snapshot over the room primary store`() = runTest {
        val source = PagedPlaybackSource(emptyList())
        val diff = PlaybackStatsRoomDiffAccess(harness.store)
        primaryState(clearedAt = 0)

        expectFailure<IllegalStateException> { diff.build(SNAPSHOT_ID, source, context) }
        for (header in listOf(stagedSnapshot(sealed = false, isDiff = false), stagedSnapshot(sealed = true, isDiff = true))) {
            staged.upsertSnapshot(header)
            val failure = expectFailure<IllegalStateException> { diff.build(SNAPSHOT_ID, source, context) }
            assertEquals("Playback diff must begin empty and unsealed", failure.message)
        }
        staged.upsertSnapshot(stagedSnapshot(sealed = false, isDiff = true))
        harness.room.metadata.put(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE)
        expectFailure<IllegalStateException> { diff.build(SNAPSHOT_ID, source, context) }

        assertTrue(source.closedCursors.isEmpty())
        assertFalse(checkNotNull(staged.getSnapshot(SNAPSHOT_ID)).sealed)
    }

    private fun primaryState(clearedAt: Long) {
        harness.room.metadata.put(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE)
        harness.room.metadata.put(CLEARED_AT_METADATA_KEY, clearedAt.toString())
    }
}
