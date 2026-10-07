package moe.ouom.neriplayer.data.local.media

import android.net.Uri
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalMediaSafReadFailureClassificationTest {
    private val permissionDenial = SecurityException("Permission Denial: reading requires that you obtain access")

    @Test
    fun `tree uris rejecting a descendant document are reported out of scope`() {
        val tree = uri(AUTHORITY)
        val error = SecurityException("Document primary:Music/a.flac is NOT A DESCENDANT of the granted tree")

        mockStatic(DocumentsContract::class.java).use { documents ->
            documents.`when`<Boolean> { DocumentsContract.isTreeUri(tree) }.thenReturn(true)

            assertEquals(
                SafAccessResult.OutOfScope(tree, "primary:Music/a.flac", error),
                LocalMediaSupport.classifySafReadFailure(tree, "primary:Music/a.flac", error)
            )
            documents.verify { DocumentsContract.isTreeUri(tree) }
            documents.verifyNoMoreInteractions()
        }
    }

    @Test
    fun `document uris are rebuilt as trees before reporting lost permission`() {
        val document = uri(AUTHORITY)
        val rebuilt = mock(Uri::class.java)

        mockStatic(DocumentsContract::class.java).use { documents ->
            documents.`when`<Boolean> { DocumentsContract.isTreeUri(document) }.thenReturn(false)
            documents.`when`<Uri> { DocumentsContract.buildTreeDocumentUri(AUTHORITY, "primary:Music") }
                .thenReturn(rebuilt)

            assertEquals(
                SafAccessResult.PermissionLost(rebuilt, permissionDenial),
                LocalMediaSupport.classifySafReadFailure(document, "primary:Music", permissionDenial)
            )
        }
    }

    @Test
    fun `document uris that cannot be rebuilt keep the original uri`() {
        val withoutAuthority = uri(null)
        val unbuildable = uri(AUTHORITY)
        val silent = SecurityException()

        mockStatic(DocumentsContract::class.java).use { documents ->
            documents.`when`<Boolean> { DocumentsContract.isTreeUri(withoutAuthority) }.thenReturn(false)
            documents.`when`<Boolean> { DocumentsContract.isTreeUri(unbuildable) }.thenReturn(false)
            documents.`when`<Uri> { DocumentsContract.buildTreeDocumentUri(AUTHORITY, "primary:Broken") }
                .thenThrow(IllegalArgumentException("bad document id"))

            assertEquals(
                SafAccessResult.PermissionLost(withoutAuthority, silent),
                LocalMediaSupport.classifySafReadFailure(withoutAuthority, "primary:Music", silent)
            )
            assertEquals(
                SafAccessResult.PermissionLost(unbuildable, permissionDenial),
                LocalMediaSupport.classifySafReadFailure(unbuildable, "primary:Broken", permissionDenial)
            )
        }
    }

    private fun uri(authority: String?): Uri = mock(Uri::class.java).also { uri ->
        doReturn(authority).`when`(uri).authority
    }

    private companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
    }
}
