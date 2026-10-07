package moe.ouom.neriplayer.data.local.media.metadata

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentChild
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import java.io.FileNotFoundException

class LocalMediaSidecarDocumentDeleteTest {
    private val resolver: ContentResolver = mock(ContentResolver::class.java)
    private val context: Context = mock(Context::class.java).also { context ->
        doReturn(resolver).`when`(context).contentResolver
    }
    private val document: Uri = mock(Uri::class.java)

    @Test
    fun `sidecars whose provider document id matches are deleted`() {
        withSidecarDocument { documents ->
            documents.`when`<Boolean> { DocumentsContract.deleteDocument(resolver, document) }.thenReturn(true)

            assertTrue(LocalMediaSupport.deleteDocumentReference(context, sidecar(SIDECAR_ID)))
            documents.verify { DocumentsContract.deleteDocument(resolver, document) }
        }
    }

    @Test
    fun `sidecars with blank or foreign document ids are never deleted`() {
        withSidecarDocument { documents ->
            assertFalse(LocalMediaSupport.deleteDocumentReference(context, sidecar(" ")))
            assertFalse(LocalMediaSupport.deleteDocumentReference(context, sidecar("primary:Music/other.lrc")))

            documents.verify({ DocumentsContract.getDocumentId(document) }, times(2))
            documents.verifyNoMoreInteractions()
        }
    }

    @Test
    fun `sidecars whose uri or document id cannot be resolved are never deleted`() {
        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(SIDECAR_URI) }.thenThrow(IllegalArgumentException("bad uri"))
            mockStatic(DocumentsContract::class.java).use { documents ->
                assertFalse(LocalMediaSupport.deleteDocumentReference(context, sidecar(SIDECAR_ID)))
                documents.verifyNoInteractions()
            }
        }

        withSidecarDocument { documents ->
            documents.`when`<String> { DocumentsContract.getDocumentId(document) }
                .thenThrow(IllegalArgumentException("not a document"))

            assertFalse(LocalMediaSupport.deleteDocumentReference(context, sidecar(SIDECAR_ID)))
            documents.verify { DocumentsContract.getDocumentId(document) }
            documents.verifyNoMoreInteractions()
        }
    }

    @Test
    fun `failed deletions report false while permission failures propagate`() {
        withSidecarDocument { documents ->
            documents.`when`<Boolean> { DocumentsContract.deleteDocument(resolver, document) }
                .thenThrow(FileNotFoundException("already gone"))
            assertFalse(LocalMediaSupport.deleteDocumentReference(context, sidecar(SIDECAR_ID)))

            val denial = SecurityException("grant revoked")
            documents.`when`<Boolean> { DocumentsContract.deleteDocument(resolver, document) }.thenThrow(denial)
            val thrown = assertThrows(SecurityException::class.java) {
                LocalMediaSupport.deleteDocumentReference(context, sidecar(SIDECAR_ID))
            }
            assertSame(denial, thrown)
        }
    }

    private fun sidecar(documentId: String) = DocumentChild(
        documentId = documentId,
        displayName = "song.lrc",
        isDirectory = false,
        uri = SIDECAR_URI
    )

    private fun withSidecarDocument(block: (MockedStatic<DocumentsContract>) -> Unit) {
        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(SIDECAR_URI) }.thenReturn(document)
            mockStatic(DocumentsContract::class.java).use { documents ->
                documents.`when`<String> { DocumentsContract.getDocumentId(document) }.thenReturn(SIDECAR_ID)
                block(documents)
            }
        }
    }

    private companion object {
        const val SIDECAR_ID = "primary:Music/song.lrc"
        const val SIDECAR_URI =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fsong.lrc"
    }
}
