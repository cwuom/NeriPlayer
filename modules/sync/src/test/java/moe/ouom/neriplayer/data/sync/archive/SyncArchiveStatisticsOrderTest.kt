package moe.ouom.neriplayer.data.sync.archive

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackStatsMergePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class SyncArchiveStatisticsOrderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `finalized shuffled device statistics reuse blocks after one playback delta`() = runBlocking {
        val stats = (0 until 100_000).map { index ->
            SyncTrackStat(
                id = index + 1L,
                identityKey = "netease:${index.toString().padStart(6, '0')}",
                name = "歌曲-$index", artist = "artist-${index % 500}", album = "library",
                totalListenMs = 600_000, playCount = 3,
                firstPlayedAt = 1_700_000_000_000, lastPlayedAt = 1_700_000_600_000
            )
        }
        val buckets = stats.mapIndexed { index, stat ->
            SyncPlaybackStatBucket(
                id = stat.id, identityKey = stat.identityKey,
                dayStartAt = (19_000L + index % 365) * 86_400_000L,
                name = stat.name, artist = stat.artist, album = stat.album,
                totalListenMs = stat.totalListenMs, playCount = stat.playCount,
                firstPlayedAt = stat.firstPlayedAt, lastPlayedAt = stat.lastPlayedAt
            )
        }
        val firstFinalized = SyncPlaybackStatsMergePolicy.finalizeMergedStats(
            stats.shuffled(Random(17)), buckets.shuffled(Random(29))
        )
        val secondDeviceStats = stats.asReversed()
        val secondDeviceBuckets = buckets.asReversed()
        val secondFinalized = SyncPlaybackStatsMergePolicy.finalizeMergedStats(secondDeviceStats, secondDeviceBuckets)
        assertEquals(firstFinalized, secondFinalized)
        val repository = SyncArchiveRepository(temporaryFolder.newFolder())
        val firstData = SyncData(deviceId = "device-a", lastModified = 1,
            playbackStats = firstFinalized.stats, playbackStatBuckets = firstFinalized.buckets)

        prepare(repository, firstData).use { first ->
            assertTrue("fixture must contain many independently reusable objects", first.paths.size > 10)
            val fullWire = first.objects.sumOf { it.content.size.toLong() } + first.content.size
            val secondData = firstData.copy(deviceId = "device-b",
                playbackStats = secondFinalized.stats, playbackStatBuckets = secondFinalized.buckets)
            prepare(repository, secondData).use { same ->
                assertEquals(first.paths, same.paths)
                assertTrue(same.objects(first.paths).none())
            }
            val changedIdentity = stats[50_000].identityKey
            val changedStats = secondDeviceStats.map { stat ->
                if (stat.identityKey == changedIdentity) stat.copy(
                    totalListenMs = stat.totalListenMs + 60_000,
                    playCount = stat.playCount + 1, lastPlayedAt = stat.lastPlayedAt + 60_000
                ) else stat
            }
            val changedBuckets = secondDeviceBuckets.map { bucket ->
                if (bucket.identityKey == changedIdentity) bucket.copy(
                    totalListenMs = bucket.totalListenMs + 60_000,
                    playCount = bucket.playCount + 1, lastPlayedAt = bucket.lastPlayedAt + 60_000
                ) else bucket
            }
            val changed = SyncPlaybackStatsMergePolicy.finalizeMergedStats(changedStats, changedBuckets)
            assertEquals(100_000, changed.stats.size)
            assertEquals(100_000, changed.buckets.size)
            prepare(repository, secondData.copy(lastModified = 2,
                playbackStats = changed.stats, playbackStatBuckets = changed.buckets)).use { updated ->
                val changedObjects = updated.objects(first.paths).toList()
                val changedWire = changedObjects.sumOf { it.content.size.toLong() } + updated.content.size
                val reused = (first.paths intersect updated.paths).size
                assertTrue("shuffled input lost block reuse: $reused/${first.paths.size}", reused >= first.paths.size * 3 / 4)
                assertTrue("single playback delta transferred $changedWire bytes", changedWire < 3_000_000)
                assertTrue("delta transferred most of the archive: $changedWire/$fullWire", changedWire < fullWire / 2)
                println("SYNC_DELTA name=shuffled-statistics records=200000 wire=$changedWire objects=${changedObjects.size} reused=$reused/${first.paths.size}")
            }
        }
    }

    private suspend fun prepare(repository: SyncArchiveRepository, data: SyncData): SyncPreparedArchive =
        repository.playbackDatasets.fromLegacy(data).use { repository.prepareCancellable(it) }
}
