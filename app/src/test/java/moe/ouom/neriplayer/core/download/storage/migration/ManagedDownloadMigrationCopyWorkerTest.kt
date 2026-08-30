package moe.ouom.neriplayer.core.download.storage.migration

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.MIGRATION_IO_MAX_ATTEMPTS
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageStat
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class ManagedDownloadMigrationCopyWorkerTest {
    private val context: Context = mock(Context::class.java)
    private val targetRoot = ManagedDownloadRootHandle.FileRoot(File("."))

    @Test
    fun `migration reader preserves cancellation when stream close also fails`() = runTest {
        val cancellation = CancellationException("cancel migration")
        val closeFailure = IOException("close failed")
        val reader = failingCloseReader(closeFailure)

        val thrown = try {
            reader.readOrThrow(
                context = context,
                entry = entry("audio.mp3", "source-audio", 4L),
                operation = "read source"
            ) {
                throw cancellation
            }
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, thrown)
        assertTrue(cancellation.suppressed.contains(closeFailure))
    }

    @Test
    fun `migration reader preserves permanent failure when stream close also fails`() = runTest {
        val permanent = ManagedDownloadMigrationException.permanent("target conflict")
        val closeFailure = IOException("close failed")
        val reader = failingCloseReader(closeFailure)

        val thrown = try {
            reader.readOrThrow(
                context = context,
                entry = entry("audio.mp3", "source-audio", 4L),
                operation = "write target"
            ) {
                throw permanent
            }
            null
        } catch (error: ManagedDownloadMigrationException) {
            error
        }

        assertSame(permanent, thrown)
        assertTrue(permanent.suppressed.contains(closeFailure))
    }

    @Test
    fun `double hash maps both passes to monotonic logical bytes`() {
        val sourceSize = 4_096L
        val sourceSpan = sourceSize / 2L + sourceSize % 2L
        val targetSpan = sourceSize - sourceSpan
        val logicalProgress = listOf(
            scaledMigrationHashProgress(0L, sourceSize, 0L, sourceSpan),
            scaledMigrationHashProgress(2_048L, sourceSize, 0L, sourceSpan),
            scaledMigrationHashProgress(sourceSize, sourceSize, 0L, sourceSpan),
            scaledMigrationHashProgress(2_048L, sourceSize, sourceSpan, targetSpan),
            scaledMigrationHashProgress(sourceSize, sourceSize, sourceSpan, targetSpan)
        )

        assertEquals(listOf(0L, 1_024L, 2_048L, 3_072L, 4_096L), logicalProgress)
        assertTrue(logicalProgress.zipWithNext().all { (before, after) -> after >= before })

        val content = ByteArray(150_000) { index -> (index % 251).toByte() }
        val hashUpdates = mutableListOf<Long>()
        sha256MigrationContent(ByteArrayInputStream(content), hashUpdates::add)

        assertTrue(hashUpdates.zipWithNext().all { (before, after) -> after > before })
        assertEquals(content.size.toLong(), hashUpdates.last())
    }

    @Test
    fun `reused target completes progress through copy entry`() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val source = entry(
            name = "audio.mp3",
            reference = "source-audio",
            sizeBytes = bytes.size.toLong()
        )
        val target = entry(
            name = "audio.mp3",
            reference = "target-audio",
            sizeBytes = bytes.size.toLong()
        )
        val updates = mutableListOf<ManagedDownloadStorage.MigrationProgress>()
        val worker = copyWorker(
            openInput = { ByteArrayInputStream(bytes) }
        )
        val progressReporter = ManagedMigrationProgressReporter(
            totalFiles = 1,
            totalBytes = source.sizeBytes,
            metadataFilesTotal = 0,
            onProgress = updates::add
        )

        val result = worker.copyEntry(
            context = context,
            targetRoot = targetRoot,
            migrationEntry = ManagedMigrationEntry(subdirectory = null, entry = source),
            targetIndex = targetIndex(target),
            namePlan = ManagedMigrationNamePlan(
                targetNamesByReference = mapOf(source.reference to target.name),
                reusedTargetsByReference = mapOf(source.reference to target)
            ),
            progressTracker = progressReporter
        )
        progressReporter.finishAll()

        assertNull(result.error)
        assertSame(target, result.copiedEntry?.copiedEntry)
        assertFalse(requireNotNull(result.copiedEntry).createdNew)
        assertEquals(1, updates.last().copiedFiles)
        assertEquals(source.sizeBytes, updates.last().copiedBytes)
    }

    @Test
    fun `valid copy receipt skips source and target streams`() = runTest {
        val source = entry("audio.mp3", "source-audio", 4L)
        val target = entry("audio.mp3", "target-audio", 4L)
        val receipt = ManagedMigrationCopyReceipt(
            sourceReference = source.reference,
            sourceName = source.name,
            sourceSubdirectory = null,
            sourceSizeBytes = source.sizeBytes,
            sourceLastModifiedMs = source.lastModifiedMs,
            targetEntry = target,
            sourceDigest = "a".repeat(64),
            verifiedTargetDigest = "a".repeat(64),
            createdNew = false,
            sourceAuthoritative = false
        )
        val result = copyWorker(
            openInput = { error("receipt fast path must not open a stream") }
        ).copyEntry(
            context = context,
            targetRoot = targetRoot,
            migrationEntry = ManagedMigrationEntry(null, source),
            targetIndex = targetIndex(target),
            namePlan = ManagedMigrationNamePlan(
                targetNamesByReference = mapOf(source.reference to target.name)
            ),
            resumeReceipt = receipt
        )

        assertNull(result.error)
        assertSame(target, result.copiedEntry?.copiedEntry)
        assertEquals(receipt.sourceDigest, result.copiedEntry?.sourceDigest)
        assertEquals(
            receipt.verifiedTargetDigest,
            result.copiedEntry?.verifiedTargetDigest
        )
        assertTrue(requireNotNull(result.copiedEntry).reusedFromReceipt)
    }

    @Test
    fun `copy receipt is rejected when source fingerprint changes`() {
        val source = entry("audio.mp3", "source-audio", 4L)
        val target = entry("audio.mp3", "target-audio", 4L)
        val receipt = ManagedMigrationCopyReceipt(
            sourceReference = source.reference,
            sourceName = source.name,
            sourceSubdirectory = null,
            sourceSizeBytes = source.sizeBytes,
            sourceLastModifiedMs = source.lastModifiedMs,
            targetEntry = target,
            sourceDigest = "a".repeat(64),
            createdNew = false,
            sourceAuthoritative = false
        )

        assertFalse(
            canReuseMigrationCopyReceipt(
                receipt = receipt,
                sourceEntry = source.copy(sizeBytes = 5L),
                sourceSubdirectory = null,
                targetName = target.name,
                targetEntry = target
            )
        )
        assertFalse(
            canReuseMigrationCopyReceipt(
                receipt = receipt,
                sourceEntry = source,
                sourceSubdirectory = null,
                targetName = target.name,
                targetEntry = target.copy(lastModifiedMs = 10L)
            )
        )
    }

    @Test
    fun `source receipt fingerprint requires current provider stat`() {
        val source = entry("audio.mp3", "source-audio", 4L)
        val receipt = ManagedMigrationCopyReceipt(
            sourceReference = source.reference,
            sourceName = source.name,
            sourceSubdirectory = null,
            sourceSizeBytes = source.sizeBytes,
            sourceLastModifiedMs = source.lastModifiedMs,
            targetEntry = entry("audio.mp3", "target-audio", 4L),
            sourceDigest = "a".repeat(64),
            createdNew = false,
            sourceAuthoritative = false
        )

        assertTrue(
            isCurrentMigrationSourceFingerprint(
                receipt = receipt,
                sourceName = source.name,
                statResult = StorageLookupResult.Found(
                    sourceStat(source.name, source.sizeBytes, source.lastModifiedMs)
                )
            )
        )
        assertFalse(
            isCurrentMigrationSourceFingerprint(
                receipt = receipt,
                sourceName = source.name,
                statResult = StorageLookupResult.Found(
                    sourceStat(source.name, source.sizeBytes + 1L, source.lastModifiedMs)
                )
            )
        )
        assertFalse(
            isCurrentMigrationSourceFingerprint(
                receipt = receipt,
                sourceName = source.name,
                statResult = StorageLookupResult.Found(
                    sourceStat(source.name, source.sizeBytes, source.lastModifiedMs + 1L)
                )
            )
        )
    }

    @Test
    fun `source receipt fingerprint rejects missing provider and unknown stats`() {
        val source = entry("audio.mp3", "source-audio", 4L)
        val receipt = ManagedMigrationCopyReceipt(
            sourceReference = source.reference,
            sourceName = source.name,
            sourceSubdirectory = null,
            sourceSizeBytes = source.sizeBytes,
            sourceLastModifiedMs = source.lastModifiedMs,
            targetEntry = entry("audio.mp3", "target-audio", 4L),
            createdNew = false,
            sourceAuthoritative = false
        )
        val results = listOf<StorageLookupResult<StorageStat>>(
            StorageLookupResult.Missing,
            StorageLookupResult.PermissionLost,
            StorageLookupResult.ProviderFailure(IllegalStateException("provider")),
            StorageLookupResult.Unsupported("stat"),
            StorageLookupResult.Found(sourceStat(source.name, null, source.lastModifiedMs)),
            StorageLookupResult.Found(sourceStat(source.name, source.sizeBytes, null)),
            StorageLookupResult.Found(sourceStat(source.name, 0L, source.lastModifiedMs)),
            StorageLookupResult.Found(sourceStat(source.name, source.sizeBytes, 0L)),
            StorageLookupResult.Found(
                sourceStat(source.name, source.sizeBytes, source.lastModifiedMs)
                    .copy(displayName = "other.mp3")
            ),
            StorageLookupResult.Found(
                sourceStat(source.name, source.sizeBytes, source.lastModifiedMs)
                    .copy(isDirectory = true)
            )
        )

        results.forEach { result ->
            assertFalse(
                isCurrentMigrationSourceFingerprint(
                    receipt = receipt,
                    sourceName = source.name,
                    statResult = result
                )
            )
        }
    }

    @Test
    fun `copy queue keeps only entries with reusable receipt targets`() {
        val reusableSource = entry("reusable.mp3", "source-reusable", 4L)
        val staleSource = entry("stale.mp3", "source-stale", 4L)
        val reusableTarget = entry("reusable.mp3", "target-reusable", 4L)
        val staleTarget = entry("stale.mp3", "target-stale", 5L)
        val currentStaleTarget = staleTarget.copy(sizeBytes = 4L)
        val reusableReceipt = ManagedMigrationCopyReceipt(
            sourceReference = reusableSource.reference,
            sourceName = reusableSource.name,
            sourceSubdirectory = null,
            sourceSizeBytes = reusableSource.sizeBytes,
            sourceLastModifiedMs = reusableSource.lastModifiedMs,
            targetEntry = reusableTarget,
            sourceDigest = "a".repeat(64),
            verifiedTargetDigest = "a".repeat(64),
            createdNew = true,
            sourceAuthoritative = true
        )
        val staleReceipt = reusableReceipt.copy(
            sourceReference = staleSource.reference,
            sourceName = staleSource.name,
            sourceSizeBytes = staleSource.sizeBytes,
            targetEntry = staleTarget
        )
        val entries = listOf(
            ManagedMigrationEntry(null, reusableSource),
            ManagedMigrationEntry(null, staleSource)
        )
        val namePlan = ManagedMigrationNamePlan(
            targetNamesByReference = mapOf(
                reusableSource.reference to reusableTarget.name,
                staleSource.reference to staleTarget.name
            )
        )

        val reusable = collectReusableMigrationCopyPairs(
            entries = entries,
            persistedCopyReceipts = mapOf(
                reusableSource.reference to reusableReceipt,
                staleSource.reference to staleReceipt
            ),
            namePlan = namePlan,
            targetIndex = targetIndex(reusableTarget, currentStaleTarget)
        )

        assertEquals(listOf(reusableSource.reference), reusable.map {
            it.sourceEntry.entry.reference
        })
    }

    @Test
    fun `source deleted during copy is reported without retry or target write`() = runTest {
        val source = entry(
            name = "deleted.mp3",
            reference = "source-deleted",
            sizeBytes = 4L
        )
        val updates = mutableListOf<ManagedDownloadStorage.MigrationProgress>()
        val progressReporter = ManagedMigrationProgressReporter(
            totalFiles = 1,
            totalBytes = source.sizeBytes,
            metadataFilesTotal = 0,
            onProgress = updates::add
        )
        val result = copyWorker(openInput = { null }).copyEntry(
            context = context,
            targetRoot = targetRoot,
            migrationEntry = ManagedMigrationEntry(null, source),
            targetIndex = targetIndex(),
            namePlan = ManagedMigrationNamePlan(
                targetNamesByReference = mapOf(source.reference to source.name)
            ),
            progressTracker = progressReporter
        )
        progressReporter.finishAll()

        assertTrue(result.sourceDeleted)
        assertNull(result.copiedEntry)
        assertNull(result.error)
        assertEquals(1, updates.last().copiedFiles)
        assertEquals(source.sizeBytes, updates.last().copiedBytes)
    }

    @Test
    fun `source deleted while validating a reused target is reported as skipped`() = runTest {
        val source = entry("deleted.mp3", "source-deleted", 4L)
        val target = entry("deleted.mp3", "target-existing", 4L)
        val result = copyWorker(openInput = { null }).copyEntry(
            context = context,
            targetRoot = targetRoot,
            migrationEntry = ManagedMigrationEntry(null, source),
            targetIndex = targetIndex(target),
            namePlan = ManagedMigrationNamePlan(
                targetNamesByReference = mapOf(source.reference to target.name),
                reusedTargetsByReference = mapOf(source.reference to target)
            )
        )

        assertTrue(result.sourceDeleted)
        assertNull(result.copiedEntry)
        assertNull(result.error)
    }

    @Test
    fun `successful copy collection ignores entries deleted by the user`() = runTest {
        val source = entry("deleted.mp3", "source-deleted", 4L)
        val deleted = copyWorker(openInput = { null }).copyEntry(
            context = context,
            targetRoot = targetRoot,
            migrationEntry = ManagedMigrationEntry(null, source),
            targetIndex = targetIndex(),
            namePlan = ManagedMigrationNamePlan(
                targetNamesByReference = mapOf(source.reference to source.name)
            )
        )

        var rollbackCalled = false
        val copied = requireSuccessfulMigrationCopies(
            results = listOf(deleted),
            rollback = { rollbackCalled = true }
        )

        assertTrue(deleted.sourceDeleted)
        assertTrue(copied.isEmpty())
        assertFalse(rollbackCalled)
    }

    @Test
    fun `exhausted transient copy rolls back completed entries before propagating retry`() = runTest {
        val bytes = byteArrayOf(5, 6, 7, 8)
        val completedSource = entry("complete.mp3", "source-complete", bytes.size.toLong())
        val completedTarget = entry("complete.mp3", "target-complete", bytes.size.toLong())
        val completedResult = copyWorker(
            openInput = { ByteArrayInputStream(bytes) },
            writeRoot = { input, onProgress ->
                val copiedBytes = input.readBytes().size.toLong()
                onProgress?.invoke(copiedBytes)
                StoredWriteResult(entry = completedTarget, createdNew = true)
            }
        ).copyEntry(
            context = context,
            targetRoot = targetRoot,
            migrationEntry = ManagedMigrationEntry(null, completedSource),
            targetIndex = targetIndex(),
            namePlan = ManagedMigrationNamePlan(
                targetNamesByReference = mapOf(completedSource.reference to completedTarget.name)
            )
        )

        var attempts = 0
        val failedSource = entry("failed.mp3", "source-failed", bytes.size.toLong())
        val failedResult = copyWorker(
            openInput = {
                attempts += 1
                throw IOException("provider temporarily unavailable")
            }
        ).copyEntry(
            context = context,
            targetRoot = targetRoot,
            migrationEntry = ManagedMigrationEntry(null, failedSource),
            targetIndex = targetIndex(),
            namePlan = ManagedMigrationNamePlan(
                targetNamesByReference = mapOf(failedSource.reference to failedSource.name)
            )
        )

        assertEquals(MIGRATION_IO_MAX_ATTEMPTS, attempts)
        assertNull(failedResult.copiedEntry)
        assertTrue(requireNotNull(failedResult.error).retryable)

        var rollbackFinished = false
        val thrown = try {
            requireSuccessfulMigrationCopies(
                results = listOf(completedResult, failedResult),
                rollback = { copiedEntries ->
                    assertEquals(listOf(completedTarget), copiedEntries.map { it.copiedEntry })
                    rollbackFinished = true
                }
            )
            null
        } catch (error: ManagedDownloadMigrationException) {
            assertTrue(rollbackFinished)
            error
        }

        assertSame(failedResult.error, thrown)
    }

    @Test
    fun `metadata rewrite provider IO remains retryable but missing content is terminal`() = runTest {
        val copied = copiedEntry(
            source = entry("audio.mp3.npmeta.json", "source-metadata", 32L),
            target = entry("audio.mp3.npmeta.json", "target-metadata", 32L)
        )
        val providerFailure = migrationFinalizer(
            readText = { throw IOException("provider query failed") }
        ).rewriteMigratedMetadataReferences(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = listOf(copied)
        )

        assertEquals(1, providerFailure.failedFiles)
        assertTrue(requireNotNull(providerFailure.error).retryable)

        val missingContent = migrationFinalizer(
            readText = { null }
        ).rewriteMigratedMetadataReferences(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = listOf(copied)
        )

        assertEquals(1, missingContent.failedFiles)
        assertNull(missingContent.error)
    }

    @Test
    fun `hash provider failure remains retryable but missing target is terminal`() = runTest {
        val copied = copiedEntry(
            source = entry("audio.mp3", "source-audio", 4L),
            target = entry("audio.mp3", "target-audio", 4L),
            sourceDigest = "known-source-digest"
        )
        val providerFailure = migrationFinalizer(
            openInput = { entry ->
                throw ManagedDownloadRootProviderException(
                    entry.reference,
                    IOException("provider query failed")
                )
            }
        ).verifyMigratedEntriesDetailed(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = listOf(copied)
        )

        assertEquals(1, providerFailure.failedFiles)
        assertTrue(requireNotNull(providerFailure.error).retryable)

        val missingTarget = migrationFinalizer(
            openInput = { null }
        ).verifyMigratedEntriesDetailed(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = listOf(copied)
        )

        assertEquals(1, missingTarget.failedFiles)
        assertNull(missingTarget.error)
    }

    @Test
    fun `final target hash publishes verifying bytes and file completion`() = runTest {
        val bytes = ByteArray(150_000) { index -> (index % 239).toByte() }
        val source = entry("audio.mp3", "source-audio", bytes.size.toLong())
        val target = entry("audio.mp3", "target-audio", bytes.size.toLong())
        val copied = copiedEntry(
            source = source,
            target = target,
            sourceDigest = sha256MigrationContent(ByteArrayInputStream(bytes))
        )
        val updates = mutableListOf<ManagedDownloadStorage.MigrationProgress>()
        val progressReporter = ManagedMigrationProgressReporter(
            totalFiles = 1,
            totalBytes = bytes.size.toLong(),
            metadataFilesTotal = 0,
            onProgress = updates::add
        )

        val result = migrationFinalizer(
            openInput = { ByteArrayInputStream(bytes) }
        ).verifyMigratedEntriesDetailed(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = listOf(copied),
            progressTracker = progressReporter
        )

        assertEquals(0, result.failedFiles)
        assertNull(result.error)
        assertTrue(updates.all { progress ->
            progress.stage == ManagedDownloadStorage.MigrationStage.VERIFYING
        })
        assertEquals(1, updates.last().verificationFilesProcessed)
        assertEquals(1, updates.last().verificationFilesTotal)
        assertEquals(bytes.size.toLong(), updates.last().verifiedBytes)
        assertEquals(bytes.size.toLong(), updates.last().verificationBytesTotal)
        assertEquals(0.97f, updates.last().fraction)
        assertEquals(
            sha256MigrationContent(ByteArrayInputStream(bytes)),
            result.verifiedEntries.single().verifiedTargetDigest
        )
    }

    @Test
    fun `verified receipt rehashes current target content`() = runTest {
        val source = entry("audio.mp3", "source-audio", 4L)
        val target = entry("audio.mp3", "target-audio", 4L)
        val content = byteArrayOf(1, 2, 3, 4)
        val digest = sha256MigrationContent(ByteArrayInputStream(content))
        val copied = copiedEntry(
            source = source,
            target = target,
            sourceDigest = digest
        ).copy(verifiedTargetDigest = digest)

        val reads = mutableListOf<String>()
        val verifiedCallbacks = mutableListOf<String>()
        val result = migrationFinalizer(
            openInput = { entry ->
                reads += entry.reference
                ByteArrayInputStream(content)
            }
        ).verifyMigratedEntriesDetailed(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = listOf(copied),
            onEntryVerified = { verifiedCallbacks += it.copiedEntry.name }
        )

        assertEquals(0, result.failedFiles)
        assertEquals(digest, result.verifiedEntries.single().verifiedTargetDigest)
        assertEquals(listOf(target.name), verifiedCallbacks)
        assertEquals(listOf(target.reference), reads)
    }

    @Test
    fun `verified metadata receipt rereads current equivalent JSON`() = runTest {
        val source = entry("audio.mp3.npmeta.json", "source-metadata", 32L)
        val target = entry("audio.mp3.npmeta.json", "target-metadata", 32L)
        val reads = mutableListOf<String>()
        val copied = copiedEntry(source, target).copy(
            verifiedTargetDigest = "a".repeat(64)
        )

        val result = migrationFinalizer(
            readText = { reference ->
                reads += reference
                when (reference) {
                    source.reference -> "{\"title\":\"original\"}"
                    target.reference -> "{\"title\":\"changed\"}"
                    else -> error("unexpected metadata reference: $reference")
                }
            }
        ).verifyMigratedEntriesDetailed(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = listOf(copied)
        )

        assertEquals(1, result.failedFiles)
        assertTrue(result.verifiedEntries.isEmpty())
        assertEquals(listOf(source.reference, target.reference), reads)
    }

    @Test
    fun `changed receipt target is preserved when verification fails`() = runTest {
        val source = entry("audio.mp3", "source-audio", 4L)
        val target = entry("audio.mp3", "target-audio", 4L)
        val expectedBytes = byteArrayOf(1, 2, 3, 4)
        val changedBytes = byteArrayOf(4, 3, 2, 1)
        val digest = sha256MigrationContent(ByteArrayInputStream(expectedBytes))
        val receipt = ManagedMigrationCopyReceipt(
            sourceReference = source.reference,
            sourceName = source.name,
            sourceSubdirectory = null,
            sourceSizeBytes = source.sizeBytes,
            sourceLastModifiedMs = source.lastModifiedMs,
            targetEntry = target,
            sourceDigest = digest,
            verifiedTargetDigest = digest,
            createdNew = true,
            sourceAuthoritative = true
        )
        val copied = receipt.toCopiedMigrationEntry(
            original = ManagedMigrationEntry(null, source),
            reusedFromReceipt = true
        )
        val reads = mutableListOf<String>()
        var deleteCalls = 0
        val finalizer = migrationFinalizer(
            openInput = { entry ->
                reads += entry.reference
                ByteArrayInputStream(changedBytes)
            },
            deleteReference = {
                deleteCalls += 1
                StorageMutationResult.Deleted
            }
        )

        val verification = finalizer.verifyMigratedEntriesDetailed(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = listOf(copied)
        )

        assertTrue(copied.reusedFromReceipt)
        assertEquals(1, verification.failedFiles)
        assertEquals(listOf(target.reference), reads)
        assertEquals(
            0,
            finalizer.rollbackMigratedEntries(
                context = context,
                copiedEntries = listOf(copied),
                targetRoot = targetRoot
            )
        )
        assertEquals(0, deleteCalls)
    }

    @Test
    fun `SAF target layout allows unrelated duplicate canonical names`() {
        val expected = listOf(
            ManagedMigrationTargetLayoutEntry(null, "Song.mp3", "provider\u0000target")
        )
        val observed = listOf(
            ManagedMigrationTargetLayoutEntry(null, "Song.mp3", "provider\u0000target"),
            ManagedMigrationTargetLayoutEntry(null, "song.mp3", "provider\u0000external")
        )

        val detail = validateMigrationTargetLayout(expected, observed)

        assertNull(detail)
    }

    @Test
    fun `SAF target layout rejects the planned identity appearing twice`() {
        val expected = listOf(
            ManagedMigrationTargetLayoutEntry(null, "Song.mp3", "provider\u0000target")
        )
        val observed = listOf(
            ManagedMigrationTargetLayoutEntry(null, "Song.mp3", "provider\u0000target"),
            ManagedMigrationTargetLayoutEntry(null, "song.mp3", "provider\u0000target")
        )

        val detail = validateMigrationTargetLayout(expected, observed)

        assertTrue(requireNotNull(detail).contains("多个相同文档"))
    }

    @Test
    fun `SAF target layout rejects a planned name pointing at different documents`() {
        val expected = listOf(
            ManagedMigrationTargetLayoutEntry(null, "song.mp3", "provider\u0000first"),
            ManagedMigrationTargetLayoutEntry(null, "song.mp3", "provider\u0000second")
        )
        val observed = listOf(
            ManagedMigrationTargetLayoutEntry(null, "song.mp3", "provider\u0000first")
        )

        val detail = validateMigrationTargetLayout(expected, observed)

        assertTrue(requireNotNull(detail).contains("多个目标文档"))
    }

    @Test
    fun `SAF target layout rejects a planned name without a document identity`() {
        val expected = listOf(
            ManagedMigrationTargetLayoutEntry(null, "song.mp3", "")
        )
        val observed = listOf(
            ManagedMigrationTargetLayoutEntry(null, "song.mp3", "provider\u0000target")
        )

        val detail = validateMigrationTargetLayout(expected, observed)

        assertTrue(requireNotNull(detail).contains("多个目标文档"))
    }

    @Test
    fun `SAF target layout rejects a receipt target moved from Covers to root`() {
        val expected = listOf(
            ManagedMigrationTargetLayoutEntry("Covers", "cover.jpg", "provider\u0000cover")
        )
        val observed = listOf(
            ManagedMigrationTargetLayoutEntry(null, "cover.jpg", "provider\u0000cover")
        )

        assertTrue(requireNotNull(validateMigrationTargetLayout(expected, observed)).contains("名称不唯一"))
    }

    @Test
    fun `SAF target layout accepts one exact document per canonical planned name`() {
        val expected = listOf(
            ManagedMigrationTargetLayoutEntry(null, "Song.mp3", "provider\u0000audio"),
            ManagedMigrationTargetLayoutEntry("Covers", "cover.jpg", "provider\u0000cover")
        )
        val observed = listOf(
            ManagedMigrationTargetLayoutEntry(null, "song.mp3", "provider\u0000audio"),
            ManagedMigrationTargetLayoutEntry("Covers", "cover.jpg", "provider\u0000cover")
        )

        assertNull(validateMigrationTargetLayout(expected, observed))
    }

    private fun copyWorker(
        openInput: (ManagedDownloadStorage.StoredEntry) -> InputStream?,
        writeRoot: ((InputStream, ((Long) -> Unit)?) -> StoredWriteResult)? = null
    ): ManagedDownloadMigrationCopyWorker {
        return ManagedDownloadMigrationCopyWorker(
            tag = "MigrationCopyWorkerTest",
            entryReader = InputStreamManagedMigrationEntryReader { _, entry ->
                openInput(entry)
            },
            mimeTypeFor = { "audio/mpeg" },
            writeRootStream = { _, _, _, _, input, _, _, _, onProgress ->
                requireNotNull(writeRoot) { "writeRootStream must not be called" }(
                    input,
                    onProgress
                )
            },
            writeSubdirectoryStream = { _, _, _, _, _, _, _, _, _, _ ->
                error("writeSubdirectoryStream must not be called")
            }
        )
    }

    private fun migrationFinalizer(
        readText: (String) -> String? = { "{}" },
        openInput: (ManagedDownloadStorage.StoredEntry) -> InputStream? = {
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        },
        deleteReference: () -> StorageMutationResult = { StorageMutationResult.Deleted }
    ): ManagedDownloadMigrationFinalizer {
        return ManagedDownloadMigrationFinalizer(
            tag = "MigrationCopyWorkerTest",
            rewriteParallelism = { 1 },
            deleteParallelism = { 1 },
            readText = { _, reference -> readText(reference) },
            entryReader = InputStreamManagedMigrationEntryReader { _, entry ->
                openInput(entry)
            },
            writeRootText = { _, _, _, _ -> error("writeRootText must not be called") },
            deleteReference = { _, _, _ -> deleteReference() },
            rewriteMetadataReferences = { content, _ -> content }
        )
    }

    private fun failingCloseReader(
        closeFailure: IOException
    ): InputStreamManagedMigrationEntryReader {
        return InputStreamManagedMigrationEntryReader { _, _ ->
            object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
                override fun close() {
                    super.close()
                    throw closeFailure
                }
            }
        }
    }

    private fun targetIndex(
        vararg rootEntries: ManagedDownloadStorage.StoredEntry
    ): ManagedMigrationTargetIndex {
        return ManagedMigrationTargetIndex(
            rootEntriesByName = rootEntries.associateBy { it.name },
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = emptyMap()
        )
    }

    private fun copiedEntry(
        source: ManagedDownloadStorage.StoredEntry,
        target: ManagedDownloadStorage.StoredEntry,
        sourceDigest: String? = null
    ): CopiedMigrationEntry {
        return CopiedMigrationEntry(
            original = ManagedMigrationEntry(subdirectory = null, entry = source),
            copiedEntry = target,
            createdNew = true,
            sourceDigest = sourceDigest
        )
    }

    private fun entry(
        name: String,
        reference: String,
        sizeBytes: Long
    ): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = reference,
            localFilePath = null,
            sizeBytes = sizeBytes,
            lastModifiedMs = 1L
        )
    }

    private fun sourceStat(
        name: String,
        sizeBytes: Long?,
        lastModifiedMs: Long?
    ): StorageStat {
        return StorageStat(
            reference = StorageReference.FileRef(name),
            displayName = name,
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs,
            isDirectory = false
        )
    }
}
