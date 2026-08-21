package moe.ouom.neriplayer.core.download.storage.tree

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class ManagedDownloadTreeDirectoriesTest {

    @Test
    fun `external storage child document id keeps canonical sidecar directory name`() {
        assertEquals(
            "primary:neriplayer-download/Covers",
            ManagedDownloadTreeNaming.externalStorageChildDocumentId(
                parentDocumentId = "primary:neriplayer-download",
                displayName = "Covers"
            )
        )
        assertNull(
            ManagedDownloadTreeNaming.externalStorageChildDocumentId(
                parentDocumentId = "primary:neriplayer-download",
                displayName = "Lyrics/unsafe"
            )
        )
    }

    @Test
    fun `exact tree stored name accepts canonically equivalent unicode`() {
        assertEquals(
            true,
            ManagedDownloadTreeNaming.isExactTreeStoredName(
                actualName = "Café.lrc",
                expectedName = "Cafe\u0301.lrc"
            )
        )
    }

    @Test
    fun `SAF nomedia failure does not block sidecar directory preparation`() {
        val context = mock(Context::class.java)
        val parent = mock(DocumentFile::class.java)
        `when`(parent.uri).thenReturn(Uri.parse("content://example/tree/root"))
        `when`(parent.listFiles()).thenReturn(emptyArray())
        val registry = ManagedDownloadTreeChildRegistry(
            writeCacheValidateIntervalMs = 0L,
            treeCacheValidateIntervalMs = 0L,
            treeWriteCacheValidateIntervalMs = 0L,
            onTreeQueryFailed = {}
        )
        val directories = ManagedDownloadTreeDirectories(
            treeChildRegistry = registry,
            tag = "test"
        )

        directories.ensureManagedMediaScanIsolation(
            context = context,
            subdirectory = "Covers",
            directory = parent
        )
    }

    @Test
    fun `directory child keeps a writable tree document wrapper`() {
        val child = QueriedTreeChild(
            name = "Lyrics",
            documentUri = mock(Uri::class.java),
            sizeBytes = 0L,
            lastModifiedMs = 0L,
            isDirectory = true
        )
        val treeDocument = mock(DocumentFile::class.java)
        val singleDocument = mock(DocumentFile::class.java)

        assertSame(
            treeDocument,
            resolveTreeChildDocumentFile(
                child = child,
                treeDocumentFile = { treeDocument },
                singleDocumentFile = { singleDocument }
            )
        )
    }

    @Test
    fun `directory child does not fall back to a single document wrapper`() {
        val child = QueriedTreeChild(
            name = "Lyrics",
            documentUri = mock(Uri::class.java),
            sizeBytes = 0L,
            lastModifiedMs = 0L,
            isDirectory = true
        )

        assertNull(
            resolveTreeChildDocumentFile(
                child = child,
                treeDocumentFile = { null },
                singleDocumentFile = { mock(DocumentFile::class.java) }
            )
        )
    }
}
