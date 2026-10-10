package moe.ouom.neriplayer.core.download.storage.operation.content

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class ManagedDownloadSnapshotRootKeyTest {
    @Test
    fun `file roots are keyed by their absolute path`() {
        val directory = File("/storage/emulated/0/Music/NeriPlayer")

        assertEquals(
            "file:${directory.absolutePath}",
            ManagedDownloadStorage.rootKeyForResolvedRoot(ManagedDownloadRootHandle.FileRoot(directory))
        )
    }

    @Test
    fun `tree roots share one key across equivalent uri spellings`() {
        val key = ManagedDownloadStorage.rootKeyForResolvedRoot(
            treeRoot("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        )

        assertEquals("tree:tree:com.android.externalstorage.documents:primary:Music", key)
        assertEquals(
            key,
            ManagedDownloadStorage.rootKeyForResolvedRoot(
                treeRoot("content://com.android.externalstorage.documents/tree/primary%3AMusic/?mode=write#top")
            )
        )
        assertNotEquals(
            key,
            ManagedDownloadStorage.rootKeyForResolvedRoot(
                treeRoot("content://com.android.externalstorage.documents/tree/primary%3APodcasts")
            )
        )
    }

    private fun treeRoot(uriString: String): ManagedDownloadRootHandle.TreeRoot {
        val uri = mock(Uri::class.java)
        `when`(uri.toString()).thenReturn(uriString)
        val tree = mock(DocumentFile::class.java)
        `when`(tree.uri).thenReturn(uri)
        return ManagedDownloadRootHandle.TreeRoot(tree)
    }
}
