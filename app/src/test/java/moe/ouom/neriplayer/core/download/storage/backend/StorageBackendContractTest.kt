package moe.ouom.neriplayer.core.download.storage.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StorageBackendContractTest {
    @Test
    fun `opaque document id is returned byte for byte`() {
        val documentId = "Primary:Music/Track%2F7"

        assertEquals(documentId, requireOpaqueDocumentId(documentId))
    }

    @Test
    fun `missing document id is a provider contract failure`() {
        assertThrows(IllegalStateException::class.java) {
            requireOpaqueDocumentId(null)
        }
    }

    @Test
    fun `managed storage does not bypass typed backend for reference reads`() {
        val source = readSource(
            "src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        )

        assertFalse(source.contains("ManagedDownloadReferenceIo.readText"))
        assertFalse(source.contains("ManagedDownloadReferenceIo.inspect"))
    }

    @Test
    fun `reference delete executor does not perform raw file deletion`() {
        val source = readSource(
            "src/main/java/moe/ouom/neriplayer/core/download/storage/delete/" +
                "ManagedDownloadReferenceDeleteExecutor.kt"
        )

        assertFalse(source.contains("File("))
        assertFalse(source.contains(".delete()"))
    }

    @Test
    fun `commit writers do not bypass typed backend for SAF output`() {
        val writer = readSource(
            "src/main/java/moe/ouom/neriplayer/core/download/storage/commit/" +
                "ManagedDownloadStorageCommitWriter.kt"
        )
        val committer = readSource(
            "src/main/java/moe/ouom/neriplayer/core/download/storage/commit/" +
                "ManagedDownloadTreeFileCommitter.kt"
        )

        assertFalse(writer.contains("contentResolver.openOutputStream"))
        assertFalse(committer.contains("contentResolver.openOutputStream"))
    }

    @Test
    fun `normal file sidecar commits use the recoverable file backend`() {
        val writer = readSource(
            "src/main/java/moe/ouom/neriplayer/core/download/storage/commit/" +
                "ManagedDownloadStorageCommitWriter.kt"
        )

        assertTrue(writer.contains("FileStorageBackend(root).writeRecoverable"))
        assertFalse(writer.contains("target.outputStream()"))
        assertFalse(writer.contains("target.writeBytes("))
    }

    @Test
    fun `SAF replacement requires confirmed cleanup and backup recovery`() {
        val backend = readSource(
            "src/main/java/moe/ouom/neriplayer/core/download/storage/backend/" +
                "StorageBackend.kt"
        )

        assertTrue(backend.contains("rollbackSafReplacement("))
        assertTrue(backend.contains("deleteSafDocumentAndConfirm("))
        assertTrue(backend.contains("restoreBackupAndConfirm("))
        assertTrue(backend.contains("confirmSafDocumentName("))
        assertTrue(backend.contains("reconcileSafBackupBeforeWrite("))
        assertTrue(backend.contains("safBackupName("))
        assertTrue(backend.contains("val temporaryCleanupError =\n                                deleteSafDocumentAndConfirm(temporaryUri)"))
        assertFalse(backend.contains("private fun restoreBackup("))
    }

    private fun readSource(relativePath: String): String {
        return sequenceOf(
            File(relativePath),
            File("../$relativePath"),
            File("../../$relativePath")
        ).firstOrNull(File::isFile)?.readText()
            ?: throw IllegalStateException("source file not found: $relativePath")
    }
}
