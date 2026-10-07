package moe.ouom.neriplayer.data.local.database.store.stats

import android.content.Context
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatProjection
import moe.ouom.neriplayer.data.sync.host.AndroidSyncSanitizationHost
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSink
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class PlaybackStatsRoomSnapshotAccessExportTest {
    private val harness = PlaybackStatsSnapshotHarness()
    private val staged = harness.staged
    private val access = PlaybackStatsRoomSnapshotAccess(harness.store)
    private val context = mock(Context::class.java)

    @Test
    fun `export streams syncable staged rows with their counters and skips device files`() = runTest {
        staged.stage(
            tracks = listOf(
                stagedTrack("a", 1_000, 2, first = 100, last = 500),
                stagedTrack("b", 800, 1, first = 10, last = 20, localFilePath = "/storage/emulated/0/Music/b.flac"),
                stagedTrack("c", 400, 1, first = 600, last = 700)
            ),
            buckets = listOf(
                stagedBucket(DAY_MS, "a", 1_000, 2, first = 100, last = 500),
                stagedBucket(DAY_MS, "b", 800, 1, first = 10, last = 20, localFilePath = "/storage/emulated/0/Music/b.flac"),
                stagedBucket(2 * DAY_MS, "c", 400, 1, first = 600, last = 700)
            ),
            counters = listOf(
                stagedCounter("a", "phone", 0, 600, 1, first = 100, last = 300),
                stagedCounter("b", "phone", 0, 800, 1, first = 10, last = 20),
                stagedCounter("c", "tablet", 50, 400, 1, first = 600, last = 700)
            ),
            dailyCounters = listOf(stagedDailyCounter(2 * DAY_MS, "c", "tablet", 50, 400, 1, first = 600, last = 700))
        )
        val trackPages = mutableListOf<List<SyncTrackStat>>()
        val bucketPages = mutableListOf<List<SyncPlaybackStatBucket>>()

        access.export(SNAPSHOT_ID, context, { trackPages.add(it) }, { bucketPages.add(it) })

        val phone = SyncPlaybackCounterShard("phone", 0, totalListenMs = 600, playCount = 1, firstPlayedAt = 100, lastPlayedAt = 300)
        val tablet = SyncPlaybackCounterShard("tablet", 50, totalListenMs = 400, playCount = 1, firstPlayedAt = 600, lastPlayedAt = 700)
        assertEquals(
            listOf(listOf(exportedTrack("a", 1_000, 2, first = 100, last = 500, base = 400L to 1, shards = listOf(phone)),
                exportedTrack("c", 400, 1, first = 600, last = 700, base = 0L to 0, shards = listOf(tablet)))),
            trackPages
        )
        assertEquals(
            listOf(listOf(exportedBucket(DAY_MS, "a", 1_000, 2, first = 100, last = 500, base = 1_000L to 2, shards = emptyList()),
                exportedBucket(2 * DAY_MS, "c", 400, 1, first = 600, last = 700, base = 0L to 0, shards = listOf(tablet)))),
            bucketPages
        )
    }

    @Test
    fun `sink export binds the default projection while a supplied one can mark custom local albums`() = runTest {
        staged.stage(
            tracks = listOf(stagedTrack("m", 300, 1, first = 1, last = 2, album = "Device Rips", albumId = 0, mediaUri = null)),
            buckets = listOf(stagedBucket(DAY_MS, "m", 300, 1, first = 1, last = 2, album = "Device Rips", albumId = 0, mediaUri = null))
        )
        val sink = RecordingSink()

        access.export(SNAPSHOT_ID, sink, context)

        assertEquals(listOf(listOf("m")), sink.tracks.map { page -> page.map { it.identityKey } })
        assertEquals(listOf(listOf(DAY_MS to "m")), sink.buckets.map { page -> page.map { it.dayStartAt to it.identityKey } })
        val tracks = mutableListOf<List<SyncTrackStat>>()
        val buckets = mutableListOf<List<SyncPlaybackStatBucket>>()
        val localAlbums = SyncPlaybackStatProjection(AndroidSyncSanitizationHost(context) { setOf("device rips") })

        access.export(SNAPSHOT_ID, context, { tracks.add(it) }, { buckets.add(it) }, localAlbums)

        assertEquals(listOf(emptyList<SyncTrackStat>()), tracks)
        assertEquals(listOf(emptyList<SyncPlaybackStatBucket>()), buckets)
    }

    @Test
    fun `lifting raises staged tracks to the sum of their days and creates tracks for bucket only songs`() = runTest {
        val a = stagedTrack("a", 1_000, 1, first = 100, last = 500)
        val b = stagedTrack("b", 5_000, 9, first = 10, last = 20)
        staged.stage(
            tracks = listOf(a, b),
            buckets = listOf(
                stagedBucket(DAY_MS, "a", 800, 1, first = 100, last = 100),
                stagedBucket(2 * DAY_MS, "a", 700, 2, first = 400, last = 500),
                stagedBucket(DAY_MS, "b", 100, 1, first = 10, last = 20),
                stagedBucket(DAY_MS, "c", 10, 1, first = 0, last = 300, name = "c first day"),
                stagedBucket(2 * DAY_MS, "c", 20, 1, first = 250, last = 300, name = "c latest day"),
                stagedBucket(3 * DAY_MS, "c", 30, 2, first = 0, last = 200, name = "c unplayed start"),
                stagedBucket(4 * DAY_MS, "c", 40, 1, first = 220, last = 280, name = "c earliest play")
            )
        )

        access.liftBucketTotals(SNAPSHOT_ID)

        val created = stagedTrack("c", 100, 5, first = 220, last = 300).copy(name = "c latest day")
        assertEquals(listOf(a.copy(totalListenMs = 1_500, playCount = 3), b, created), staged.storedTracks(SNAPSHOT_ID))

        access.liftBucketTotals("empty")

        assertTrue(staged.storedTracks("empty").isEmpty())
    }

    private fun exportedTrack(key: String, listen: Long, plays: Int, first: Long, last: Long, base: Pair<Long, Int>,
        shards: List<SyncPlaybackCounterShard>) = SyncTrackStat(
        identityKey = key, name = "Stored $key", artist = "artist", album = "album", totalListenMs = listen, playCount = plays,
        lastPlayedAt = last, firstPlayedAt = first, coverUrl = "https://img.example/$key.jpg", durationMs = 180_000,
        mediaUri = "https://music.example/$key", id = 7, albumId = 5, counterBaseListenMs = base.first,
        counterBasePlayCount = base.second, counterShards = shards
    )

    private fun exportedBucket(day: Long, key: String, listen: Long, plays: Int, first: Long, last: Long, base: Pair<Long, Int>,
        shards: List<SyncPlaybackCounterShard>) = SyncPlaybackStatBucket(
        dayStartAt = day, identityKey = key, name = "Stored $key", artist = "artist", album = "album", totalListenMs = listen,
        playCount = plays, lastPlayedAt = last, firstPlayedAt = first, coverUrl = "https://img.example/$key.jpg",
        durationMs = 180_000, mediaUri = "https://music.example/$key", id = 7, albumId = 5, counterBaseListenMs = base.first,
        counterBasePlayCount = base.second, counterShards = shards
    )

    private class RecordingSink : SyncPlaybackSink {
        val tracks = mutableListOf<List<SyncTrackStat>>()
        val buckets = mutableListOf<List<SyncPlaybackStatBucket>>()
        override suspend fun appendTracks(page: List<SyncTrackStat>) { tracks.add(page) }
        override suspend fun appendBuckets(page: List<SyncPlaybackStatBucket>) { buckets.add(page) }
        override suspend fun seal(): SyncPlaybackSource = throw UnsupportedOperationException("Export never seals its sink")
        override fun close() = Unit
    }
}
