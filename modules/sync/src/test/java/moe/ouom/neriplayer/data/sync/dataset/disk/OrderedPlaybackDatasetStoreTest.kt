package moe.ouom.neriplayer.data.sync.dataset.disk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_BYTES
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackBucketOrder
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackCursor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrderedPlaybackDatasetStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun trackOrderIsStrictAcrossPageBoundariesAndEmptyPages() = runBlocking {
        for (next in listOf("a", "b")) {
            val directory = temporary.newFolder()
            FileSyncPlaybackDatasetStore(directory).newOrderedSink().use { sink ->
                sink.appendTracks(listOf(SyncTrackStat(identityKey = "b")))
                sink.appendTracks(emptyList())
                assertTrue("Unordered or repeated track $next was accepted", runCatching {
                    sink.appendTracks(listOf(SyncTrackStat(identityKey = next)))
                }.isFailure)
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun dayAndBinaryUnicodeBucketOrderIsStrictAcrossPages() = runBlocking {
        val previous = SyncPlaybackStatBucket(identityKey = "unicode-😀", dayStartAt = 2)
        val invalid = listOf(
            previous,
            previous.copy(identityKey = "unicode-\uE000"),
            previous.copy(identityKey = "z", dayStartAt = 1)
        )
        for (next in invalid) {
            val directory = temporary.newFolder()
            FileSyncPlaybackDatasetStore(directory).newOrderedSink().use { sink ->
                sink.appendBuckets(listOf(previous))
                sink.appendBuckets(emptyList())
                assertTrue("Unordered or repeated bucket was accepted: $next", runCatching {
                    sink.appendBuckets(listOf(next))
                }.isFailure)
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun binaryUnicodeTrackOrderCannotUseUtf16StringOrder() = runBlocking {
        val directory = temporary.newFolder()
        FileSyncPlaybackDatasetStore(directory).newOrderedSink().use { sink ->
            sink.appendTracks(listOf(SyncTrackStat(identityKey = "😀")))
            assertTrue(runCatching { sink.appendTracks(listOf(SyncTrackStat(identityKey = "\uE000"))) }.isFailure)
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun orderedWritesDoNotCreateTrackOrDaySortingRunsAndIdentityIndexIsCorrect() = runBlocking {
        val directory = temporary.newFolder()
        val tracks = (0 until 5000).map { SyncTrackStat(identityKey = key(it), playCount = it) }
        val buckets = (1L..3L).flatMap { day -> (0 until 1700).map { index ->
            SyncPlaybackStatBucket(identityKey = key(index), dayStartAt = day, playCount = index)
        } }
        FileSyncPlaybackDatasetStore(directory).newOrderedSink().use { sink ->
            tracks.chunked(SYNC_PLAYBACK_PAGE_RECORDS).forEach { sink.appendTracks(it) }
            buckets.chunked(SYNC_PLAYBACK_PAGE_RECORDS).forEach { sink.appendBuckets(it) }
            val session = directory.listFiles().orEmpty().single()
            assertTrue("Already ordered data created sorting-run metadata", session.listFiles().orEmpty().none { it.extension == "meta" })
            sink.seal().use { source ->
                assertEquals(tracks, read(source.openTracks()))
                assertEquals(buckets, read(source.openBuckets()))
                val identityOrder = (0 until 1700).flatMap { index -> (1L..3L).map { day ->
                    SyncPlaybackStatBucket(identityKey = key(index), dayStartAt = day, playCount = index)
                } }
                assertEquals(identityOrder, read(source.openBuckets(SyncPlaybackBucketOrder.IDENTITY_DAY)))
                assertTrue("Ordered primary files retained sorting-run metadata", session.listFiles().orEmpty().none {
                    it.extension == "meta" && (it.name.startsWith("tracks-") || it.name.startsWith("buckets-"))
                })
            }
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun orderedSinkPreservesPageBudgetsAndLargeRecordSinglePages() = runBlocking {
        val directory = temporary.newFolder()
        FileSyncPlaybackDatasetStore(directory).newOrderedSink().use { sink ->
            assertTrue(runCatching { sink.appendTracks(List(SYNC_PLAYBACK_PAGE_RECORDS + 1) { SyncTrackStat() }) }.isFailure)
            sink.appendTracks(listOf(SyncTrackStat(identityKey = "a", name = "x".repeat(SYNC_PLAYBACK_PAGE_BYTES))))
            sink.appendTracks(listOf(SyncTrackStat(identityKey = "b", name = "y".repeat(SYNC_PLAYBACK_PAGE_BYTES))))
            sink.seal().use { source ->
                source.openTracks().use { cursor ->
                    assertEquals("a", cursor.nextPage().single().identityKey)
                    assertEquals("b", cursor.nextPage().single().identityKey)
                    assertTrue(cursor.nextPage().isEmpty())
                    assertTrue(cursor.nextPage().isEmpty())
                }
            }
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun canceledOrderedAppendReleasesWritersAndOwnedFiles() = runBlocking {
        val directory = temporary.newFolder()
        val canceled = Job().apply { cancel() }
        val failure = runCatching {
            FileSyncPlaybackDatasetStore(directory).newOrderedSink().use { sink ->
                sink.appendTracks(listOf(SyncTrackStat(identityKey = "a")))
                withContext(canceled) { sink.appendTracks(listOf(SyncTrackStat(identityKey = "b"))) }
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun transferredOrderedSinkCannotDeleteOrModifyItsSource() = runBlocking {
        val directory = temporary.newFolder()
        val sink = FileSyncPlaybackDatasetStore(directory).newOrderedSink()
        sink.appendTracks(listOf(SyncTrackStat(identityKey = "owned")))
        val source = sink.seal()
        try {
            sink.close()
            sink.close()
            assertTrue(runCatching { sink.appendTracks(emptyList()) }.isFailure)
            assertTrue(runCatching { sink.appendBuckets(emptyList()) }.isFailure)
            assertEquals("owned", read(source.openTracks()).single().identityKey)
        } finally {
            source.close()
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun key(index: Int) = "key-${index.toString().padStart(5, '0')}"
    private suspend fun <T> read(cursor: SyncPlaybackCursor<T>): List<T> = cursor.use {
        buildList {
            while (true) {
                val page = cursor.nextPage()
                if (page.isEmpty()) break
                assertTrue(page.size <= SYNC_PLAYBACK_PAGE_RECORDS)
                addAll(page)
            }
        }
    }
}
