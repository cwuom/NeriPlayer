package moe.ouom.neriplayer.core.download.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    fun `synchronous delete callbacks surround successful and failed attempts`() {
        val successfulReference = "content://documents.test/document/success"
        val failedReference = "content://documents.test/document/failure"
        val references = listOf(successfulReference, failedReference).map { externalReference ->
            val uri = mock(Uri::class.java)
            `when`(uri.scheme).thenReturn("content")
            `when`(uri.authority).thenReturn("documents.test")
            `when`(uri.pathSegments).thenReturn(emptyList())
            TrustedManagedRef(
                reference = StorageReference.SafRef(uri),
                externalReference = externalReference
            )
        }
        val events = mutableListOf<String>()
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceDeleteOperation = { _, reference, _, _ ->
                if (reference.externalReference == successfulReference) {
                    StorageMutationResult.Deleted
                } else {
                    StorageMutationResult.PermissionLost
                }
            },
            contentReferenceGoneOperation = { _, reference ->
                if (reference.externalReference == successfulReference) {
                    ManagedDownloadReferenceIo.AccessResult.Missing
                } else {
                    ManagedDownloadReferenceIo.AccessResult.PermissionLost
                }
            }
        )

        val result = executor.deleteReferences(
            context = mock(Context::class.java),
            references = references,
            deletePolicy = ManagedDownloadDeletePolicy(
                managedFileRoots = emptyList(),
                managedTreeRoots = emptyList(),
                trustedReferences = references.toSet()
            ),
            onDeleteStarted = { reference ->
                events += "start:${reference.externalReference}"
            },
            onDeleteAttemptFinished = { reference, deleted ->
                events += "finish:${reference.externalReference}:$deleted"
            }
        )

        assertEquals(setOf(successfulReference), result.deletedReferences)
        assertTrue(result.hasUnconfirmedDeletes)
        assertEquals(
            listOf(
                "start:$successfulReference",
                "finish:$successfulReference:true",
                "start:$failedReference",
                "finish:$failedReference:false"
            ),
            events
        )
    }

    @Test
    fun `confirmed synchronous saf delete skips redundant gone probe`() {
        val reference = TrustedManagedRef(
            reference = StorageReference.SafRef(mock(Uri::class.java)),
            externalReference = "content://documents.test/document/confirmed"
        )
        val goneProbeCalls = AtomicInteger(0)
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceDeleteOperation = { _, _, maxAttempts, retryDelayMs ->
                assertEquals(SAF_DELETE_MAX_ATTEMPTS, maxAttempts)
                assertEquals(SAF_DELETE_RETRY_DELAY_MS, retryDelayMs)
                StorageMutationResult.Deleted
            },
            contentReferenceGoneOperation = { _, _ ->
                goneProbeCalls.incrementAndGet()
                ManagedDownloadReferenceIo.AccessResult.Accessible
            }
        )

        val result = executor.deleteTrustedContentReference(
            context = mock(Context::class.java),
            reference = reference
        )

        assertEquals(StorageMutationResult.Deleted, result)
        assertEquals(0, goneProbeCalls.get())
    }

    @Test
    fun `synchronous saf delete tries every alias after a missing result`() {
        val sourceUri = mock(Uri::class.java)
        val singleDocumentUri = mock(Uri::class.java)
        val treeDocumentUri = mock(Uri::class.java)
        `when`(sourceUri.scheme).thenReturn("content")
        `when`(sourceUri.authority).thenReturn("documents.test")
        `when`(sourceUri.pathSegments)
            .thenReturn(listOf("tree", "root", "document", "root/song.mp3"))
        val seenUris = mutableListOf<Uri>()
        val reference = TrustedManagedRef(
            reference = StorageReference.SafRef(sourceUri),
            externalReference = "content://documents.test/document/song.mp3"
        )
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceDeleteOperation = { _, candidate, _, _ ->
                val candidateUri = (candidate.reference as StorageReference.SafRef).uri
                seenUris += candidateUri
                if (seenUris.size == 1) {
                    StorageMutationResult.Missing
                } else {
                    StorageMutationResult.Deleted
                }
            }
        )

        val result = runBlocking {
            withTreeAliases(
                sourceUri,
                singleDocumentUri,
                treeDocumentUri
            ) {
                executor.deleteTrustedContentReference(
                    context = mock(Context::class.java),
                    reference = reference
                )
            }
        }

        assertTrue(seenUris.size >= 2)
        assertEquals(sourceUri, seenUris.first())
        assertEquals(StorageMutationResult.Deleted, result)
    }

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
    fun `concurrent saf missing alias stays retryable after later alias failure`() = runBlocking {
        val sourceUri = mock(Uri::class.java)
        val singleDocumentUri = mock(Uri::class.java)
        val treeDocumentUri = mock(Uri::class.java)
        `when`(sourceUri.scheme).thenReturn("content")
        `when`(sourceUri.authority).thenReturn("documents.test")
        `when`(sourceUri.pathSegments)
            .thenReturn(listOf("tree", "root", "document", "root/song.mp3"))
        val deleteCalls = AtomicInteger(0)
        val reference = TrustedManagedRef(
            reference = StorageReference.SafRef(sourceUri),
            externalReference = "content://documents.test/document/song.mp3"
        )
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            referenceDeleteParallelism = 1,
            contentReferenceDeleteOperation = { _, _, _, _ ->
                if (deleteCalls.incrementAndGet() == 1) {
                    StorageMutationResult.Missing
                } else {
                    StorageMutationResult.ProviderFailure(
                        IllegalStateException("unavailable")
                    )
                }
            },
            contentReferenceGoneOperation = { _, _ ->
                ManagedDownloadReferenceIo.AccessResult.Accessible
            },
            workerDispatcher = Dispatchers.Unconfined
        )

        val result = withTreeAliases(
            sourceUri,
            singleDocumentUri,
            treeDocumentUri
        ) {
            executor.deleteReferencesConcurrently(
                context = mock(Context::class.java),
                references = listOf(reference),
                deletePolicy = ManagedDownloadDeletePolicy(
                    managedFileRoots = emptyList(),
                    managedTreeRoots = emptyList(),
                    trustedReferences = setOf(reference)
                ),
                parallelism = 1
            )
        }

        assertTrue(deleteCalls.get() >= 2)
        assertTrue(result.deletedReferences.isEmpty())
        assertTrue(result.hasUnconfirmedDeletes)
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
    fun `duplicate external references are deleted only once`() = runBlocking {
        val externalReference = "content://documents.test/document/duplicate"
        val first = TrustedManagedRef(
            reference = StorageReference.SafRef(mock(Uri::class.java)),
            externalReference = externalReference
        )
        val second = TrustedManagedRef(
            reference = StorageReference.SafRef(mock(Uri::class.java)),
            externalReference = externalReference
        )
        val deleteCalls = AtomicInteger(0)
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceDeleteOperation = { _, _, _, _ ->
                deleteCalls.incrementAndGet()
                StorageMutationResult.Deleted
            }
        )

        val result = executor.deleteReferencesConcurrently(
            context = mock(Context::class.java),
            references = listOf(first, second),
            deletePolicy = ManagedDownloadDeletePolicy(
                managedFileRoots = emptyList(),
                managedTreeRoots = emptyList(),
                trustedReferences = setOf(first)
            )
        )

        assertEquals(listOf(externalReference), result.requestedReferences)
        assertEquals(setOf(externalReference), result.deletedReferences)
        assertEquals(1, deleteCalls.get())
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
    fun `concurrent delete callbacks report each reference exactly once`() = runBlocking {
        val references = (0 until 24).map { index ->
            "content://documents.test/document/callback-$index"
        }
        val starts = ConcurrentHashMap.newKeySet<String>()
        val finishes = ConcurrentHashMap.newKeySet<String>()
        val finishCounts = ConcurrentHashMap<String, AtomicInteger>()
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            referenceDeleteParallelism = 3,
            contentReferenceDeleteOperation = { _, _, maxAttempts, retryDelayMs ->
                assertEquals(1, maxAttempts)
                assertEquals(0L, retryDelayMs)
                Thread.sleep(2L)
                StorageMutationResult.Deleted
            }
        )

        val result = executor.deleteReferencesConcurrently(
            context = mock(Context::class.java),
            references = trustedReferences(references),
            deletePolicy = deletePolicyFor(references),
            parallelism = 3,
            onDeleteStarted = { reference ->
                assertTrue(starts.add(reference.externalReference))
            },
            onDeleteAttemptFinished = { reference, deleted ->
                assertTrue(deleted)
                finishes += reference.externalReference
                finishCounts.getOrPut(reference.externalReference) { AtomicInteger() }
                    .incrementAndGet()
            }
        )

        assertEquals(references.toSet(), result.deletedReferences)
        assertEquals(references.toSet(), starts)
        assertEquals(references.toSet(), finishes)
        assertTrue(finishCounts.values.all { count -> count.get() == 1 })
    }

    @Test
    fun `concurrent delete reports each completed reference before the next operation`() =
        runBlocking {
            val fastReference = "content://documents.test/document/fast"
            val slowReference = "content://documents.test/document/slow"
            val references = listOf(fastReference, slowReference)
            val fastFinished = CountDownLatch(1)
            val slowObservedFastFinish = AtomicBoolean(false)
            val executor = ManagedDownloadReferenceDeleteExecutor(
                tag = "ManagedDownloadReferenceDeleteExecutorTest",
                isReferenceAllowed = { _, _, _, _ -> true },
                referenceDeleteParallelism = 1,
                contentReferenceDeleteOperation = { _, reference, _, _ ->
                    if (reference.externalReference == slowReference) {
                        slowObservedFastFinish.set(
                            fastFinished.await(2, TimeUnit.SECONDS)
                        )
                    }
                    StorageMutationResult.Deleted
                }
            )

            val result = executor.deleteReferencesConcurrently(
                context = mock(Context::class.java),
                references = trustedReferences(references),
                deletePolicy = deletePolicyFor(references),
                parallelism = 1,
                onDeleteAttemptFinished = { reference, deleted ->
                    if (reference.externalReference == fastReference && deleted) {
                        fastFinished.countDown()
                    }
                }
            )

            assertEquals(references.toSet(), result.deletedReferences)
            assertTrue(slowObservedFastFinish.get())
        }

    @Test
    fun `concurrent delete propagates cancellation instead of retrying it`() {
        val reference = "content://documents.test/document/cancelled"
        val cancellation = CancellationException("cancelled")
        val deleteCalls = AtomicInteger(0)
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceDeleteOperation = { _, _, _, _ ->
                deleteCalls.incrementAndGet()
                throw cancellation
            }
        )

        var thrown: CancellationException? = null
        try {
            runBlocking {
                executor.deleteReferencesConcurrently(
                    context = mock(Context::class.java),
                    references = trustedReferences(listOf(reference)),
                    deletePolicy = deletePolicyFor(listOf(reference)),
                    parallelism = 1
                )
            }
        } catch (error: CancellationException) {
            thrown = error
        }

        assertEquals(1, deleteCalls.get())
        thrown?.let { error -> assertEquals(cancellation.message, error.message) }
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

    @Test
    fun `large saf delete uses bounded provider batches without per reference fallback`() = runBlocking {
        val references = (0 until 3_864).map { index ->
            "content://documents.test/document/library-$index"
        }
        val batchCalls = AtomicInteger(0)
        val individualDeleteCalls = AtomicInteger(0)
        val activeBatches = AtomicInteger(0)
        val maximumActiveBatches = AtomicInteger(0)
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceDeleteOperation = { _, _, _, _ ->
                individualDeleteCalls.incrementAndGet()
                StorageMutationResult.Deleted
            },
            contentReferenceBatchDeleteOperation = { _, batch ->
                assertTrue(batch.size <= SAF_REFERENCE_DELETE_BATCH_SIZE)
                batchCalls.incrementAndGet()
                val activeCount = activeBatches.incrementAndGet()
                maximumActiveBatches.accumulateAndGet(activeCount) { current, candidate ->
                    maxOf(current, candidate)
                }
                try {
                    Thread.sleep(2L)
                    List(batch.size) { StorageMutationResult.Deleted }
                } finally {
                    activeBatches.decrementAndGet()
                }
            }
        )

        val result = executor.deleteReferencesConcurrently(
            context = mock(Context::class.java),
            references = trustedReferences(references),
            deletePolicy = deletePolicyFor(references)
        )

        assertEquals(references.toSet(), result.deletedReferences)
        assertFalse(result.hasUnconfirmedDeletes)
        assertEquals(0, individualDeleteCalls.get())
        assertEquals(
            (references.size + SAF_REFERENCE_DELETE_BATCH_SIZE - 1) /
                SAF_REFERENCE_DELETE_BATCH_SIZE,
            batchCalls.get()
        )
        assertTrue(maximumActiveBatches.get() <= SAF_REFERENCE_DELETE_BATCH_PARALLELISM)
    }

    @Test
    fun `provider batch cancellation escapes without starting individual deletes`() {
        val references = listOf("content://documents.test/document/cancel-batch")
        val individualDeletes = AtomicInteger()
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceBatchDeleteOperation = { _, _ ->
                throw CancellationException("provider cancelled batch")
            },
            contentReferenceDeleteOperation = { _, _, _, _ ->
                individualDeletes.incrementAndGet()
                StorageMutationResult.Deleted
            }
        )
        var thrown: CancellationException? = null
        try {
            runBlocking {
                executor.deleteReferencesConcurrently(
                    context = mock(Context::class.java),
                    references = trustedReferences(references),
                    deletePolicy = deletePolicyFor(references)
                )
            }
        } catch (error: CancellationException) {
            thrown = error
        }
        assertEquals("provider cancelled batch", thrown?.message)
        assertEquals(0, individualDeletes.get())
    }

    @Test
    fun `provider batches respect the caller concurrency bound`() = runBlocking {
        val references = (0 until 512).map { "content://documents.test/document/bounded-$it" }
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceBatchDeleteOperation = { _, batch ->
                val count = active.incrementAndGet()
                maximumActive.accumulateAndGet(count, ::maxOf)
                try {
                    Thread.sleep(20)
                    List(batch.size) { StorageMutationResult.Deleted }
                } finally {
                    active.decrementAndGet()
                }
            }
        )

        val result = executor.deleteReferencesConcurrently(
            context = mock(Context::class.java),
            references = trustedReferences(references),
            deletePolicy = deletePolicyFor(references),
            parallelism = 2
        )

        assertEquals(references.toSet(), result.deletedReferences)
        assertTrue("provider concurrency exceeded caller limit: ${maximumActive.get()}", maximumActive.get() <= 2)
    }

    @Test
    fun `provider progress is published before a long batch hides completed deletes`() = runBlocking {
        val references = (0 until 256).map { "content://documents.test/document/progress-$it" }
        val physicallyDeleted = AtomicInteger()
        val physicalCountAtFirstProgress = AtomicInteger()
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceBatchDeleteOperation = { _, batch ->
                physicallyDeleted.addAndGet(batch.size)
                List(batch.size) { StorageMutationResult.Deleted }
            }
        )

        val result = executor.deleteReferencesConcurrently(
            context = mock(Context::class.java),
            references = trustedReferences(references),
            deletePolicy = deletePolicyFor(references),
            parallelism = 1,
            onDeleteAttemptFinished = { _, deleted ->
                if (deleted) physicalCountAtFirstProgress.compareAndSet(0, physicallyDeleted.get())
            }
        )

        assertEquals(references.toSet(), result.deletedReferences)
        assertTrue("too many files disappeared before first progress: ${physicalCountAtFirstProgress.get()}",
            physicalCountAtFirstProgress.get() in 1..32)
    }

    @Test
    fun `failed batch entries alone use compatible per reference fallback`() = runBlocking {
        val references = (0 until 12).map { index ->
            "content://documents.test/document/fallback-$index"
        }
        val fallbackReferences = ConcurrentHashMap.newKeySet<String>()
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceDeleteOperation = { _, reference, _, _ ->
                fallbackReferences += reference.externalReference
                StorageMutationResult.Deleted
            },
            contentReferenceBatchDeleteOperation = { _, batch ->
                batch.mapIndexed { index, _ ->
                    if (index % 3 == 0) {
                        StorageMutationResult.ProviderFailure(
                            IllegalStateException("batch rejected")
                        )
                    } else {
                        StorageMutationResult.Deleted
                    }
                }
            }
        )

        val result = executor.deleteReferencesConcurrently(
            context = mock(Context::class.java),
            references = trustedReferences(references),
            deletePolicy = deletePolicyFor(references)
        )

        assertEquals(references.toSet(), result.deletedReferences)
        assertEquals(
            references.filterIndexed { index, _ -> index % 3 == 0 }.toSet(),
            fallbackReferences
        )
    }

    private suspend fun <T> withTreeAliases(
        sourceUri: Uri,
        singleDocumentUri: Uri,
        treeDocumentUri: Uri,
        block: suspend () -> T
    ): T {
        return mockStatic(DocumentsContract::class.java).use { documentsContract ->
            documentsContract.`when`<String> {
                DocumentsContract.getDocumentId(sourceUri)
            }.thenReturn("root/song.mp3")
            documentsContract.`when`<Uri> {
                DocumentsContract.buildDocumentUri("documents.test", "root/song.mp3")
            }.thenReturn(singleDocumentUri)
            documentsContract.`when`<Uri> {
                DocumentsContract.buildDocumentUriUsingTree(sourceUri, "root/song.mp3")
            }.thenReturn(treeDocumentUri)
            block()
        }
    }

    @Test
    fun `system storage batches report early progress and never mix providers`() = runBlocking {
        val references = (0 until 300).map { index ->
            val authority = if (index % 10 == 0) "documents.test" else "com.android.externalstorage.documents"
            val uri = mock(Uri::class.java)
            `when`(uri.authority).thenReturn(authority)
            TrustedManagedRef(StorageReference.SafRef(uri), "content://$authority/document/$index")
        }
        val batches = mutableListOf<List<TrustedManagedRef>>()
        val reported = mutableListOf<String>()
        val executor = ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadReferenceDeleteExecutorTest",
            isReferenceAllowed = { _, _, _, _ -> true },
            contentReferenceBatchDeleteOperation = { _, batch ->
                val authorities = batch.map { (it.reference as StorageReference.SafRef).uri.authority }.toSet()
                assertEquals(1, authorities.size)
                val limit = if (authorities.single() == "documents.test") SAF_REFERENCE_DELETE_BATCH_SIZE else 128
                assertTrue(batch.size <= limit)
                batches += batch
                List(batch.size) { StorageMutationResult.Deleted }
            }
        )

        val result = executor.deleteReferencesConcurrently(
            mock(Context::class.java), references,
            ManagedDownloadDeletePolicy(emptyList(), emptyList(), references.toSet()),
            parallelism = 1,
            onDeleteAttemptFinished = { reference, deleted ->
                assertTrue(deleted)
                reported += reference.externalReference
            }
        )

        val systemBatches = batches.filter {
            (it.first().reference as StorageReference.SafRef).uri.authority == "com.android.externalstorage.documents"
        }
        assertEquals(SAF_REFERENCE_DELETE_BATCH_SIZE, systemBatches.first().size)
        assertTrue(systemBatches.drop(1).any { it.size == 128 })
        assertEquals(references.map { it.externalReference }.toSet(), result.deletedReferences)
        assertEquals(references.size, reported.size)
        assertEquals(references.size, reported.toSet().size)
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
