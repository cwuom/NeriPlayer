package moe.ouom.neriplayer.core.download.storage

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedMetadataReadResult
import moe.ouom.neriplayer.core.download.storage.operation.content.readDownloadedAudioMetadataWithWorkers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManagedDownloadMetadataReadWorkersTest {
    @Test
    fun `full delete reads every receipt with sixteen fixed workers`() = runBlocking {
        val entries = List(1000, ::entry)
        val workers = ConcurrentHashMap.newKeySet<Job>()
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val result = readDownloadedAudioMetadataWithWorkers(entries, fullLibraryDelete = true) {
            workers += requireNotNull(currentCoroutineContext()[Job])
            calls.incrementAndGet()
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                delay(2L)
                ManagedMetadataReadResult.Missing
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals(entries.map(StoredEntry::reference), result.keys.toList())
        assertEquals(1000, calls.get())
        assertEquals(16, workers.size)
        assertTrue(peak.get() in 5..16)
        assertEquals(0, active.get())
    }

    @Test
    fun `ordinary reads retain four workers`() = runBlocking {
        val entries = List(100, ::entry)
        val workers = ConcurrentHashMap.newKeySet<Job>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val result = readDownloadedAudioMetadataWithWorkers(entries) {
            workers += requireNotNull(currentCoroutineContext()[Job])
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                delay(2L)
                ManagedMetadataReadResult.Missing
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals(entries.size, result.size)
        assertEquals(4, workers.size)
        assertTrue(peak.get() in 2..4)
        assertEquals(0, active.get())
    }

    @Test
    fun `a slow receipt does not hold back the next chunk`() = runBlocking {
        val entries = List(32, ::entry)
        val releaseSlowRead = CompletableDeferred<Unit>()
        val nextChunkReached = CompletableDeferred<Unit>()
        val read = async {
            readDownloadedAudioMetadataWithWorkers(entries, fullLibraryDelete = true) { current ->
                if (current == entries.first()) releaseSlowRead.await()
                if (current == entries[16]) nextChunkReached.complete(Unit)
                ManagedMetadataReadResult.Missing
            }
        }
        try {
            assertEquals(true, withTimeoutOrNull(2_000L) { nextChunkReached.await(); true })
        } finally {
            releaseSlowRead.complete(Unit)
        }
        assertEquals(entries.size, read.await().size)
    }

    @Test
    fun `unavailable receipts do not suppress other results or reorder references`() = runBlocking {
        val entries = List(4, ::entry)
        val expected = listOf(
            ManagedMetadataReadResult.Unavailable(SecurityException("permission lost")),
            ManagedMetadataReadResult.Found(DownloadedAudioMetadata(stableKey = "owned")),
            ManagedMetadataReadResult.Malformed,
            ManagedMetadataReadResult.Missing
        )
        val calls = ConcurrentHashMap.newKeySet<String>()
        val result = readDownloadedAudioMetadataWithWorkers(entries, fullLibraryDelete = true) { current ->
            val index = entries.indexOf(current)
            calls += current.reference
            delay((entries.size - index) * 2L)
            expected[index]
        }

        assertEquals(entries.map(StoredEntry::reference), result.keys.toList())
        assertEquals(expected, result.values.toList())
        assertEquals(entries.map(StoredEntry::reference).toSet(), calls)
    }

    @Test
    fun `duplicate references retain last input result even if it finishes first`() = runBlocking {
        val first = entry(1)
        val last = first.copy(name = "last receipt")
        val result = readDownloadedAudioMetadataWithWorkers(listOf(first, last), fullLibraryDelete = true) {
            if (it == first) {
                delay(10L)
                ManagedMetadataReadResult.Missing
            } else {
                ManagedMetadataReadResult.Unavailable(SecurityException("denied"))
            }
        }

        assertEquals(1, result.size)
        assertTrue(result[first.reference] is ManagedMetadataReadResult.Unavailable)
    }

    @Test
    fun `receipt cancellation propagates without returning partial results`() = runBlocking {
        try {
            readDownloadedAudioMetadataWithWorkers(listOf(entry(0)), fullLibraryDelete = true) {
                throw CancellationException("receipt cancelled")
            }
            fail("cancellation must propagate")
        } catch (error: CancellationException) {
            assertEquals("receipt cancelled", error.message)
        }
    }

    private fun entry(index: Int) = StoredEntry(
        name = "song-$index.mp3.npmeta.json",
        reference = "content://provider/document/receipt-$index",
        mediaUri = "content://provider/document/receipt-$index",
        localFilePath = null,
        sizeBytes = 32L,
        lastModifiedMs = 1L
    )
}
