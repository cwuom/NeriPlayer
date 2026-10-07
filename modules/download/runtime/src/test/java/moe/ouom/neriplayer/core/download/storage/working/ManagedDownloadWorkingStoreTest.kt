package moe.ouom.neriplayer.core.download.storage.working

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedDownloadWorkingStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `working files are grouped by sanitized operation id inside the staging root`() {
        val cacheDir = tempFolder.newFolder("files")
        val stagingDir = File(cacheDir, "download_staging")

        val rootFile = ManagedDownloadWorkingStore.createWorkingFile(cacheDir, "song-key", "Song.m4a")
        val blankOperationFile = ManagedDownloadWorkingStore.createWorkingFile(
            cacheDir,
            "song-key",
            "Song.m4a",
            operationId = "   "
        )
        val operationFile = ManagedDownloadWorkingStore.createWorkingFile(
            cacheDir,
            "song-key",
            "Song.m4a",
            operationId = " batch/42 "
        )

        assertEquals(stagingDir, rootFile.parentFile)
        assertEquals(rootFile, blankOperationFile)
        assertEquals(File(stagingDir, "batch_42"), operationFile.parentFile)
        assertTrue(File(stagingDir, "batch_42").isDirectory)
        assertEquals("npdl_batch_42_Song.m4a.download", operationFile.name)
        assertFalse(operationFile.exists())
    }

    @Test
    fun `resume metadata is preserved only beside a fresh working file with a readable song`() {
        val stagingDir = tempFolder.newFolder("download_staging")
        val nowMs = System.currentTimeMillis()
        val workingFile = workingFile(stagingDir, "song-1", "Song.m4a").apply {
            writeText("partial")
            setLastModified(nowMs - 1_000L)
        }
        val metadata = ManagedDownloadWorkingStore.buildWorkingResumeMetadataFile(workingFile)
        assertTrue(ManagedDownloadWorkingStore.saveWorkingResumeMetadata(workingFile, song(1L)))
        metadata.setLastModified(nowMs - 1_000L)
        val emptyWorkingFile = workingFile(stagingDir, "song-2", "Empty.m4a").apply { createNewFile() }
        val emptyWorkingMetadata = ManagedDownloadWorkingStore.buildWorkingResumeMetadataFile(emptyWorkingFile)
        ManagedDownloadWorkingStore.saveWorkingResumeMetadata(emptyWorkingFile, song(2L))
        val brokenWorkingFile = workingFile(stagingDir, "song-3", "Broken.m4a").apply { writeText("partial") }
        val brokenMetadata = ManagedDownloadWorkingStore.buildWorkingResumeMetadataFile(brokenWorkingFile)
            .apply { writeText("{") }
        val unnamedMetadata = File(stagingDir, ".resume.json").apply { writeText("{}") }
        val metadataDirectory = File(stagingDir, "folder.resume.json").apply { mkdirs() }

        assertTrue(ManagedDownloadWorkingStore.shouldPreserveWorkingResumeMetadataForResume(metadata, nowMs))
        assertFalse(ManagedDownloadWorkingStore.shouldPreserveWorkingResumeMetadataForResume(workingFile, nowMs))
        assertFalse(
            ManagedDownloadWorkingStore.shouldPreserveWorkingResumeMetadataForResume(
                metadata,
                nowMs + TimeUnit.DAYS.toMillis(8)
            )
        )
        assertFalse(ManagedDownloadWorkingStore.shouldPreserveWorkingResumeMetadataForResume(emptyWorkingMetadata, nowMs))
        assertFalse(ManagedDownloadWorkingStore.shouldPreserveWorkingResumeMetadataForResume(brokenMetadata, nowMs))
        assertFalse(ManagedDownloadWorkingStore.shouldPreserveWorkingResumeMetadataForResume(unnamedMetadata, nowMs))
        assertFalse(ManagedDownloadWorkingStore.shouldPreserveWorkingResumeMetadataForResume(metadataDirectory, nowMs))
    }

    @Test
    fun `staging cleanup leaves missing empty and unknown-only staging roots untouched`() {
        val missing = File(tempFolder.root, "missing/download_staging")
        val empty = tempFolder.newFolder("empty", "download_staging")
        val unknownOnly = tempFolder.newFolder("unknown", "download_staging")
        val notes = File(unknownOnly, "notes.txt").apply { writeText("keep user data") }
        val unknownOperation = File(unknownOnly, "operation-a").apply { mkdirs() }
        val directoryOnly = tempFolder.newFolder("directory-only", "download_staging")
        val emptyOperation = File(directoryOnly, "operation-b").apply { mkdirs() }

        assertEquals(ManagedDownloadStorage.StartupRecoveryResult(), ManagedDownloadWorkingStore.cleanupStagingFilesInDirectory(missing))
        assertEquals(ManagedDownloadStorage.StartupRecoveryResult(), ManagedDownloadWorkingStore.cleanupStagingFilesInDirectory(empty))
        assertEquals(
            ManagedDownloadStorage.StartupRecoveryResult(),
            ManagedDownloadWorkingStore.cleanupStagingFilesInDirectory(unknownOnly)
        )
        assertEquals(
            ManagedDownloadStorage.StartupRecoveryResult(),
            ManagedDownloadWorkingStore.cleanupStagingFilesInDirectory(directoryOnly)
        )
        assertFalse(missing.exists())
        assertEquals("keep user data", notes.readText())
        assertTrue(unknownOperation.isDirectory)
        assertTrue(emptyOperation.isDirectory)
    }

    @Test
    fun `pending cleanup deletes orphan metadata whose song key is requested`() {
        val stagingDir = tempFolder.newFolder("download_staging")
        val target = song(71L)
        val kept = song(72L)
        val orphanOperation = File(stagingDir, "operation-a").apply { mkdirs() }
        val orphanWorkingFile = File(orphanOperation, workingFileName(target.stableKey(), "Target.m4a"))
        ManagedDownloadWorkingStore.saveWorkingResumeMetadata(orphanWorkingFile, target)
        val orphanMetadata = ManagedDownloadWorkingStore.buildWorkingResumeMetadataFile(orphanWorkingFile)
        val rootWorkingFile = File(stagingDir, workingFileName(target.stableKey(), "Root.m4a"))
        ManagedDownloadWorkingStore.saveWorkingResumeMetadata(rootWorkingFile, target)
        val rootMetadata = ManagedDownloadWorkingStore.buildWorkingResumeMetadataFile(rootWorkingFile)
        val sharedOperation = File(stagingDir, "operation-c").apply { mkdirs() }
        val sharedWorkingFile = File(sharedOperation, workingFileName(target.stableKey(), "Shared.m4a"))
        ManagedDownloadWorkingStore.saveWorkingResumeMetadata(sharedWorkingFile, target)
        val sharedMetadata = ManagedDownloadWorkingStore.buildWorkingResumeMetadataFile(sharedWorkingFile)
        val sharedNotes = File(sharedOperation, "notes.txt").apply { writeText("keep user data") }
        val keptWorkingFile = File(stagingDir, workingFileName(kept.stableKey(), "Kept.m4a"))
        ManagedDownloadWorkingStore.saveWorkingResumeMetadata(keptWorkingFile, kept)
        val keptMetadata = ManagedDownloadWorkingStore.buildWorkingResumeMetadataFile(keptWorkingFile)

        val deletedKeys = ManagedDownloadWorkingStore.deletePendingWorkingDownloadArtifactsInDirectory(
            stagingDir,
            listOf(target.stableKey(), "   ")
        )

        assertEquals(setOf(target.stableKey()), deletedKeys)
        assertFalse(orphanMetadata.exists())
        assertFalse(orphanOperation.exists())
        assertFalse(rootMetadata.exists())
        assertTrue(stagingDir.isDirectory)
        assertFalse(sharedMetadata.exists())
        assertTrue(sharedNotes.exists())
        assertTrue(keptMetadata.exists())
    }

    @Test
    fun `pending cleanup keeps hashed artifacts that cannot prove ownership`() {
        val stagingDir = tempFolder.newFolder("download_staging")
        val target = song(81L)
        val operationDirectory = File(stagingDir, "operation-b").apply { mkdirs() }
        val unownedWorkingFile = File(operationDirectory, workingFileName(target.stableKey(), "Target.m4a"))
            .apply { writeText("partial") }
        val unownedCheckpoint = ManagedDownloadWorkingStore.buildWorkingHlsCheckpointFile(unownedWorkingFile)
            .apply { writeText("{}") }
        val nestedDirectory = File(operationDirectory, "nested").apply { mkdirs() }
        val missingHash = File(stagingDir, "npdl_orphan").apply { writeText("x") }
        val blankHash = File(stagingDir, "npdl__Target.m4a.download").apply { writeText("x") }
        val danglingLink = Files.createSymbolicLink(
            File(stagingDir, workingFileName(target.stableKey(), "Link.m4a")).toPath(),
            File(stagingDir, "missing-target").toPath()
        )

        val deletedKeys = ManagedDownloadWorkingStore.deletePendingWorkingDownloadArtifactsInDirectory(
            stagingDir,
            setOf(target.stableKey())
        )

        assertTrue(deletedKeys.isEmpty())
        assertTrue(unownedWorkingFile.exists())
        assertTrue(unownedCheckpoint.exists())
        assertTrue(nestedDirectory.isDirectory)
        assertTrue(missingHash.exists())
        assertTrue(blankHash.exists())
        assertTrue(Files.isSymbolicLink(danglingLink))
    }

    @Test
    fun `pending cleanup ignores blank song keys`() {
        val stagingDir = tempFolder.newFolder("download_staging")
        val unrelated = File(stagingDir, workingFileName("", "Song.m4a")).apply { writeText("partial") }

        assertTrue(
            ManagedDownloadWorkingStore.deletePendingWorkingDownloadArtifactsInDirectory(
                stagingDir,
                listOf("", "   ")
            ).isEmpty()
        )
        assertTrue(unrelated.exists())
    }

    private fun workingFile(stagingDir: File, songKey: String, fileName: String): File {
        return File(stagingDir, workingFileName(songKey, fileName))
    }

    private fun workingFileName(songKey: String, fileName: String): String {
        return ManagedDownloadWorkingStore.buildWorkingFileName(songKey = songKey, fileName = fileName)
    }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        durationMs = 1_000L,
        coverUrl = null,
        mediaUri = "https://example.com/$id"
    )
}
