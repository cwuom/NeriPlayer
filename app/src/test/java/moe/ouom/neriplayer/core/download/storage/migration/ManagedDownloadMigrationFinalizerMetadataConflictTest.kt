package moe.ouom.neriplayer.core.download.storage.migration

import android.content.Context
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

class ManagedDownloadMigrationFinalizerMetadataConflictTest {

    @Test
    fun `existing metadata target is rewritten from source after receipt reuse`() = runBlocking {
        assertConflictingMetadataIsRewritten(reusedFromReceipt = true)
    }

    @Test
    fun `non authoritative existing metadata target is rewritten from source`() = runBlocking {
        assertConflictingMetadataIsRewritten(reusedFromReceipt = false)
    }

    private suspend fun assertConflictingMetadataIsRewritten(
        reusedFromReceipt: Boolean
    ) {
        val directory = Files.createTempDirectory("migration-metadata-conflict").toFile()
        try {
            val sourceAudio = entry(directory, "source", "track.mp3")
            val targetAudio = entry(directory, "target", "track.mp3")
            val sourceMetadata = entry(directory, "source", "track.mp3.npmeta.json")
            val targetMetadata = entry(directory, "target", "track.mp3.npmeta.json")
            sourceMetadata.file.writeText(
                JSONObject()
                    .put("title", "source title")
                    .put("mediaUri", sourceAudio.file.toURI().toString())
                    .toString()
            )
            targetMetadata.file.writeText(JSONObject().put("title", "target title").toString())

            val copiedEntries = listOf(
                CopiedMigrationEntry(
                    original = ManagedMigrationEntry(null, sourceAudio.entry),
                    copiedEntry = targetAudio.entry,
                    createdNew = false
                ),
                CopiedMigrationEntry(
                    original = ManagedMigrationEntry(null, sourceMetadata.entry),
                    copiedEntry = targetMetadata.entry,
                    createdNew = false,
                    sourceAuthoritative = false,
                    verifiedTargetDigest = "a".repeat(64),
                    reusedFromReceipt = reusedFromReceipt
                )
            )
            val finalizer = ManagedDownloadMigrationFinalizer(
                tag = "ManagedDownloadMigrationFinalizerMetadataConflictTest",
                rewriteParallelism = { 1 },
                deleteParallelism = { 1 },
                readText = { _, reference -> File(reference).readText() },
                entryReader = InputStreamManagedMigrationEntryReader { _, entry ->
                    File(entry.reference).inputStream()
                },
                writeRootText = { _, _, _, content ->
                    targetMetadata.file.writeText(content)
                    targetMetadata.entry.copy(
                        sizeBytes = targetMetadata.file.length(),
                        lastModifiedMs = targetMetadata.file.lastModified()
                    )
                },
                deleteReference = { _, _, _ -> StorageMutationResult.Missing },
                rewriteMetadataReferences = ManagedDownloadStorage::rewriteManagedMetadataReferences
            )

            val result = finalizer.rewriteMigratedMetadataReferences(
                context = mock(Context::class.java),
                targetRoot = ManagedDownloadRootHandle.FileRoot(directory),
                copiedEntries = copiedEntries
            )

            assertEquals(0, result.failedFiles)
            assertEquals("source title", JSONObject(targetMetadata.file.readText()).getString("title"))
            assertEquals(
                targetAudio.file.toURI().toString(),
                JSONObject(targetMetadata.file.readText()).getString("mediaUri")
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun entry(
        directory: File,
        child: String,
        name: String
    ): TestEntry {
        val childDirectory = File(directory, child).apply { mkdirs() }
        val file = File(childDirectory, name)
        return TestEntry(
            file = file,
            entry = ManagedDownloadStorage.StoredEntry(
                name = name,
                reference = file.absolutePath,
                mediaUri = file.toURI().toString(),
                localFilePath = file.absolutePath,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified()
            )
        )
    }

    private data class TestEntry(
        val file: File,
        val entry: ManagedDownloadStorage.StoredEntry
    )
}
