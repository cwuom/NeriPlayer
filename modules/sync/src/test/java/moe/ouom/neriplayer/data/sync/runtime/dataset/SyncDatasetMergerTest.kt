package moe.ouom.neriplayer.data.sync.runtime.dataset

import moe.ouom.neriplayer.data.sync.dataset.SyncPlaybackKeyOrder

import moe.ouom.neriplayer.data.sync.merge.dataset.SyncDatasetMerger

import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.sync.*
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.merge.engine.TestSyncMergeHost
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class SyncDatasetMergerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun diskMergeMatchesRecordPoliciesAndBucketLiftingForDuplicatesAndMissingTracks() = runBlocking {
        val store = FileSyncPlaybackDatasetStore(temporary.newFolder())
        val legacyMerger = SyncDataMerger(TestSyncMergeHost()) { 100L }
        val merger = SyncDatasetMerger(legacyMerger, store)
        val local = SyncData(playbackStats = (0..1800).map { track(it, 2) },
            playbackStatBuckets = (0..2500).flatMap { index -> (1L..3L).map { day -> bucket(index, day, 4) } })
        val remote = SyncData(playbackStats = (1200..2900).reversed().map { track(it, 5) } + listOf(track(1200, 7)),
            playbackStatBuckets = (1200..2900).reversed().flatMap { index -> (3L downTo 2L).map { day -> bucket(index, day, 8) } })
        val expected = legacyMerger.merge(local, remote, 0L).mergedData
        store.fromLegacy(local).use { left -> store.fromLegacy(remote).use { right ->
            merger.merge(left, right, 0L).dataset.use { result ->
                val actual = result.readForTest()
                assertEquals(expected.playbackStats.sortedWith(compareBy(SyncPlaybackKeyOrder, SyncTrackStat::identityKey)), actual.playbackStats)
                assertEquals(expected.playbackStatBuckets, actual.playbackStatBuckets)
                assertTrue(merger.changed(right, result, false))
                merger.merge(result, result, 0L).dataset.use { echoed ->
                    assertFalse(merger.changed(result, echoed, false))
                    assertEquals(actual.playbackStats, echoed.readForTest().playbackStats)
                }
            }
        } }
    }

    @Test fun binaryUnicodeOrderClearBarrierAndDirectionProduceTheSameCanonicalDataset() = runBlocking {
        val store = FileSyncPlaybackDatasetStore(temporary.newFolder())
        val merger = SyncDatasetMerger(SyncDataMerger(TestSyncMergeHost()) { 100L }, store)
        val prefix = "key-"
        val local = SyncData(playbackStatsClearedAt = 100,
            playbackStats = listOf(track(1, 2).copy(identityKey = prefix + "😀"), track(2, 3).copy(identityKey = prefix + "\uE000")),
            playbackStatBuckets = listOf(bucket(2, 1, 8).copy(identityKey = prefix + "\uE000")))
        val remote = SyncData(playbackStats = listOf(track(1, 10).copy(identityKey = prefix + "😀", firstPlayedAt = 50),
            track(2, 7).copy(identityKey = prefix + "\uE000")))
        store.fromLegacy(local).use { left -> store.fromLegacy(remote).use { right ->
            merger.merge(left, right, 0L).dataset.use { forward ->
                merger.merge(right, left, 0L).dataset.use { reverse ->
                    assertEquals(forward.readForTest().playbackStats, reverse.readForTest().playbackStats)
                    assertEquals(listOf(prefix + "\uE000", prefix + "😀"), forward.readForTest().playbackStats.map { it.identityKey })
                    assertEquals(2, forward.readForTest().playbackStats.last().playCount)
                }
            }
        } }
    }

    @Test fun mergedBucketFilesAreReusedWithoutASecondCopyAndBothLeasesTransferTogether() = runBlocking {
        val directory = temporary.newFolder()
        val files = FileSyncPlaybackDatasetStore(directory)
        val writes = ArrayList<Int>()
        val store = object : SyncPlaybackDatasetStore {
            override fun newSink(): SyncPlaybackSink {
                val delegate = files.newSink()
                val index = writes.size
                writes.add(0)
                return object : SyncPlaybackSink by delegate {
                    override suspend fun appendBuckets(page: List<SyncPlaybackStatBucket>) {
                        writes[index] += page.size
                        delegate.appendBuckets(page)
                    }
                }
            }
        }
        val merger = SyncDatasetMerger(SyncDataMerger(TestSyncMergeHost()) { 100L }, store)
        val data = SyncData(playbackStats = (0 until 1000).map { track(it, 2) },
            playbackStatBuckets = (0 until 1000).map { bucket(it, 1L, 2) })
        files.fromLegacy(data).use { local ->
            val result = merger.merge(local, null, 0L).dataset
            assertEquals(listOf(1000, 0), writes)
            assertEquals(3, directory.listFiles().orEmpty().size)
            assertEquals(1000, result.readForTest().playbackStatBuckets.size)
            result.close()
            result.close()
            assertTrue(runCatching { result.playback.openTracks() }.isFailure)
            assertTrue(runCatching { result.playback.openBuckets() }.isFailure)
            assertEquals(1, directory.listFiles().orEmpty().size)
            assertEquals(data.playbackStats, local.readForTest().playbackStats)
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationWhileComparingTheCompletedMergeReleasesBothOutputLeases() = runBlocking {
        val directory = temporary.newFolder()
        val files = FileSyncPlaybackDatasetStore(directory)
        val merger = SyncDatasetMerger(SyncDataMerger(TestSyncMergeHost()) { 100L }, files)
        val data = SyncData(playbackStats = listOf(track(1, 2)), playbackStatBuckets = listOf(bucket(1, 1L, 2)))
        files.fromLegacy(data).use { captured ->
            var opened = 0
            val cancellation = CancellationException("comparison cancelled")
            val source = object : SyncPlaybackSource by captured.playback {
                override fun openTracks(): SyncPlaybackCursor<SyncTrackStat> {
                    opened++
                    if (opened == 2) throw cancellation
                    return captured.playback.openTracks()
                }
            }
            val local = SyncDataset(captured.data, source, 7L)
            assertSame(cancellation, runCatching { merger.merge(local, null, 0L) }.exceptionOrNull())
            assertEquals(1, directory.listFiles().orEmpty().size)
            assertEquals(data.playbackStats, captured.readForTest().playbackStats)
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun oneCompositeLeaseCleanupFailureStillClosesTheOtherLease() = runBlocking {
        val directory = temporary.newFolder()
        val files = FileSyncPlaybackDatasetStore(directory)
        val closed = ArrayList<Int>()
        val cleanup = IOException("bucket lease cleanup")
        var sinks = 0
        val store = object : SyncPlaybackDatasetStore {
            override fun newSink(): SyncPlaybackSink {
                val delegate = files.newSink()
                val index = sinks++
                return object : SyncPlaybackSink by delegate {
                    override suspend fun seal(): SyncPlaybackSource {
                        val source = delegate.seal()
                        return object : SyncPlaybackSource by source {
                            override fun close() {
                                closed.add(index)
                                source.close()
                                if (index == 0) throw cleanup
                            }
                        }
                    }
                }
            }
        }
        val merger = SyncDatasetMerger(SyncDataMerger(TestSyncMergeHost()) { 100L }, store)
        files.fromLegacy(SyncData(playbackStats = listOf(track(1, 2)))).use { local ->
            val result = merger.merge(local, null, 0L).dataset
            assertSame(cleanup, runCatching { result.close() }.exceptionOrNull())
            assertEquals(listOf(0, 1), closed)
            result.close()
            assertEquals(listOf(0, 1), closed)
            assertEquals(1, directory.listFiles().orEmpty().size)
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun anOversizedSourcePageCannotPublishAnyOutputLease() = runBlocking {
        val directory = temporary.newFolder()
        val files = FileSyncPlaybackDatasetStore(directory)
        val merger = SyncDatasetMerger(SyncDataMerger(TestSyncMergeHost()) { 100L }, files)
        val source = object : SyncPlaybackSource {
            override fun openTracks(): SyncPlaybackCursor<SyncTrackStat> = cursor(List(SYNC_PLAYBACK_PAGE_RECORDS + 1) { track(it, 2) })
            override fun openBuckets(order: SyncPlaybackBucketOrder): SyncPlaybackCursor<SyncPlaybackStatBucket> = cursor(emptyList())
            override fun close() = Unit
        }
        assertTrue(runCatching { merger.merge(SyncDataset(SyncData(), source), null, 0L) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun <T> cursor(page: List<T>): SyncPlaybackCursor<T> = object : SyncPlaybackCursor<T> {
        private var read = false
        override suspend fun nextPage(): List<T> = if (read) emptyList() else page.also { read = true }
        override fun close() = Unit
    }

    private fun track(index: Int, plays: Int) = SyncTrackStat(identityKey = "track-${index.toString().padStart(5, '0')}",
        id = index.toLong(), name = "name-$index", playCount = plays, totalListenMs = plays * 10L, firstPlayedAt = 100, lastPlayedAt = 200)
    private fun bucket(index: Int, day: Long, plays: Int) = SyncPlaybackStatBucket(identityKey = track(index, plays).identityKey,
        dayStartAt = day, id = index.toLong(), name = "name-$index", playCount = plays, totalListenMs = plays * 10L, firstPlayedAt = 100, lastPlayedAt = 200)
}
