package moe.ouom.neriplayer.core.download

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.nio.file.Files
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ManagedDownloadStorageDeleteSemanticsTest {

    @Test
    fun `missing saf document failures are treated as already deleted`() {
        assertTrue(
            ManagedDownloadStorage.isMissingManagedDocumentFailure(
                FileNotFoundException("Missing file for primary:neriplayer-download/test.flac")
            )
        )
        assertFalse(
            ManagedDownloadStorage.isMissingManagedDocumentFailure(
                IllegalArgumentException("Failed to determine if uri is child of primary:neriplayer-download")
            )
        )
    }

    @Test
    fun `reference io keeps missing document failure semantics aligned`() {
        val missingFileError = FileNotFoundException(
            "Missing file for primary:neriplayer-download/test.flac"
        )
        val unrelatedError = IllegalStateException("provider offline")

        assertEquals(
            ManagedDownloadStorage.isMissingManagedDocumentFailure(missingFileError),
            ManagedDownloadReferenceIo.isMissingDocumentFailure(missingFileError)
        )
        assertEquals(
            ManagedDownloadStorage.isMissingManagedDocumentFailure(unrelatedError),
            ManagedDownloadReferenceIo.isMissingDocumentFailure(unrelatedError)
        )
    }

    @Test
    fun `verified transfer does not reject a post processing size change`() {
        assertFalse(
            ManagedDownloadStorage.shouldRejectTransferSize(
                expectedSizeBytes = 3_758_751L,
                actualSizeBytes = 4_551_323L,
                transferSizeVerified = true
            )
        )
    }

    @Test
    fun `unverified transfer still rejects a materially short payload`() {
        assertTrue(
            ManagedDownloadStorage.shouldRejectTransferSize(
                expectedSizeBytes = 1_000_000L,
                actualSizeBytes = 900_000L,
                transferSizeVerified = false
            )
        )
    }

    @Test
    fun `unknown transfer length is never rejected by storage size guard`() {
        assertFalse(
            ManagedDownloadStorage.shouldRejectTransferSize(
                expectedSizeBytes = null,
                actualSizeBytes = 128L,
                transferSizeVerified = false
            )
        )
    }

    @Test
    fun `reference io resolves file uri as a local file`() {
        val file = Files.createTempFile("neriplayer-reference", ".txt").toFile()
        try {
            file.writeText("local-reference", Charsets.UTF_8)
            val context = mock(Context::class.java)
            val reference = file.toURI().toString()

            assertEquals(
                ManagedDownloadReferenceIo.AccessResult.Accessible,
                ManagedDownloadReferenceIo.inspect(context, reference)
            )
            assertEquals("local-reference", ManagedDownloadReferenceIo.readText(context, reference))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `reference inspection distinguishes missing local files`() {
        val file = Files.createTempFile("neriplayer-reference", ".txt").toFile()
        val missing = file.resolveSibling(file.name + ".missing")
        try {
            val context = mock(Context::class.java)
            assertEquals(
                ManagedDownloadReferenceIo.AccessResult.Accessible,
                ManagedDownloadReferenceIo.inspect(context, file.absolutePath)
            )
            assertEquals(
                ManagedDownloadReferenceIo.AccessResult.Missing,
                ManagedDownloadReferenceIo.inspect(context, missing.absolutePath)
            )
        } finally {
            file.delete()
            missing.delete()
        }
    }

    @Test
    fun `directory inspection keeps local directory identity typed`() {
        val directory = Files.createTempDirectory("neriplayer-reference-dir").toFile()
        val missing = directory.resolve("missing")
        try {
            val context = mock(Context::class.java)
            assertEquals(
                ManagedDownloadReferenceIo.AccessResult.Accessible,
                ManagedDownloadReferenceIo.inspectDirectory(
                    context,
                    directory.absolutePath
                )
            )
            assertEquals(
                ManagedDownloadReferenceIo.AccessResult.Missing,
                ManagedDownloadReferenceIo.inspectDirectory(
                    context,
                    missing.absolutePath
                )
            )
        } finally {
            directory.deleteRecursively()
        }
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
    fun `permission-like file not found failures are never missing evidence`() {
        listOf(
            FileNotFoundException("Permission denied"),
            FileNotFoundException("EACCES: access denied"),
            FileNotFoundException("Operation not permitted")
        ).forEach { error ->
            assertFalse(ManagedDownloadReferenceIo.isMissingDocumentFailure(error))
        }
    }

    @Test
    fun `null provider cursor is not treated as missing`() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        `when`(context.contentResolver).thenReturn(resolver)

        val result = ManagedDownloadReferenceLookup.inspect(
            context,
            "content://provider/audio"
        )

        assertTrue(result is ManagedDownloadReferenceLookup.Result.ProviderFailure)
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
    fun `saf delete guard requires trusted enumeration instead of document id containment`() {
        val managedTree =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FNeriPlayer"
        val managedChild =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FNeriPlayer/document/primary%3AMusic%2FNeriPlayer%2FCovers%2Fsong.jpg"
        val outsideTreeChild =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FNeriPlayer/document/primary%3AMusic%2FOther%2Fsong.jpg"
        val crossAuthorityChild =
            "content://com.example.documents/tree/primary%3AMusic%2FNeriPlayer/document/primary%3AMusic%2FNeriPlayer%2FCovers%2Fsong.jpg"

        assertFalse(
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

    @Test
    fun `saf delete guard rejects opaque child ids without trusted enumeration`() {
        val managedTree = "content://documents.test/tree/root-opaque-id"
        val managedChild =
            "content://documents.test/tree/root-opaque-id/document/child-opaque-id"
        val foreignTreeChild =
            "content://documents.test/tree/other-opaque-id/document/child-opaque-id"

        assertFalse(
            ManagedDownloadStorage.isReferenceAllowedForManagedDelete(
                reference = managedChild,
                trustedReferences = emptySet(),
                managedFileRoots = emptyList(),
                managedTreeRoots = listOf(managedTree)
            )
        )
        assertFalse(
            ManagedDownloadStorage.isReferenceAllowedForManagedDelete(
                reference = foreignTreeChild,
                trustedReferences = emptySet(),
                managedFileRoots = emptyList(),
                managedTreeRoots = listOf(managedTree)
            )
        )
    }

    @Test
    fun `saf delete guard rejects opaque pure document references without a matching tree token`() {
        val managedTree =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FNeriPlayer"
        val outsideDocument =
            "content://com.android.externalstorage.documents/document/primary%3ADCIM"

        assertFalse(
            ManagedDownloadStorage.isReferenceAllowedForManagedDelete(
                reference = outsideDocument,
                trustedReferences = emptySet(),
                managedFileRoots = emptyList(),
                managedTreeRoots = listOf(managedTree)
            )
        )
    }

    @Test
    fun `saf delete guard accepts an exact provider ref from a trusted enumeration`() {
        val managedTree = "content://documents.test/tree/root-opaque-id"
        val opaqueRef =
            "content://documents.test/document/provider-owned-token"

        assertTrue(
            ManagedDownloadStorage.isReferenceAllowedForManagedDelete(
                reference = opaqueRef,
                trustedReferences = setOf(opaqueRef),
                managedFileRoots = emptyList(),
                managedTreeRoots = listOf(managedTree)
            )
        )
    }
}
