package moe.ouom.neriplayer.core.download.storage.migration

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadMigrationCopyReceiptBatcherTest {
    @Test
    fun `receipts flush in bounded batches and retain the latest value`() = runTest {
        val batches = mutableListOf<List<ManagedMigrationCopyReceipt>>()
        val batcher = ManagedDownloadMigrationCopyReceiptBatcher(batchSize = 2) {
            batches += it
        }

        batcher.add(receipt("source-a", 1L))
        assertEquals(emptyList<List<ManagedMigrationCopyReceipt>>(), batches)
        batcher.add(receipt("source-b", 2L))
        batcher.add(receipt("source-a", 3L))
        batcher.flush()

        assertEquals(2, batches.size)
        assertEquals(listOf("source-a", "source-b"), batches[0].map { it.sourceReference })
        assertEquals(listOf("source-a"), batches[1].map { it.sourceReference })
        assertEquals(3L, batches[1].single().sourceSizeBytes)
    }

    @Test
    fun `invalidated pending receipt is never persisted`() = runTest {
        val batches = mutableListOf<List<ManagedMigrationCopyReceipt>>()
        val batcher = ManagedDownloadMigrationCopyReceiptBatcher(batchSize = 8) {
            batches += it
        }

        batcher.add(receipt("source-a", 1L))
        batcher.invalidate("source-a")
        batcher.flush()

        assertEquals(emptyList<List<ManagedMigrationCopyReceipt>>(), batches)
    }

    private fun receipt(reference: String, sizeBytes: Long): ManagedMigrationCopyReceipt {
        val target = ManagedDownloadStorage.StoredEntry(
            name = "track.mp3",
            reference = "target-$reference",
            mediaUri = "target-$reference",
            localFilePath = null,
            sizeBytes = sizeBytes,
            lastModifiedMs = 1L
        )
        return ManagedMigrationCopyReceipt(
            sourceReference = reference,
            sourceName = "track.mp3",
            sourceSubdirectory = null,
            sourceSizeBytes = sizeBytes,
            sourceLastModifiedMs = 1L,
            targetEntry = target,
            createdNew = true,
            sourceAuthoritative = true
        )
    }
}
