package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStorageDirectoryUriTest {

    @Test
    fun `canonicalizeDirectoryUri returns null for blank input`() {
        assertNull(ManagedDownloadStorage.canonicalizeDirectoryUri(" "))
    }

    @Test
    fun `canonicalizeDirectoryUri collapses tree document uri to canonical tree uri`() {
        val uri =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FNeriPlayer/document/primary%3AMusic%2FNeriPlayer"

        val normalized = ManagedDownloadStorage.canonicalizeDirectoryUri(uri)

        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FNeriPlayer",
            normalized
        )
    }

    @Test
    fun `areEquivalentDirectoryUris matches canonical tree and tree document variants`() {
        val canonical =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FNeriPlayer"
        val treeDocument =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FNeriPlayer/document/primary%3AMusic%2FNeriPlayer"

        assertTrue(ManagedDownloadStorage.areEquivalentDirectoryUris(canonical, treeDocument))
    }
}
