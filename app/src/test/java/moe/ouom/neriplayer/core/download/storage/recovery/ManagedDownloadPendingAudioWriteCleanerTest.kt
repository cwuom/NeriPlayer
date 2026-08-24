package moe.ouom.neriplayer.core.download.storage.recovery

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.nio.file.Files
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ManagedDownloadPendingAudioWriteCleanerTest {
    @Test
    fun `provider failure is not counted as a missing pending audio`() {
        val context = mock(Context::class.java)
        val tree = mock(DocumentFile::class.java)
        val registry = mock(ManagedDownloadTreeChildRegistry::class.java)
        val child = QueriedTreeChild(
            name = "song.mp3$PENDING_AUDIO_WRITE_MARKER.1.pending",
            documentUri = mock(Uri::class.java),
            sizeBytes = 10L,
            lastModifiedMs = 1L,
            isDirectory = false
        )
        `when`(tree.uri).thenReturn(mock(Uri::class.java))
        `when`(registry.queryTreeChildren(context, tree)).thenReturn(listOf(child))

        val result = ManagedDownloadPendingAudioWriteCleaner.cleanup(
            context = context,
            root = ManagedDownloadRootHandle.TreeRoot(tree),
            names = ManagedDownloadPendingAudioWriteNames(),
            treeChildRegistry = registry,
            deleteTreeChild = {
                StorageMutationResult.ProviderFailure(
                    IllegalStateException("provider unavailable")
                )
            },
            tag = "test"
        )

        assertEquals(0, result.cleanedCount)
        assertEquals(1, result.failedCount)
    }

    @Test
    fun `file cleanup counts only confirmed deletion`() {
        val directory = createTempDirectory()
        try {
            File(directory, "song.mp3$PENDING_AUDIO_WRITE_MARKER.1.pending").writeText("partial")
            val result = ManagedDownloadPendingAudioWriteCleaner.cleanup(
                context = mock(Context::class.java),
                root = ManagedDownloadRootHandle.FileRoot(directory),
                names = ManagedDownloadPendingAudioWriteNames(),
                treeChildRegistry = mock(ManagedDownloadTreeChildRegistry::class.java),
                deleteTreeChild = { StorageMutationResult.OutOfScope },
                tag = "test"
            )

            assertEquals(1, result.cleanedCount)
            assertEquals(0, result.failedCount)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createTempDirectory(): File {
        return Files.createTempDirectory("neriplayer-pending-cleaner").toFile()
    }
}
