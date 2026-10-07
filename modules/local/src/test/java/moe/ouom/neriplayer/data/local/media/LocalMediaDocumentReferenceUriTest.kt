package moe.ouom.neriplayer.data.local.media

import android.net.Uri
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalMediaDocumentReferenceUriTest {
    private val base: Uri = mock(Uri::class.java)
    private val built: Uri = mock(Uri::class.java)

    @Test
    fun `tree bases build the document uri inside the granted tree`() {
        mockStatic(DocumentsContract::class.java).use { documents ->
            documents.`when`<Boolean> { DocumentsContract.isTreeUri(base) }.thenReturn(true)
            documents.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(base, DOCUMENT_ID) }.thenReturn(built)

            assertSame(built, LocalMediaSupport.buildDocumentReferenceUri(base, DOCUMENT_ID))
            documents.verify { DocumentsContract.isTreeUri(base) }
            documents.verify { DocumentsContract.buildDocumentUriUsingTree(base, DOCUMENT_ID) }
            documents.verifyNoMoreInteractions()
        }
    }

    @Test
    fun `document bases rebuild the uri from their authority`() {
        doReturn(AUTHORITY).`when`(base).authority
        mockStatic(DocumentsContract::class.java).use { documents ->
            documents.`when`<Uri> { DocumentsContract.buildDocumentUri(AUTHORITY, DOCUMENT_ID) }.thenReturn(built)

            assertSame(built, LocalMediaSupport.buildDocumentReferenceUri(base, DOCUMENT_ID))
        }
    }

    @Test
    fun `document bases without an authority are rejected`() {
        doReturn("content:///document/primary%3AMusic").`when`(base).toString()
        mockStatic(DocumentsContract::class.java).use { documents ->
            val error = assertThrows(IllegalStateException::class.java) {
                LocalMediaSupport.buildDocumentReferenceUri(base, DOCUMENT_ID)
            }

            assertEquals("Document URI has no authority: content:///document/primary%3AMusic", error.message)
            documents.verify { DocumentsContract.isTreeUri(base) }
            documents.verifyNoMoreInteractions()
        }
    }

    private companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val DOCUMENT_ID = "primary:Music/song.lrc"
    }
}
