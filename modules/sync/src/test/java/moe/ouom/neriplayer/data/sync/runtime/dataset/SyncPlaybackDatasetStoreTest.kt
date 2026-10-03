package moe.ouom.neriplayer.data.sync.runtime.dataset

import moe.ouom.neriplayer.data.sync.dataset.SyncPlaybackKeyOrder

import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException

class SyncPlaybackDatasetStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unorderedInputIsSortedWithoutLosingDuplicatesAndBothBucketOrdersAgree() = runBlocking {
        val store = FileSyncPlaybackDatasetStore(temporary.newFolder())
        val tracks = (0 until 18_000).map { SyncTrackStat(identityKey = "key-${18_000 - it}", playCount = it) } +
            listOf(SyncTrackStat(identityKey = "emoji-\uE000"), SyncTrackStat(identityKey = "emoji-😀"), SyncTrackStat(identityKey = "key-1", playCount = 9))
        store.newSink().use { sink ->
            for (page in tracks.chunked(SYNC_PLAYBACK_PAGE_RECORDS)) sink.appendTracks(page)
            sink.appendBuckets(listOf(
                SyncPlaybackStatBucket(identityKey = "b", dayStartAt = 1),
                SyncPlaybackStatBucket(identityKey = "a", dayStartAt = 2),
                SyncPlaybackStatBucket(identityKey = "a", dayStartAt = 1)
            ))
            sink.seal().use { source ->
                val actual = read(source.openTracks())
                assertEquals(tracks.sortedWith(compareBy(SyncPlaybackKeyOrder, SyncTrackStat::identityKey)), actual)
                assertEquals(listOf(1L to "a", 1L to "b", 2L to "a"), read(source.openBuckets()).map { it.dayStartAt to it.identityKey })
                assertEquals(listOf("a" to 1L, "a" to 2L, "b" to 1L), read(source.openBuckets(SyncPlaybackBucketOrder.IDENTITY_DAY)).map { it.identityKey to it.dayStartAt })
            }
        }
    }

    @Test fun largeRecordsUseSingleRecordPagesAndSortingHeadsDoNotRetainPayloads() = runBlocking {
        val store = FileSyncPlaybackDatasetStore(temporary.newFolder())
        store.newSink().use { sink ->
            repeat(70) { index ->
                sink.appendTracks(listOf(SyncTrackStat(identityKey = "same-prefix-".repeat(30) + (70 - index).toString().padStart(4, '0'), name = "x".repeat(1024 * 1024))))
            }
            sink.seal().use { source ->
                source.openTracks().use { cursor ->
                    var count = 0
                    var previous: String? = null
                    while (true) {
                        val page = cursor.nextPage()
                        if (page.isEmpty()) break
                        assertEquals(1, page.size)
                        val track = page.single()
                        previous?.let { assertTrue(SyncPlaybackKeyOrder.compare(it, track.identityKey) <= 0) }
                        assertEquals(1024 * 1024, track.name.length)
                        previous = track.identityKey
                        count++
                    }
                    assertEquals(70, count)
                }
            }
        }
    }

    @Test fun corruptedSealedFileFailsAndClosingReleasesOnlyItsDataset() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileSyncPlaybackDatasetStore(directory)
        val first = store.fromLegacy(SyncData(playbackStats = listOf(SyncTrackStat(identityKey = "one", name = "before"))))
        val second = store.fromLegacy(SyncData(playbackStats = listOf(SyncTrackStat(identityKey = "two"))))
        val file = directory.walkTopDown().first { it.isFile && it.name.startsWith("tracks-") && it.extension == "bin" && it.readText(Charsets.ISO_8859_1).contains("before") }
        RandomAccessFile(file, "rw").use { random ->
            random.seek(file.length() - 1)
            val value = random.read()
            random.seek(file.length() - 1)
            random.write(value xor 1)
        }
        assertTrue(runCatching { read(first.playback.openTracks()) }.isFailure)
        first.close()
        assertEquals("two", read(second.playback.openTracks()).single().identityKey)
        second.close()
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun truncatedFileRejectsBeforeOpeningAndAbandonedLeasesRecoverWithoutTouchingActiveOnes() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileSyncPlaybackDatasetStore(directory)
        val live = store.fromLegacy(SyncData(playbackStats = listOf(SyncTrackStat(identityKey = "live"))))
        val abandoned = File(directory, "dataset-abandoned").apply { mkdir() }
        File(abandoned, "owner.lock").writeText("NERI_SYNC_PLAYBACK_STAGE_1", Charsets.US_ASCII)
        File(abandoned, "large.bin").writeBytes(ByteArray(1024))
        val foreign = File(directory, "dataset-foreign").apply { mkdir() }
        File(foreign, "keep").writeText("foreign")
        FileSyncPlaybackDatasetStore(directory)
        assertFalse(abandoned.exists())
        assertTrue(foreign.exists())
        assertEquals("live", read(live.playback.openTracks()).single().identityKey)
        val file = directory.walkTopDown().first { it.isFile && it.name.startsWith("tracks-") && it.extension == "bin" }
        RandomAccessFile(file, "rw").use { it.setLength(file.length() - 1) }
        assertTrue(runCatching { live.playback.openTracks() }.isFailure)
        live.close()
    }

    @Test fun cancellationBeforeSealDeletesTheOwnedSession() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileSyncPlaybackDatasetStore(directory)
        val canceled = Job().apply { cancel() }
        val failure = runCatching {
            store.newSink().use { sink -> withContext(canceled) { sink.appendTracks(listOf(SyncTrackStat(identityKey = "cancel"))) } }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun pageBudgetsAndTransferredLeaseRejectFurtherWritesWithoutDeletingTheSource() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileSyncPlaybackDatasetStore(directory)
        val sink = store.newSink()
        assertTrue(runCatching { sink.appendTracks(List(SYNC_PLAYBACK_PAGE_RECORDS + 1) { SyncTrackStat() }) }.isFailure)
        sink.appendTracks(listOf(SyncTrackStat(identityKey = "owned")))
        val source = sink.seal()
        assertTrue(runCatching { sink.appendBuckets(emptyList()) }.isFailure)
        sink.close()
        sink.close()
        assertTrue(runCatching { sink.appendTracks(emptyList()) }.isFailure)
        assertEquals("owned", read(source.openTracks()).single().identityKey)
        assertTrue(runCatching { SyncDataset(SyncData(playbackStats = listOf(SyncTrackStat())), source) }.isFailure)
        assertTrue(runCatching { SyncDataset(SyncData(playbackStatBuckets = listOf(SyncPlaybackStatBucket())), source) }.isFailure)
        source.close()
        source.close()
        assertTrue(runCatching { source.openTracks() }.isFailure)
        assertTrue(runCatching { source.openBuckets() }.isFailure)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun cleanupFailureRetainsOwnershipAndBlocksNewStagingUntilRecoverySucceeds() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileSyncPlaybackDatasetStore(directory)
        val dataset = store.fromLegacy(SyncData(playbackStats = listOf(SyncTrackStat(identityKey = "cleanup"))))
        val session = directory.listFiles().orEmpty().single()
        val blocked = File(session, "blocked").apply { mkdir() }
        File(blocked, "record.bin").writeBytes(ByteArray(16))
        check(blocked.setWritable(false, false))
        try {
            assertFalse("failure fixture must deny directory writes", blocked.canWrite())
            assertTrue(runCatching { dataset.close() }.exceptionOrNull() is IOException)
            assertTrue(File(session, "owner.lock").isFile)
            assertTrue(runCatching { store.newSink() }.exceptionOrNull() is IOException)
            assertEquals(1, directory.listFiles().orEmpty().size)
        } finally {
            check(blocked.setWritable(true, true))
            FileSyncPlaybackDatasetStore(directory)
        }
        assertFalse(session.exists())
    }

    private suspend fun <T> read(cursor: SyncPlaybackCursor<T>): List<T> = cursor.use {
        buildList { while (true) { val page = it.nextPage(); if (page.isEmpty()) break; assertTrue(page.size <= SYNC_PLAYBACK_PAGE_RECORDS); addAll(page) } }
    }
}
