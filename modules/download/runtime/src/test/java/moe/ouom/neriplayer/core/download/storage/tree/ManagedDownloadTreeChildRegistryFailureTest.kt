package moe.ouom.neriplayer.core.download.storage.tree

import java.io.File
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

class ManagedDownloadTreeChildRegistryFailureTest {
    @Test
    fun `tree child registry does not use parent name lookup as resolution evidence`() {
        val source = File(
            "src/main/java/moe/ouom/neriplayer/core/download/storage/tree/" +
                "ManagedDownloadTreeChildRegistry.kt"
        ).readText()

        assertFalse(source.contains("parent.findFile"))
    }

    @Test
    fun `tree child resolution does not synthesize a wrapper from a name`() {
        val child = QueriedTreeChild(
            name = "Covers",
            documentUri = mock(android.net.Uri::class.java),
            sizeBytes = 0L,
            lastModifiedMs = 0L,
            isDirectory = true
        )

        assertNull(
            resolveTreeChildDocumentFile(
                child = child,
                treeDocumentFile = { null },
                singleDocumentFile = { null }
            )
        )
    }
}
