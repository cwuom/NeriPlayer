package moe.ouom.neriplayer.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RefactoredSourceFamilyResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `collects only declared nested implementation directories`() {
        val globalRoot = write("GlobalDownloadManager.kt", "global_root_marker")
        write("manager/runtime/GlobalDownloadManagerRuntime.kt", "global_nested_marker")
        write("storage/commit/GlobalDownloadManagerCommitWriter.kt", "global_unrelated_marker")

        val storageRoot = write("ManagedDownloadStorage.kt", "storage_root_marker")
        write("storage/facade/ManagedDownloadStorageFacadeSetup.kt", "storage_facade_marker")
        write("storage/operation/ManagedDownloadStorageRecovery.kt", "storage_operation_marker")
        write("storage/commit/ManagedDownloadStorageCommitWriter.kt", "storage_unrelated_marker")

        val globalSource = RefactoredSourceFamilyResolver.resolve(globalRoot).readText()
        assertTrue(globalSource.contains("global_root_marker"))
        assertTrue(globalSource.contains("global_nested_marker"))
        assertFalse(globalSource.contains("global_unrelated_marker"))

        val storageSource = RefactoredSourceFamilyResolver.resolve(storageRoot).readText()
        assertTrue(storageSource.contains("storage_root_marker"))
        assertTrue(storageSource.contains("storage_facade_marker"))
        assertTrue(storageSource.contains("storage_operation_marker"))
        assertFalse(storageSource.contains("storage_unrelated_marker"))
    }

    private fun write(relativePath: String, content: String): File {
        val file = File(temporaryFolder.root, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }
}
