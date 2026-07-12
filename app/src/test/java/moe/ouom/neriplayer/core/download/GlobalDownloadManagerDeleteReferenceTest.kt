package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlobalDownloadManagerDeleteReferenceTest {

    @Test
    fun `metadata delete reference must already exist in trusted snapshot`() {
        val trustedReference =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FNeriPlayer/document/primary%3AMusic%2FNeriPlayer%2FCovers%2Fsong.jpg"
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            knownReferences = setOf(trustedReference)
        )

        assertEquals(
            trustedReference,
            GlobalDownloadManager.trustedManagedMetadataReference(trustedReference, snapshot)
        )
        assertNull(
            GlobalDownloadManager.trustedManagedMetadataReference(
                "/tmp/outside/song.jpg",
                snapshot
            )
        )
        assertNull(
            GlobalDownloadManager.trustedManagedMetadataReference(
                "content://com.example.documents/tree/primary%3AMusic%2FNeriPlayer/document/primary%3AMusic%2FNeriPlayer%2FCovers%2Fsong.jpg",
                snapshot
            )
        )
    }
}
