package moe.ouom.neriplayer.core.download.storage.operation.content

import androidx.documentfile.provider.DocumentFile
import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.MIGRATION_COPY_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.MIGRATION_TREE_COPY_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

class ManagedDownloadMigrationCopyParallelismTest {
    @Test
    fun `file to file copies keep file parallelism while any tree root lowers it`() {
        val fileRoot = ManagedDownloadRootHandle.FileRoot(File("/storage/emulated/0/Music/NeriPlayer"))
        val treeRoot = ManagedDownloadRootHandle.TreeRoot(mock(DocumentFile::class.java))

        assertEquals(
            MIGRATION_COPY_PARALLELISM,
            ManagedDownloadStorage.migrationCopyParallelism(fileRoot, fileRoot)
        )
        assertEquals(
            MIGRATION_TREE_COPY_PARALLELISM,
            ManagedDownloadStorage.migrationCopyParallelism(treeRoot, fileRoot)
        )
        assertEquals(
            MIGRATION_TREE_COPY_PARALLELISM,
            ManagedDownloadStorage.migrationCopyParallelism(fileRoot, treeRoot)
        )
    }
}
