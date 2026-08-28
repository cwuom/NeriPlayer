package moe.ouom.neriplayer.core.download.storage.backend

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Answers.CALLS_REAL_METHODS
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class SafStorageBackendFailureClassificationTest {
    private val reference = StorageReference.SafRef(mock(Uri::class.java))

    @Test
    fun `stat keeps arbitrary file not found provider failures typed`() = runBlocking {
        val error = FileNotFoundException("provider temporarily unavailable")

        val result = SafStorageBackend(contextThatThrows(error)).stat(reference)

        assertProviderFailure(result, error)
    }

    @Test
    fun `stat classifies wrapped missing file provider error as missing`() = runBlocking {
        val error = IllegalArgumentException(
            "Failed to determine if primary:root/song.npmeta.json is child of primary:root",
            FileNotFoundException(
                "Missing file for primary:root/song.npmeta.json at /storage/emulated/0/root/song.npmeta.json"
            )
        )

        val result = SafStorageBackend(contextThatThrows(error)).stat(reference)

        assertEquals(StorageLookupResult.Missing, result)
    }

    @Test
    fun `stat keeps wrapped permission failure typed`() = runBlocking {
        val error = IllegalArgumentException(
            "Failed to determine if primary:root/song.npmeta.json is child of primary:root",
            FileNotFoundException("Permission denied for primary:root/song.npmeta.json")
        )

        val result = SafStorageBackend(contextThatThrows(error)).stat(reference)

        assertEquals(StorageLookupResult.PermissionLost, result)
    }

    @Test
    fun `list keeps arbitrary file not found provider failures typed`() = runBlocking {
        val error = FileNotFoundException("provider temporarily unavailable")

        val result = SafStorageBackend(contextThatThrows(error)).list(reference)

        val failure = result.confidence as StorageConfidence.ProviderFailure
        assertEquals(error, failure.error)
    }

    @Test
    fun `list maps document id permission failure to permission lost`() = runBlocking {
        val error = SecurityException("tree permission revoked")
        val treeUri = Uri.parse("content://provider/document/opaque-root")
        val context = mock(Context::class.java)
        mockStatic(DocumentsContract::class.java, CALLS_REAL_METHODS).use { documentsContract ->
            documentsContract.`when`<String> {
                DocumentsContract.getDocumentId(treeUri)
            }.thenAnswer { throw error }

            val queryChildren = SafStorageBackend::class.java
                .getDeclaredMethod("queryChildren", Uri::class.java)
                .apply { isAccessible = true }
            val result = queryChildren.invoke(SafStorageBackend(context), treeUri)

            assertEquals("PermissionLost", result.javaClass.simpleName)
        }
    }

    @Test
    fun `opaque document ids are accepted without normalization`() {
        val documentId = "opaque/segment:with punctuation"
        assertEquals(
            documentId,
            requireOpaqueDocumentId(documentId)
        )
    }

    @Test
    fun `read does not report arbitrary file not found as missing`() = runBlocking {
        val error = FileNotFoundException("provider temporarily unavailable")

        val result = SafStorageBackend(contextThatThrows(error)).read(reference) { it.readBytes() }

        assertProviderFailure(result, error)
    }

    @Test
    fun `read opens a valid SAF stream without a preflight document query`() = runBlocking {
        val payload = "saf-payload".encodeToByteArray()
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(resolver.openInputStream(reference.uri)).thenReturn(
            ByteArrayInputStream(payload)
        )
        doAnswer { throw AssertionError("valid SAF reads must not query the document first") }
            .`when`(resolver)
            .query(any(), any(), any(), any(), any())

        val result = SafStorageBackend(context, Dispatchers.Unconfined).read(reference) {
            it.readBytes()
        }

        assertTrue(result is StorageLookupResult.Found)
        assertArrayEquals(payload, (result as StorageLookupResult.Found).value)
    }

    @Test
    fun `write does not report arbitrary file not found as missing`() = runBlocking {
        val error = FileNotFoundException("provider temporarily unavailable")
        val target = StorageTarget.SafTarget(reference, "song.mp3", "audio/mpeg")

        val result = SafStorageBackend(contextThatThrows(error)).writeRecoverable(target) {
            it.write(byteArrayOf(1))
        }

        val failure = result as StorageWriteResult.ProviderFailure
        assertEquals(error, failure.error)
    }

    @Test
    fun `delete does not report arbitrary file not found as missing`() = runBlocking {
        val error = FileNotFoundException("provider temporarily unavailable")

        val result = SafStorageBackend(contextThatThrows(error)).delete(
            TrustedManagedRef(reference)
        )

        val failure = result as StorageMutationResult.ProviderFailure
        assertEquals(error, failure.error)
    }

    @Test
    fun `permission file not found is not reported as missing`() = runBlocking {
        val error = FileNotFoundException("provider permission denied")

        val result = SafStorageBackend(contextThatThrows(error)).stat(reference)

        assertEquals(StorageLookupResult.PermissionLost, result)
    }

    @Test
    fun `wrapped arbitrary file not found is not missing evidence`() {
        val error = IllegalStateException(
            "provider temporarily unavailable",
            FileNotFoundException("temporary provider failure")
        )

        assertFalse(ManagedDownloadReferenceIo.isMissingDocumentFailure(error))
    }

    private fun contextThatThrows(error: Throwable): Context {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        doAnswer { throw error }.`when`(resolver).query(any(), any(), any(), any(), any())
        return context
    }

    private fun assertProviderFailure(
        result: StorageLookupResult<*>,
        error: Throwable
    ) {
        assertTrue(result is StorageLookupResult.ProviderFailure)
        assertEquals(error, (result as StorageLookupResult.ProviderFailure).error)
    }
}
