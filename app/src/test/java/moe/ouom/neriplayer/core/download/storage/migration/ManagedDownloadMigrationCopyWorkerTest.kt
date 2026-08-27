package moe.ouom.neriplayer.core.download.storage.migration

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.MIGRATION_IO_MAX_ATTEMPTS
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
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
    }

    private fun copyWorker(
        openInput: (ManagedDownloadStorage.StoredEntry) -> InputStream?,
        writeRoot: ((InputStream, ((Long) -> Unit)?) -> StoredWriteResult)? = null
    ): ManagedDownloadMigrationCopyWorker {
        return ManagedDownloadMigrationCopyWorker(
            tag = "MigrationCopyWorkerTest",
            openInputStream = { _, entry -> openInput(entry) },
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
        }
    ): ManagedDownloadMigrationFinalizer {
        return ManagedDownloadMigrationFinalizer(
            tag = "MigrationCopyWorkerTest",
            rewriteParallelism = { 1 },
            deleteParallelism = { 1 },
            readText = { _, reference -> readText(reference) },
            openInputStream = { _, entry -> openInput(entry) },
            writeRootText = { _, _, _, _ -> error("writeRootText must not be called") },
            deleteReference = { _, _, _ -> StorageMutationResult.Deleted },
            rewriteMetadataReferences = { content, _ -> content }
        )
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
}
