@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Index
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Objects
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Ref
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Stream
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4TreeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncArchiveV4ParallelReadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun leafDownloadsOverlapWithinABoundedBatchAndKeepStreamOrder() = runBlocking {
        val fixture = fixture(9)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val started = AtomicInteger()
        val firstBatch = CompletableDeferred<Unit>()
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val reader = reader(fixture) { ref ->
            requested += ref.path
            if (!ref.index) {
                val concurrent = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, concurrent) }
                try {
                    if (started.incrementAndGet() <= 4) {
                        if (started.get() == 4) firstBatch.complete(Unit)
                        firstBatch.await()
                    }
                } finally { active.decrementAndGet() }
            }
            Result.success(fixture.objects.readCompressed(ref))
        }
        val leaves = withTimeout(5_000L) { reader.read(fixture.stream) }
        assertEquals(fixture.leaves, leaves)
        assertTrue("independent downloads must overlap", peak.get() > 1)
        assertTrue("download memory and request concurrency must stay bounded", peak.get() <= 4)
        assertEquals(fixture.leaves.size + 1, requested.size)
        assertEquals(requested.toSet(), reader.paths)
    }

    @Test fun repeatedLeavesAreFetchedOnceAndStillExpandInTheirOriginalPositions() = runBlocking {
        val fixture = fixture(2, repeatLeaves = true)
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val reader = reader(fixture) { ref ->
            requested += ref.path
            Result.success(fixture.objects.readCompressed(ref))
        }
        assertEquals(fixture.leaves, reader.read(fixture.stream))
        assertEquals(1, requested.count { it == fixture.leaves.first().path })
        assertEquals(1, requested.count { it == fixture.leaves.last().path })
        assertEquals(requested.toSet(), reader.paths)
    }

    @Test fun failingLeafCancelsItsBatchBeforeAnotherBatchStarts() = runBlocking {
        val fixture = fixture(9)
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        val batchStarted = CompletableDeferred<Unit>()
        val failure = IOException("synthetic missing remote object")
        val reader = reader(fixture) { ref ->
            if (ref.index) Result.success(fixture.objects.readCompressed(ref)) else {
                if (started.incrementAndGet() == 4) batchStarted.complete(Unit)
                try {
                    batchStarted.await()
                    if (ref == fixture.leaves.first()) Result.failure(failure) else awaitCancellation()
                } finally {
                    if (ref != fixture.leaves.first()) cancelled.incrementAndGet()
                }
            }
        }
        val actual = withTimeout(5_000L) { runCatching { reader.read(fixture.stream) }.exceptionOrNull() }
        assertSame(failure, actual)
        assertEquals(4, started.get())
        assertEquals(3, cancelled.get())
        assertEquals(setOf(checkNotNull(fixture.stream.root).path), reader.paths)
    }

    @Test fun invalidStreamBudgetsAreRejectedBeforeAnyLeafDownload() = runBlocking {
        val fixture = fixture(4)
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val reader = reader(fixture) { ref ->
            requested += ref.path
            Result.success(fixture.objects.readCompressed(ref))
        }
        assertTrue(runCatching { reader.read(fixture.stream.copy(rawBytes = fixture.stream.rawBytes - 1)) }.isFailure)
        assertEquals(listOf(checkNotNull(fixture.stream.root).path), requested)
    }

    @Test fun parentCancellationFinishesAllActiveLeavesBeforeReturning() = runBlocking {
        val fixture = fixture(9)
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        val batchStarted = CompletableDeferred<Unit>()
        val reader = reader(fixture) { ref ->
            if (ref.index) Result.success(fixture.objects.readCompressed(ref)) else {
                try {
                    if (started.incrementAndGet() == 4) batchStarted.complete(Unit)
                    awaitCancellation()
                } finally { cancelled.incrementAndGet() }
            }
        }
        withTimeout(5_000L) {
            val pending = async { reader.read(fixture.stream) }
            batchStarted.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        }
        assertEquals(4, started.get())
        assertEquals(4, cancelled.get())
        assertEquals(setOf(checkNotNull(fixture.stream.root).path), reader.paths)
    }

    @Test fun delayedLeafDownloadsRetainTheirContents() = runBlocking {
        val fixture = fixture(12)
        val reader = reader(fixture) { ref ->
            if (!ref.index) delay(80L)
            Result.success(fixture.objects.readCompressed(ref))
        }
        val start = System.nanoTime()
        assertEquals(fixture.leaves, reader.read(fixture.stream))
        println("12 sync leaves with 80 ms fetch latency: ${(System.nanoTime() - start) / 1_000_000L} ms")
    }

    private fun reader(fixture: Fixture, fetch: suspend (SyncArchiveV4Ref) -> Result<ByteArray>): SyncArchiveV4TreeReader {
        val refs = (fixture.leaves + checkNotNull(fixture.stream.root)).associateBy { it.path }
        return SyncArchiveV4TreeReader(SyncArchiveV4Objects(temporary.newFolder()), { path -> fetch(refs.getValue(path)) }, true)
    }

    private fun fixture(count: Int, repeatLeaves: Boolean = false): Fixture {
        val objects = SyncArchiveV4Objects(temporary.newFolder())
        val unique = (0 until count).map { value -> checkNotNull(objects.store(byteArrayOf(value.toByte(), 1, 2), false)) }
        val leaves = if (repeatLeaves) listOf(unique[0], unique[0], unique[1], unique[0], unique[1]) else unique
        val root = checkNotNull(objects.store(ProtoBuf.encodeToByteArray(SyncArchiveV4Index(leaves)), true))
        return Fixture(objects, leaves, SyncArchiveV4Stream(root, leaves.size * 3L, leaves.size.toLong()))
    }

    private data class Fixture(val objects: SyncArchiveV4Objects, val leaves: List<SyncArchiveV4Ref>, val stream: SyncArchiveV4Stream)
}
