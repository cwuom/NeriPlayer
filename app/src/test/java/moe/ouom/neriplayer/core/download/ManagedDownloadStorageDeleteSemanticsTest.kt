package moe.ouom.neriplayer.core.download

import java.io.FileNotFoundException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStorageDeleteSemanticsTest {

    @Test
    fun `missing saf document failures are treated as already deleted`() {
        assertTrue(
            ManagedDownloadStorage.isMissingManagedDocumentFailure(
                FileNotFoundException("Missing file for primary:neriplayer-download/test.flac")
            )
        )
        assertTrue(
            ManagedDownloadStorage.isMissingManagedDocumentFailure(
                IllegalArgumentException("Failed to determine if uri is child of primary:neriplayer-download")
            )
        )
    }

    @Test
    fun `unrelated delete failures are not swallowed as missing document`() {
        assertFalse(
            ManagedDownloadStorage.isMissingManagedDocumentFailure(
                IllegalStateException("provider offline")
            )
        )
    }

    @Test
    fun `committed byte verification rejects short writes`() {
        assertEquals(
            128L,
            ManagedDownloadStorage.verifiedCommittedByteCount(
                expectedSizeBytes = 128L,
                reportedSizeBytes = 128L,
                countedSizeBytes = null
            )
        )
        assertNull(
            ManagedDownloadStorage.verifiedCommittedByteCount(
                expectedSizeBytes = 128L,
                reportedSizeBytes = 64L,
                countedSizeBytes = null
            )
        )
        assertNull(
            ManagedDownloadStorage.verifiedCommittedByteCount(
                expectedSizeBytes = 128L,
                reportedSizeBytes = 64L,
                countedSizeBytes = 65L
            )
        )
    }

    @Test
    fun `committed byte verification falls back to counted bytes when reported size drifts`() {
        assertEquals(
            128L,
            ManagedDownloadStorage.verifiedCommittedByteCount(
                expectedSizeBytes = 128L,
                reportedSizeBytes = 64L,
                countedSizeBytes = 128L
            )
        )
    }

    @Test
    fun `committed byte verification allows small saf size drift`() {
        assertEquals(
            129L,
            ManagedDownloadStorage.verifiedCommittedByteCount(
                expectedSizeBytes = 128L,
                reportedSizeBytes = 129L,
                countedSizeBytes = null,
                toleranceBytes = 1L
            )
        )
        assertNull(
            ManagedDownloadStorage.verifiedCommittedByteCount(
                expectedSizeBytes = 128L,
                reportedSizeBytes = 130L,
                countedSizeBytes = null,
                toleranceBytes = 1L
            )
        )
    }

    @Test
    fun `committed byte verification falls back to counted bytes when reported size is unavailable`() {
        assertEquals(
            128L,
            ManagedDownloadStorage.verifiedCommittedByteCount(
                expectedSizeBytes = 128L,
                reportedSizeBytes = null,
                countedSizeBytes = 128L
            )
        )
        assertNull(
            ManagedDownloadStorage.verifiedCommittedByteCount(
                expectedSizeBytes = 128L,
                reportedSizeBytes = null,
                countedSizeBytes = 127L
            )
        )
    }

    @Test
    fun `file delete guard rejects paths outside managed root`() {
        val managedRoot = Files.createTempDirectory("neri-managed-root").toFile()
        val foreignRoot = Files.createTempDirectory("neri-foreign-root").toFile()
        try {
            val managedFile = managedRoot.resolve("Covers/song.jpg").absolutePath
            val foreignFile = foreignRoot.resolve("song.jpg").absolutePath

            assertTrue(
                ManagedDownloadStorage.isReferenceAllowedForManagedDelete(
                    reference = managedFile,
                    trustedReferences = emptySet(),
                    managedFileRoots = listOf(managedRoot.absolutePath),
                    managedTreeRoots = emptyList()
                )
            )
            assertFalse(
                ManagedDownloadStorage.isReferenceAllowedForManagedDelete(
                    reference = foreignFile,
                    trustedReferences = emptySet(),
                    managedFileRoots = listOf(managedRoot.absolutePath),
                    managedTreeRoots = emptyList()
                )
            )
        } finally {
            managedRoot.deleteRecursively()
            foreignRoot.deleteRecursively()
        }
    }

    @Test
    fun `saf delete guard rejects cross authority and outside tree documents`() {
        val managedTree =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FNeriPlayer"
        val managedChild =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FNeriPlayer/document/primary%3AMusic%2FNeriPlayer%2FCovers%2Fsong.jpg"
        val outsideTreeChild =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FNeriPlayer/document/primary%3AMusic%2FOther%2Fsong.jpg"
        val crossAuthorityChild =
            "content://com.example.documents/tree/primary%3AMusic%2FNeriPlayer/document/primary%3AMusic%2FNeriPlayer%2FCovers%2Fsong.jpg"

        assertTrue(
            ManagedDownloadStorage.isReferenceAllowedForManagedDelete(
                reference = managedChild,
                trustedReferences = emptySet(),
                managedFileRoots = emptyList(),
                managedTreeRoots = listOf(managedTree)
            )
        )
        assertFalse(
            ManagedDownloadStorage.isReferenceAllowedForManagedDelete(
                reference = outsideTreeChild,
                trustedReferences = emptySet(),
                managedFileRoots = emptyList(),
                managedTreeRoots = listOf(managedTree)
            )
        )
        assertFalse(
            ManagedDownloadStorage.isReferenceAllowedForManagedDelete(
                reference = crossAuthorityChild,
                trustedReferences = emptySet(),
                managedFileRoots = emptyList(),
                managedTreeRoots = listOf(managedTree)
            )
        )
    }
}
