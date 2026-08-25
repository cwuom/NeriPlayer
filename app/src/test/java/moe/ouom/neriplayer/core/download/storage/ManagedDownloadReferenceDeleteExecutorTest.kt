package moe.ouom.neriplayer.core.download.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
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
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.mockingDetails

class ManagedDownloadReferenceDeleteExecutorTest {

    @Test
    fun `delete boundary accepts trusted managed references`() = runBlocking {
        val references = listOf(
            TrustedManagedRef(
                reference = StorageReference.SafRef(mock(Uri::class.java)),
                externalReference = "content://documents.test/document/song"
            )
        )
        val goneProbeCalls = AtomicInteger(0)
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceDeleteOperation = { _, _, _, _ -> StorageMutationResult.Deleted },
            contentReferenceGoneOperation = { _, _ ->
                goneProbeCalls.incrementAndGet()
                ManagedDownloadReferenceIo.AccessResult.Missing
            }
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
        assertEquals(0, goneProbeCalls.get())
    }

    @Test
    fun `confirmed DocumentsContract delete bypasses saf probes`() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        `when`(context.contentResolver).thenReturn(resolver)

        mockStatic(DocumentsContract::class.java).use { documentsContract ->
            documentsContract.`when`<Boolean> {
                DocumentsContract.deleteDocument(resolver, uri)
            }.thenReturn(true)

            val result = ManagedDownloadReferenceIo.deleteContentReference(
                context = context,
                uri = uri,
                maxAttempts = 1,
                retryDelayMs = 0L
            )

            assertEquals(ManagedDownloadReferenceIo.DeleteResult.Deleted, result)
        }

        assertTrue(mockingDetails(resolver).invocations.isEmpty())
    }

    @Test
    fun `one thousand confirmed saf deletes use bounded workers without gone probes`() = runBlocking {
        val references = (0 until 1_000).map { index ->
            "content://documents.test/document/song-$index"
        }
        val deleteCalls = AtomicInteger(0)
        val goneProbeCalls = AtomicInteger(0)
        val activeWorkers = AtomicInteger(0)
        val maximumActiveWorkers = AtomicInteger(0)
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            referenceDeleteParallelism = SAF_REFERENCE_DELETE_PARALLELISM,
            contentReferenceDeleteOperation = { _, _, maxAttempts, retryDelayMs ->
                assertEquals(1, maxAttempts)
                assertEquals(0L, retryDelayMs)
                val activeCount = activeWorkers.incrementAndGet()
                maximumActiveWorkers.accumulateAndGet(activeCount) { current, candidate ->
                    maxOf(current, candidate)
                }
                try {
                    Thread.sleep(1L)
                    deleteCalls.incrementAndGet()
                    StorageMutationResult.Deleted
                } finally {
                    activeWorkers.decrementAndGet()
                }
            },
            contentReferenceGoneOperation = { _, _ ->
                goneProbeCalls.incrementAndGet()
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
        assertEquals(references.size, deleteCalls.get())
        assertEquals(0, goneProbeCalls.get())
        assertTrue(maximumActiveWorkers.get() <= SAF_REFERENCE_DELETE_PARALLELISM)
    }

    @Test
    fun `known missing saf references finish without gone probes`() = runBlocking {
        val references = listOf(
            "content://documents.test/document/missing-a",
            "content://documents.test/document/missing-b"
        )
        val deleteCalls = AtomicInteger(0)
        val goneProbeCalls = AtomicInteger(0)
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceDeleteOperation = { _, _, _, _ ->
                deleteCalls.incrementAndGet()
                StorageMutationResult.Missing
            },
            contentReferenceGoneOperation = { _, _ ->
                goneProbeCalls.incrementAndGet()
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
        assertEquals(references.size, deleteCalls.get())
        assertEquals(0, goneProbeCalls.get())
    }

    @Test
    fun `permission loss remains unconfirmed when gone probe cannot verify deletion`() = runBlocking {
        val references = listOf("content://documents.test/document/permission-lost")
        val deleteCalls = AtomicInteger(0)
        val goneProbeCalls = AtomicInteger(0)
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceDeleteOperation = { _, _, _, _ ->
                deleteCalls.incrementAndGet()
                StorageMutationResult.PermissionLost
            },
            contentReferenceGoneOperation = { _, _ ->
                goneProbeCalls.incrementAndGet()
                ManagedDownloadReferenceIo.AccessResult.PermissionLost
            }
        )

        val result = executor.deleteReferencesConcurrently(
            context = mock(Context::class.java),
            references = trustedReferences(references),
            deletePolicy = deletePolicyFor(references)
        )

        assertTrue(result.deletedReferences.isEmpty())
        assertTrue(result.hasUnconfirmedDeletes)
        assertEquals(SAF_DELETE_MAX_ATTEMPTS, deleteCalls.get())
        assertEquals(1, goneProbeCalls.get())
    }

    @Test
    fun `unknown provider failures remain unconfirmed without missing evidence`() = runBlocking {
        val references = listOf("content://documents.test/document/provider-failure")
        val deleteCalls = AtomicInteger(0)
        val goneProbeCalls = AtomicInteger(0)
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceDeleteOperation = { _, _, _, _ ->
                deleteCalls.incrementAndGet()
                StorageMutationResult.ProviderFailure(IllegalStateException("provider offline"))
            },
            contentReferenceGoneOperation = { _, _ ->
                goneProbeCalls.incrementAndGet()
                ManagedDownloadReferenceIo.AccessResult.Accessible
            }
        )

        val result = executor.deleteReferencesConcurrently(
            context = mock(Context::class.java),
            references = trustedReferences(references),
            deletePolicy = deletePolicyFor(references)
        )

        assertTrue(result.deletedReferences.isEmpty())
        assertTrue(result.hasUnconfirmedDeletes)
        assertEquals(SAF_DELETE_MAX_ATTEMPTS, deleteCalls.get())
        assertEquals(1, goneProbeCalls.get())
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
