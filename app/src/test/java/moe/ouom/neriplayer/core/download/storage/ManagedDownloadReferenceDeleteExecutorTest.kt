package moe.ouom.neriplayer.core.download.storage

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import android.net.Uri
import moe.ouom.neriplayer.core.download.storage.delete.ManagedDownloadDeletePolicy
import moe.ouom.neriplayer.core.download.storage.delete.ManagedDownloadReferenceDeleteExecutor
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class ManagedDownloadReferenceDeleteExecutorTest {

    @Test
    fun `delete boundary accepts trusted managed references`() = runBlocking {
        val references = listOf(
            TrustedManagedRef(
                reference = StorageReference.SafRef(mock(Uri::class.java)),
                externalReference = "content://documents.test/document/song"
            )
        )
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceDeleteOperation = { _, _, _, _ -> StorageMutationResult.Deleted },
            contentReferenceGoneOperation = { _, _ -> ManagedDownloadReferenceIo.AccessResult.Missing }
        )

        val result = executor.deleteReferencesConcurrently(
            context = mock(Context::class.java),
            references = references,
            deletePolicy = ManagedDownloadDeletePolicy(
                managedFileRoots = emptyList(),
                managedTreeRoots = emptyList(),
                trustedReferences = references.toSet()
            )
        )

        assertEquals(setOf("content://documents.test/document/song"), result.deletedReferences)
    }

    @Test
    fun `delete boundary rejects unenumerated saf references`() = runBlocking {
        val reference = TrustedManagedRef(
            reference = StorageReference.SafRef(mock(Uri::class.java)),
            externalReference = "content://documents.test/document/untrusted"
        )
        val deleteCalls = AtomicInteger(0)
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceDeleteOperation = { _, _, _, _ ->
                deleteCalls.incrementAndGet()
                StorageMutationResult.Deleted
            },
            contentReferenceGoneOperation = { _, _ -> ManagedDownloadReferenceIo.AccessResult.Missing }
        )

        val result = executor.deleteReferencesConcurrently(
            context = mock(Context::class.java),
            references = listOf(reference),
            deletePolicy = ManagedDownloadDeletePolicy(
                managedFileRoots = emptyList(),
                managedTreeRoots = emptyList(),
                trustedReferences = emptySet()
            )
        )

        assertTrue(result.deletedReferences.isEmpty())
        assertEquals(0, deleteCalls.get())
    }

    @Test
    fun `batch retries use bounded workers and finish every reference`() = runBlocking {
        val references = (0 until 120).map { index ->
            "content://documents.test/document/song-$index"
        }
        val deleteCalls = AtomicInteger(0)
        val activeWorkers = AtomicInteger(0)
        val maximumActiveWorkers = AtomicInteger(0)
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            referenceDeleteParallelism = 4,
            contentReferenceDeleteOperation = { _, _, maxAttempts, retryDelayMs ->
                assertEquals(1, maxAttempts)
                assertEquals(0L, retryDelayMs)
                val activeCount = activeWorkers.incrementAndGet()
                maximumActiveWorkers.accumulateAndGet(activeCount) { current, candidate ->
                    maxOf(current, candidate)
                }
                try {
                    Thread.sleep(2L)
                    if (deleteCalls.incrementAndGet() > references.size * 2) {
                        StorageMutationResult.Deleted
                    } else {
                        StorageMutationResult.ProviderFailure(
                            IllegalStateException("retry")
                        )
                    }
                } finally {
                    activeWorkers.decrementAndGet()
                }
            },
            contentReferenceGoneOperation = { _, _ -> ManagedDownloadReferenceIo.AccessResult.Accessible }
        )
        val result = executor.deleteReferencesConcurrently(
            context = mock(Context::class.java),
            references = trustedReferences(references),
            deletePolicy = deletePolicyFor(references)
        )
        assertEquals(references.toSet(), result.deletedReferences)
        assertFalse(result.hasUnconfirmedDeletes)
        assertTrue(maximumActiveWorkers.get() <= 4)
        assertEquals(references.size * SAF_DELETE_MAX_ATTEMPTS, deleteCalls.get())
    }

    @Test
    fun `missing references are finalized after all delete attempts fail`() = runBlocking {
        val references = listOf(
            "content://documents.test/document/missing-a",
            "content://documents.test/document/missing-b"
        )
        val deleteCalls = AtomicInteger(0)
        val inspectedReferenceCount = AtomicInteger(0)
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            referenceDeleteParallelism = 2,
            contentReferenceDeleteOperation = { _, _, _, _ ->
                deleteCalls.incrementAndGet()
                StorageMutationResult.ProviderFailure(IllegalStateException("not yet"))
            },
            contentReferenceGoneOperation = { _, _ ->
                inspectedReferenceCount.incrementAndGet()
                ManagedDownloadReferenceIo.AccessResult.Missing
            }
        )

        val result = executor.deleteReferencesConcurrently(
            context = mock(Context::class.java),
            references = trustedReferences(references),
            deletePolicy = deletePolicyFor(references)
        )

        assertEquals(references.toSet(), result.deletedReferences)
        assertFalse(result.hasUnconfirmedDeletes)
        assertEquals(references.size * SAF_DELETE_MAX_ATTEMPTS, deleteCalls.get())
        assertEquals(references.size, inspectedReferenceCount.get())
    }

    private fun deletePolicyFor(references: List<String>): ManagedDownloadDeletePolicy {
        return ManagedDownloadDeletePolicy(
            managedFileRoots = emptyList(),
            managedTreeRoots = emptyList(),
            trustedReferences = references.mapTo(linkedSetOf()) { reference ->
                TrustedManagedRef(
                    reference = StorageReference.SafRef(mock(Uri::class.java)),
                    externalReference = reference
                )
            }
        )
    }

    private fun trustedReferences(references: List<String>): List<TrustedManagedRef> {
        return references.map { reference ->
            TrustedManagedRef(
                reference = StorageReference.SafRef(mock(Uri::class.java)),
                externalReference = reference
            )
        }
    }
}
