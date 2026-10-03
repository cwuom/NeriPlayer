package moe.ouom.neriplayer.data.sync.host

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDatasetRemoteSnapshot
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackBucketOrder
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource
import org.junit.Assert.*
import org.junit.Test

class SyncRemoteProtocolObservationTest {
    @Test fun `observations retain the actual remote protocol version`() = runTest {
        for (version in listOf(3, 4)) {
            val source = Source()
            val snapshot = SyncDatasetRemoteSnapshot(SyncDataset(SyncData(), source), "version")
            var observedVersion: Int? = null
            assertSame(snapshot, observeCurrentSyncProtocol(snapshot, version) { observedVersion = it }.getOrThrow())
            assertEquals(version, observedVersion)
            assertFalse(source.closed)
            snapshot.dataset!!.close()
        }
    }

    @Test fun `success transfers dataset ownership to the caller`() = runTest {
        val source = Source()
        val snapshot = SyncDatasetRemoteSnapshot(SyncDataset(SyncData(), source), "version")
        assertSame(snapshot, observeCurrentSyncProtocol(snapshot) {}.getOrThrow())
        assertFalse(source.closed)
        snapshot.dataset!!.close()
    }

    @Test fun `failed and cancelled observations close the decoded dataset`() = runTest {
        for (failure in listOf(IOException("disk full"), CancellationException("cancelled"))) {
            val source = Source()
            val snapshot = SyncDatasetRemoteSnapshot(SyncDataset(SyncData(), source), "version")
            val observed = runCatching { observeCurrentSyncProtocol(snapshot) { throw failure } }
            val error = if (observed.isFailure) observed.exceptionOrNull() else observed.getOrThrow().exceptionOrNull()
            assertSame(failure, error)
            assertTrue(source.closed)
        }
    }

    @Test fun `cleanup failure does not replace the durable observation error`() = runTest {
        val original = IOException("disk full")
        val cleanup = IOException("cleanup failed")
        val snapshot = SyncDatasetRemoteSnapshot(SyncDataset(SyncData(), Source(cleanup)), "version")
        assertSame(original, observeCurrentSyncProtocol(snapshot) { throw original }.exceptionOrNull())
        assertArrayEquals(arrayOf(cleanup), original.suppressed)
    }

    private class Source(private val cleanup: IOException? = null) : SyncPlaybackSource {
        var closed = false
        override fun openTracks() = error("observation must not read tracks")
        override fun openBuckets(order: SyncPlaybackBucketOrder) = error("observation must not read buckets")
        override fun close() { closed = true; cleanup?.let { throw it } }
    }
}
