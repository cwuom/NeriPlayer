package moe.ouom.neriplayer.data.local.database.store.stats

import android.content.Context
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotBucketData
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotTrackData
import moe.ouom.neriplayer.data.local.database.store.expectFailure
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackBucketOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class PlaybackStatsRoomSnapshotAccessLegacyMergeTest {
    private val harness = PlaybackStatsSnapshotHarness()
    private val staged = harness.staged
    private val access = PlaybackStatsRoomSnapshotAccess(harness.store)
    private val context = mock(Context::class.java)

    @Test
    fun `legacy merge folds remote rows into staged ones and lifts tracks to their bucket totals`() = runTest {
        val storedTrack = stagedTrack("a", listen = 1_000, plays = 2, first = 100, last = 500)
        val storedBucket = stagedBucket(DAY_MS, "a", listen = 600, plays = 1, first = 100, last = 500)
        staged.upsertSnapshot(stagedSnapshot(sealed = false))
        staged.stage(tracks = listOf(storedTrack), buckets = listOf(storedBucket))

        access.mergeLegacyBackup(
            SNAPSHOT_ID,
            tracks = listOf(remoteTrack("a", 3_000, 1, first = 300, last = 800), remoteTrack("b", 700, 1, first = 50, last = 60)),
            buckets = listOf(
                remoteBucket(DAY_MS, "a", 900, 2, first = 300, last = 800),
                remoteBucket(2 * DAY_MS, "b", 700, 1, first = 50, last = 60),
                remoteBucket(3 * DAY_MS, "b", 400, 1, first = 70, last = 90),
                remoteBucket(DAY_MS, "c", 250, 1, first = 40, last = 45)
            ),
            clearedAt = 0,
            respectLocalClear = true
        )

        assertEquals(
            listOf(
                written("a", 3_000, 2, first = 100, last = 800, local = storedTrack),
                written("b", 1_100, 2, first = 50, last = 60),
                written("c", 250, 1, first = 40, last = 45)
            ),
            staged.storedTracks(SNAPSHOT_ID)
        )
        assertEquals(
            listOf(
                writtenBucket(DAY_MS, "a", 900, 2, first = 100, last = 800, local = storedBucket),
                writtenBucket(DAY_MS, "c", 250, 1, first = 40, last = 45),
                writtenBucket(2 * DAY_MS, "b", 700, 1, first = 50, last = 60),
                writtenBucket(3 * DAY_MS, "b", 400, 1, first = 70, last = 90)
            ),
            staged.storedBuckets(SNAPSHOT_ID)
        )
        assertEquals(stagedSnapshot(sealed = true), staged.getSnapshot(SNAPSHOT_ID))
        assertEquals(List(2) { listOf("begin", "commit", "end") }.flatten(), harness.room.transactionLog)
    }

    @Test
    fun `merged counter shards replace the staged shards of every written row`() = runTest {
        val phone = stagedCounter("a", "phone", 0, listen = 1_000, plays = 2, first = 100, last = 500)
        val tablet = SyncPlaybackCounterShard("tablet", 0, totalListenMs = 400, playCount = 1, firstPlayedAt = 600, lastPlayedAt = 700)
        staged.upsertSnapshot(stagedSnapshot())
        staged.stage(
            tracks = listOf(stagedTrack("a", 1_000, 2, first = 100, last = 500)),
            buckets = listOf(stagedBucket(DAY_MS, "a", 1_000, 2, first = 100, last = 500)),
            counters = listOf(phone),
            dailyCounters = listOf(stagedDailyCounter(DAY_MS, "a", "phone", 0, 1_000, 2, first = 100, last = 500))
        )

        access.mergeLegacyBackup(
            SNAPSHOT_ID,
            tracks = listOf(remoteTrack("a", 400, 1, first = 600, last = 700, shards = listOf(tablet))),
            buckets = listOf(remoteBucket(DAY_MS, "a", 400, 1, first = 600, last = 700, shards = listOf(tablet))),
            clearedAt = 0,
            respectLocalClear = true
        )

        val track = staged.storedTracks(SNAPSHOT_ID).single()
        assertEquals(listOf(1_400L, 3L, 100L, 700L), listOf(track.totalListenMs, track.playCount.toLong(), track.firstPlayedAt, track.lastPlayedAt))
        assertEquals(listOf(phone, stagedCounter("a", "tablet", 0, 400, 1, first = 600, last = 700)), staged.storedCounters(SNAPSHOT_ID))
        val bucket = staged.storedBuckets(SNAPSHOT_ID).single()
        assertEquals(listOf(1_400L, 3L), listOf(bucket.totalListenMs, bucket.playCount.toLong()))
        assertEquals(
            listOf(
                stagedDailyCounter(DAY_MS, "a", "phone", 0, 1_000, 2, first = 100, last = 500),
                stagedDailyCounter(DAY_MS, "a", "tablet", 0, 400, 1, first = 600, last = 700)
            ),
            staged.storedDailyCounters(SNAPSHOT_ID)
        )
    }

    @Test
    fun `paged legacy merge sanitizes each page and skips pages that sanitize to nothing`() = runTest {
        staged.upsertSnapshot(stagedSnapshot(sealed = false))
        val source = PagedPlaybackSource(
            trackPages = listOf(
                listOf(
                    remoteTrack("a", 300, 1, first = 900, last = 800),
                    remoteTrack(" ", 10, 1, first = 1, last = 2),
                    remoteTrack("x", 50, 1, first = 1, last = 2, mediaUri = "content://media/external/audio/media/1")
                ),
                listOf(remoteTrack("y", 70, 1, first = 1, last = 2, mediaUri = "file:///sdcard/Music/y.mp3"))
            ),
            bucketPages = mapOf(
                SyncPlaybackBucketOrder.DAY_IDENTITY to listOf(
                    listOf(remoteBucket(DAY_MS, "a", 300, 1, first = 900, last = 800)),
                    listOf(remoteBucket(DAY_MS, "y", 70, 1, first = 1, last = 2, mediaUri = "content://media/external/audio/media/2"))
                )
            )
        )

        access.mergeLegacyBackup(SNAPSHOT_ID, source, clearedAt = 0, respectLocalClear = false, context = context)

        assertEquals(listOf(written("a", 300, 1, first = 800, last = 800)), staged.storedTracks(SNAPSHOT_ID))
        assertEquals(listOf(writtenBucket(DAY_MS, "a", 300, 1, first = 800, last = 800)), staged.storedBuckets(SNAPSHOT_ID))
        assertEquals(listOf("tracks", "buckets:DAY_IDENTITY"), source.closedCursors)
        assertEquals(stagedSnapshot(sealed = true), staged.getSnapshot(SNAPSHOT_ID))
    }

    @Test
    fun `paged legacy merge rejects pages over the record budget and still closes the cursor`() = runTest {
        staged.upsertSnapshot(stagedSnapshot())
        val tracks = PagedPlaybackSource(listOf(List(SYNC_PLAYBACK_PAGE_RECORDS + 1) { remoteTrack("t$it", 1, 1, first = 1, last = 2) }))

        val trackFailure = expectFailure<IllegalArgumentException> {
            access.mergeLegacyBackup(SNAPSHOT_ID, tracks, clearedAt = 0, respectLocalClear = false, context = context)
        }

        assertEquals("Playback backup page exceeds record budget", trackFailure.message)
        assertEquals(listOf("tracks"), tracks.closedCursors)
        val buckets = PagedPlaybackSource(
            trackPages = emptyList(),
            bucketPages = mapOf(
                SyncPlaybackBucketOrder.DAY_IDENTITY to
                    listOf(List(SYNC_PLAYBACK_PAGE_RECORDS + 1) { remoteBucket(DAY_MS, "b$it", 1, 1, first = 1, last = 2) })
            )
        )

        val bucketFailure = expectFailure<IllegalArgumentException> {
            access.mergeLegacyBackup(SNAPSHOT_ID, buckets, clearedAt = 0, respectLocalClear = false, context = context)
        }

        assertEquals("Playback backup page exceeds record budget", bucketFailure.message)
        assertEquals(listOf("tracks", "buckets:DAY_IDENTITY"), buckets.closedCursors)
        assertTrue(staged.storedTracks(SNAPSHOT_ID).isEmpty() && staged.storedBuckets(SNAPSHOT_ID).isEmpty())
        assertFalse(checkNotNull(staged.getSnapshot(SNAPSHOT_ID)).sealed)
    }

    @Test
    fun `advancing the clear barrier drops staged rows last played before it`() = runTest {
        val kept = stagedTrack("kept", 900, 3, first = 450, last = 600)
        val keptBucket = stagedBucket(DAY_MS, "kept", 900, 3, first = 450, last = 600)
        staged.upsertSnapshot(stagedSnapshot(clearedAt = 100, epoch = 120))
        staged.stage(
            tracks = listOf(kept, stagedTrack("old", 300, 1, first = 50, last = 90)),
            buckets = listOf(keptBucket, stagedBucket(DAY_MS, "old", 300, 1, first = 50, last = 90)),
            counters = listOf(stagedCounter("old", "phone", 0, 300, 1, first = 50, last = 90)),
            dailyCounters = listOf(stagedDailyCounter(DAY_MS, "old", "phone", 0, 300, 1, first = 50, last = 90))
        )

        access.mergeLegacyBackup(SNAPSHOT_ID, emptyList(), emptyList(), clearedAt = 400, respectLocalClear = true)

        assertEquals(listOf(kept), staged.storedTracks(SNAPSHOT_ID))
        assertEquals(listOf(keptBucket), staged.storedBuckets(SNAPSHOT_ID))
        assertTrue(staged.storedCounters(SNAPSHOT_ID).isEmpty())
        assertTrue(staged.storedDailyCounters(SNAPSHOT_ID).isEmpty())
        assertEquals(stagedSnapshot(clearedAt = 400, epoch = 400), staged.getSnapshot(SNAPSHOT_ID))
        assertEquals(List(4) { listOf("begin", "commit", "end") }.flatten(), harness.room.transactionLog)
    }

    @Test
    fun `an older or ignored remote clear keeps every staged row`() = runTest {
        val old = stagedTrack("old", 300, 1, first = 50, last = 90)
        staged.upsertSnapshot(stagedSnapshot(clearedAt = 100, epoch = 120))
        staged.stage(tracks = listOf(old))

        access.mergeLegacyBackup(SNAPSHOT_ID, emptyList(), emptyList(), clearedAt = 50, respectLocalClear = true)

        assertEquals(stagedSnapshot(clearedAt = 100, epoch = 120), staged.getSnapshot(SNAPSHOT_ID))

        access.mergeLegacyBackup(SNAPSHOT_ID, emptyList(), emptyList(), clearedAt = -5, respectLocalClear = false)

        assertEquals(stagedSnapshot(clearedAt = 0, epoch = 120), staged.getSnapshot(SNAPSHOT_ID))
        assertEquals(listOf(old), staged.storedTracks(SNAPSHOT_ID))
    }

    @Test
    fun `legacy merge into a released snapshot fails before writing`() = runTest {
        expectFailure<IllegalStateException> {
            access.mergeLegacyBackup("released", listOf(remoteTrack("a", 1, 1, first = 1, last = 2)), emptyList(), 0, true)
        }

        assertTrue(staged.storedTracks("released").isEmpty())
        assertTrue(harness.room.transactionLog.isEmpty())
    }

    private fun written(key: String, listen: Long, plays: Int, first: Long, last: Long, local: PlaybackStatsSnapshotTrackData? = null) =
        PlaybackStatsSnapshotTrackData(
            identityKey = key, id = 9, name = "Remote $key", artist = "artist", album = "album", albumId = 5,
            coverUrl = "https://img.example/remote-$key.jpg", durationMs = 200_000, totalListenMs = listen, playCount = plays,
            lastPlayedAt = last, firstPlayedAt = first, mediaUri = "https://music.example/$key",
            localFilePath = local?.localFilePath, localFileName = local?.localFileName, customName = local?.customName,
            customArtist = local?.customArtist, customCoverUrl = local?.customCoverUrl
        )

    private fun writtenBucket(day: Long, key: String, listen: Long, plays: Int, first: Long, last: Long, local: PlaybackStatsSnapshotBucketData? = null) =
        PlaybackStatsSnapshotBucketData(
            dayStartAt = day, identityKey = key, id = 9, name = "Remote $key", artist = "artist", album = "album", albumId = 5,
            coverUrl = "https://img.example/remote-$key.jpg", durationMs = 200_000, totalListenMs = listen, playCount = plays,
            lastPlayedAt = last, firstPlayedAt = first, mediaUri = "https://music.example/$key",
            localFilePath = local?.localFilePath, localFileName = local?.localFileName, customName = local?.customName,
            customArtist = local?.customArtist, customCoverUrl = local?.customCoverUrl
        )
}
