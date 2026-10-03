package moe.ouom.neriplayer.data.sync.runtime.dataset

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.dataset.SyncPlaybackKeyOrder
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.sync.merge.dataset.SyncDatasetMerger
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.merge.engine.TestSyncMergeHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncDatasetNoOpReuseTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun verifiedCanonicalCopiesReadSixCompleteStreamsWithoutCreatingOutputFiles() = runBlocking {
        val directory = temporary.newFolder()
        val files = FileSyncPlaybackDatasetStore(directory)
        val store = CountingStore(files)
        val merger = merger(store)
        val data = canonicalData(600)
        files.fromLegacy(data).use { local -> files.fromLegacy(data).use { remote ->
            val left = CountedSource(local.playback)
            val right = CountedSource(remote.playback)
            val captured = SyncDataset(local.data, left, 19L)
            val fetched = SyncDataset(remote.data, right)
            val merged = merger.merge(captured, fetched, 0).dataset
            try {
                assertEquals("Unchanged canonical playback must reuse its captured files", 0, store.sinks)
                assertEquals(listOf(1, 1, 1), left.eofs.toList())
                assertEquals(listOf(1, 1, 1), right.eofs.toList())
                assertTrue(merged.playbackMatchesCaptured)
                assertEquals(19L, merged.capturedPlaybackRevision)
                assertFalse(merger.changed(fetched, merged, false))
                assertEquals("changed must reuse the proof for this exact remote source", listOf(1, 1, 1), right.eofs.toList())
                assertEquals(2, directory.listFiles().orEmpty().size)
                assertEquals(data.playbackStats, merged.readForTest().playbackStats)
            } finally { merged.close() }
            merged.close()
            assertTrue(runCatching { merged.playback.openTracks() }.isFailure)
            assertTrue(runCatching { merged.playback.openBuckets() }.isFailure)
            assertEquals("A borrowed result must leave its captured parent open", data.playbackStats, captured.readForTest().playbackStats)
            assertEquals("Closing the result must not close either input", 0, left.closed)
            assertEquals(0, right.closed)
        } }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun matchingUnderCountedTracksStillLiftBucketTotals() = runBlocking {
        val canonical = canonicalData(1)
        val wrong = canonical.copy(playbackStats = canonical.playbackStats.map { it.copy(totalListenMs = 10, playCount = 1, counterShards = emptyList()) })
        verifyOrdinaryNormalization(wrong) { result ->
            assertEquals(40L, result.playbackStats.single().totalListenMs)
            assertEquals(4, result.playbackStats.single().playCount)
        }
    }

    @Test fun matchingDuplicateRecordsAndActorsStillNormalizeAcrossPageBoundaries() = runBlocking {
        val base = canonicalData(1)
        val smaller = base.playbackStats.single().copy(totalListenMs = 10, playCount = 1)
        val duplicateActors = base.playbackStats.single().copy(counterShards = listOf(
            shard(40, 4), shard(10, 1), shard(40, 4).copy(firstPlayedAt = 50, lastPlayedAt = 250)
        ))
        val duplicates = base.copy(playbackStats = List(256) { smaller } + duplicateActors)
        verifyOrdinaryNormalization(duplicates) { result ->
            val track = result.playbackStats.single()
            assertEquals(40L, track.totalListenMs)
            assertEquals(4, track.playCount)
            assertEquals(listOf(shard(40, 4).copy(firstPlayedAt = 50, lastPlayedAt = 250)), track.counterShards)
        }
    }

    @Test fun duplicateCanonicalTracksStillRequireDeduplicationAcrossPages() = runBlocking {
        val base = canonicalData(1)
        verifyOrdinaryNormalization(base.copy(playbackStats = List(257) { base.playbackStats.single() })) { result ->
            assertEquals(base.playbackStats, result.playbackStats)
            assertEquals(base.playbackStatBuckets, result.playbackStatBuckets)
        }
    }

    @Test fun bucketsWithoutTheirParentTrackMustRestoreTheMissingTrackAtEveryIdentityPosition() = runBlocking {
        val base = canonicalData(2)
        for (identity in listOf("!missing-before", "track-000000-extra", "z-missing-after")) {
            val orphan = base.playbackStatBuckets.first().copy(identityKey = identity, id = 77, name = "recovered from bucket")
            verifyOrdinaryNormalization(base.copy(playbackStatBuckets = base.playbackStatBuckets + orphan)) { result ->
                assertEquals(3, result.playbackStats.size)
                val recovered = result.playbackStats.single { it.identityKey == identity }
                assertEquals(20L, recovered.totalListenMs)
                assertEquals(2, recovered.playCount)
                assertEquals("recovered from bucket", recovered.name)
            }
        }
    }

    @Test fun matchingCopiesCannotReturnOldEpochCountsAcrossAnExistingClearBarrier() = runBlocking {
        val data = canonicalData(1).copy(playbackStatsClearedAt = 150)
        for (input in listOf(data, data.copy(playbackStatBuckets = emptyList()))) {
            verifyOrdinaryNormalization(input) { result ->
                assertTrue(result.playbackStats.isEmpty())
                assertTrue(result.playbackStatBuckets.isEmpty())
            }
        }
    }

    @Test fun saturatedCanonicalCountsCanStillReuseFilesExactly() = runBlocking {
        val files = FileSyncPlaybackDatasetStore(temporary.newFolder())
        val store = CountingStore(files)
        val merger = merger(store)
        val data = canonicalData(1).let { normal -> normal.copy(
            playbackStats = normal.playbackStats.map { it.copy(totalListenMs = Long.MAX_VALUE, playCount = Int.MAX_VALUE,
                counterShards = listOf(shard(Long.MAX_VALUE, Int.MAX_VALUE))) },
            playbackStatBuckets = normal.playbackStatBuckets.map { it.copy(totalListenMs = Long.MAX_VALUE,
                playCount = Int.MAX_VALUE, counterShards = listOf(shard(Long.MAX_VALUE, Int.MAX_VALUE))) }
        ) }
        files.fromLegacy(data).use { local -> files.fromLegacy(data).use { remote ->
            merger.merge(local, remote, 0).dataset.use { merged ->
                assertEquals(0, store.sinks)
                assertTrue(merged.playbackMatchesCaptured)
                assertEquals(data.playbackStats, merged.readForTest().playbackStats)
            }
        } }
    }

    @Test fun identicalCopiesWithDifferentBucketIndexMetadataCannotBorrowTheWrongIndex() = runBlocking {
        val files = FileSyncPlaybackDatasetStore(temporary.newFolder())
        val store = CountingStore(files)
        val merger = merger(store)
        val data = canonicalData(1)
        files.fromLegacy(data).use { local -> files.fromLegacy(data).use { remote ->
            fun wrongIndex(source: SyncPlaybackSource) = object : SyncPlaybackSource by source {
                override fun openBuckets(order: SyncPlaybackBucketOrder): SyncPlaybackCursor<SyncPlaybackStatBucket> {
                    val cursor = source.openBuckets(order)
                    return object : SyncPlaybackCursor<SyncPlaybackStatBucket> by cursor {
                        override suspend fun nextPage(): List<SyncPlaybackStatBucket> = cursor.nextPage().map {
                            if (order == SyncPlaybackBucketOrder.IDENTITY_DAY) it.copy(name = "different index payload", mediaUri = "different uri") else it
                        }
                    }
                }
            }
            merger.merge(SyncDataset(local.data, wrongIndex(local.playback)), SyncDataset(remote.data, wrongIndex(remote.playback)), 0).dataset.use { merged ->
                assertTrue("Index association must cover metadata and not only counters", store.sinks > 0)
                assertEquals(data.playbackStatBuckets, merged.readForTest().playbackStatBuckets)
            }
        } }
    }

    @Test fun reversedIdentityIndexDaysCannotBeBorrowedEvenWhenBothCopiesHaveTheSamePayloadSet() = runBlocking {
        val files = FileSyncPlaybackDatasetStore(temporary.newFolder())
        val store = CountingStore(files)
        val data = canonicalData(1)
        files.fromLegacy(data).use { local -> files.fromLegacy(data).use { remote ->
            val left = mapIdentityPages(local.playback) { it.asReversed() }
            val right = mapIdentityPages(remote.playback) { it.asReversed() }
            merger(store).merge(SyncDataset(local.data, left), SyncDataset(remote.data, right), 0).dataset.use { merged ->
                assertTrue("Equal index digests cannot replace strict identity/day order", store.sinks > 0)
                assertEquals(data.playbackStatBuckets, merged.readForTest().playbackStatBuckets)
            }
        } }
    }

    @Test fun unequalRemoteIdentityPayloadsMustUseTheValidatedDayIndexForNormalization() = runBlocking {
        val files = FileSyncPlaybackDatasetStore(temporary.newFolder())
        val data = canonicalData(1)
        for (changedDay in listOf(1L, 2L)) {
            val store = CountingStore(files)
            files.fromLegacy(data).use { local -> files.fromLegacy(data).use { remote ->
                val right = mapIdentityPages(remote.playback) { page -> page.map { bucket ->
                    if (bucket.dayStartAt == changedDay) bucket.copy(name = "unequal remote payload") else bucket
                } }
                merger(store).merge(local, SyncDataset(remote.data, right), 0).dataset.use { merged ->
                    assertTrue("Paired input comparison must include all decoded bucket fields", store.sinks > 0)
                    assertEquals(data.playbackStatBuckets, merged.readForTest().playbackStatBuckets)
                }
            } }
        }
    }

    @Test fun anUnbucketedNewRemoteTrackCannotReuseTheShorterCapturedTrackStream() = runBlocking {
        val files = FileSyncPlaybackDatasetStore(temporary.newFolder())
        val store = CountingStore(files)
        val data = canonicalData(1)
        val newer = data.copy(playbackStats = data.playbackStats + data.playbackStats.single().copy(identityKey = "z-new", id = 88))
        files.fromLegacy(data).use { local -> files.fromLegacy(newer).use { remote ->
            merger(store).merge(local, remote, 0).dataset.use { merged ->
                assertTrue(store.sinks > 0)
                assertFalse(merged.playbackMatchesCaptured)
                assertEquals(newer.playbackStats, merged.readForTest().playbackStats)
            }
        } }
    }

    @Test fun remoteIdentityIndexEofChecksumFailureCannotConfirmAnUnchangedDataset() = runBlocking {
        val directory = temporary.newFolder()
        val files = FileSyncPlaybackDatasetStore(directory)
        val failure = IOException("identity index checksum mismatch after all decoded rows")
        verifyIdentityEofFailure(files, failure)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationAtRemoteIdentityIndexEofKeepsInputLeasesAndReleasesItsCursors() = runBlocking {
        val directory = temporary.newFolder()
        val files = FileSyncPlaybackDatasetStore(directory)
        val cancellation = CancellationException("cancelled at identity index eof")
        verifyIdentityEofFailure(files, cancellation)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun aDifferentRemoteSourceAndMigrationMustKeepTheirOwnChangeChecks() = runBlocking {
        val files = FileSyncPlaybackDatasetStore(temporary.newFolder())
        val store = CountingStore(files)
        val merger = merger(store)
        val data = canonicalData(600)
        val changed = data.copy(playbackStats = data.playbackStats.dropLast(1) + data.playbackStats.last().copy(name = "a changed last record"))
        files.fromLegacy(data).use { local -> files.fromLegacy(data).use { remote -> files.fromLegacy(changed).use { newer ->
            val checkedRemote = CountedSource(remote.playback)
            val fetched = SyncDataset(remote.data, checkedRemote)
            merger.merge(local, fetched, 0).dataset.use { merged ->
                assertFalse(merger.changed(fetched, merged, false))
                assertTrue("A refetched source must not inherit a previous source proof", merger.changed(newer, merged, false))
                assertTrue(merger.changed(fetched, merged, true))
                val otherHeader = SyncDataset(remote.data.copy(playbackStatsClearedAt = 1), checkedRemote)
                assertTrue(merger.changed(otherHeader, merged, false))
                assertEquals(0, store.sinks)
            }
        } } }
    }

    private suspend fun verifyOrdinaryNormalization(data: SyncData, check: (SyncData) -> Unit) {
        val files = FileSyncPlaybackDatasetStore(temporary.newFolder())
        val store = CountingStore(files)
        files.fromLegacy(data).use { local -> files.fromLegacy(data).use { remote ->
            merger(store).merge(local, remote, 0).dataset.use { merged ->
                assertTrue("Matching input copies still require normalization", store.sinks > 0)
                check(merged.readForTest())
            }
        } }
    }

    private suspend fun verifyIdentityEofFailure(files: FileSyncPlaybackDatasetStore, failure: Exception) {
        val store = CountingStore(files)
        val data = canonicalData(300)
        files.fromLegacy(data).use { local -> files.fromLegacy(data).use { remote ->
            val left = CountedSource(local.playback)
            val right = CountedSource(remote.playback, failure)
            var output: SyncDataset? = null
            val caught = runCatching { output = merger(store).merge(SyncDataset(local.data, left), SyncDataset(remote.data, right), 0).dataset }.exceptionOrNull()
            output?.close()
            assertSame(failure, caught)
            assertEquals(0, store.sinks)
            assertEquals(0, left.closed)
            assertEquals(0, right.closed)
            assertTrue("Every cursor opened by the proof must close on failure", left.opened.all { it.closed })
            assertTrue(right.opened.all { it.closed })
            assertEquals(data.playbackStats, local.readForTest().playbackStats)
            assertEquals(data.playbackStats, remote.readForTest().playbackStats)
        } }
    }

    private fun merger(store: SyncPlaybackDatasetStore) = SyncDatasetMerger(SyncDataMerger(TestSyncMergeHost()) { 100L }, store)

    private fun mapIdentityPages(source: SyncPlaybackSource,
        map: (List<SyncPlaybackStatBucket>) -> List<SyncPlaybackStatBucket>): SyncPlaybackSource = object : SyncPlaybackSource by source {
        override fun openBuckets(order: SyncPlaybackBucketOrder): SyncPlaybackCursor<SyncPlaybackStatBucket> {
            val cursor = source.openBuckets(order)
            if (order != SyncPlaybackBucketOrder.IDENTITY_DAY) return cursor
            return object : SyncPlaybackCursor<SyncPlaybackStatBucket> by cursor {
                override suspend fun nextPage(): List<SyncPlaybackStatBucket> = map(cursor.nextPage())
            }
        }
    }

    private class CountingStore(private val files: SyncPlaybackDatasetStore) : SyncPlaybackDatasetStore {
        var sinks = 0
        override fun newSink(): SyncPlaybackSink { sinks++; return files.newSink() }
        override fun newOrderedSink(): SyncPlaybackSink { sinks++; return files.newOrderedSink() }
    }

    private class CursorState { var closed = false }

    private class CountedSource(private val delegate: SyncPlaybackSource, private val identityEofFailure: Exception? = null) : SyncPlaybackSource {
        val eofs = IntArray(3)
        val opened = mutableListOf<CursorState>()
        var closed = 0
        override fun openTracks() = counted(delegate.openTracks(), 0)
        override fun openBuckets(order: SyncPlaybackBucketOrder) = counted(delegate.openBuckets(order), if (order == SyncPlaybackBucketOrder.DAY_IDENTITY) 1 else 2)
        private fun <T> counted(cursor: SyncPlaybackCursor<T>, family: Int): SyncPlaybackCursor<T> {
            val state = CursorState().also(opened::add)
            return object : SyncPlaybackCursor<T> {
                override suspend fun nextPage(): List<T> = cursor.nextPage().also { page ->
                    if (page.isEmpty()) {
                        eofs[family]++
                        if (family == 2) identityEofFailure?.let { throw it }
                    }
                }
                override fun close() { state.closed = true; cursor.close() }
            }
        }
        override fun close() { closed++; delegate.close() }
    }

    private fun canonicalData(count: Int): SyncData {
        val tracks = (0 until count).map { index -> SyncTrackStat(identityKey = "track-${index.toString().padStart(6, '0')}",
            id = index.toLong(), name = "name-$index", totalListenMs = 40, playCount = 4, firstPlayedAt = 100, lastPlayedAt = 200,
            counterShards = listOf(shard(40, 4))) }.sortedWith(compareBy(SyncPlaybackKeyOrder, SyncTrackStat::identityKey))
        val buckets = (1L..2L).flatMap { day -> tracks.map { stat -> SyncPlaybackStatBucket(dayStartAt = day,
            identityKey = stat.identityKey, id = stat.id, name = "bucket-${stat.id}", totalListenMs = 20, playCount = 2,
            firstPlayedAt = 100, lastPlayedAt = 200, counterShards = listOf(shard(20, 2))) } }
        return SyncData(playbackStats = tracks, playbackStatBuckets = buckets)
    }

    private fun shard(total: Long, count: Int) = SyncPlaybackCounterShard("actor", 0, total, count, 100, 200)
}
