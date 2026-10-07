package moe.ouom.neriplayer.data.local.audioimport

import android.net.Uri
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalAudioImportDocumentIdLookupTest {
    private val uri: Uri = mock(Uri::class.java)

    @Test
    fun `document ids are used without consulting the tree id`() {
        mockStatic(DocumentsContract::class.java).use { documents ->
            documents.`when`<String> { DocumentsContract.getDocumentId(uri) }.thenReturn("primary:Music/song.flac")

            assertEquals("primary:Music/song.flac", LocalAudioImportManager.resolveDocumentId(uri))
            documents.verify { DocumentsContract.getDocumentId(uri) }
            documents.verifyNoMoreInteractions()
        }
    }

    @Test
    fun `tree uris fall back to the tree document id`() {
        mockStatic(DocumentsContract::class.java).use { documents ->
            documents.`when`<String> { DocumentsContract.getDocumentId(uri) }
                .thenThrow(IllegalArgumentException("not a document"))
            documents.`when`<String> { DocumentsContract.getTreeDocumentId(uri) }.thenReturn("primary:Music")

            assertEquals("primary:Music", LocalAudioImportManager.resolveDocumentId(uri))
        }
    }

    @Test
    fun `uris that are neither documents nor trees have no id`() {
        mockStatic(DocumentsContract::class.java).use { documents ->
            documents.`when`<String> { DocumentsContract.getDocumentId(uri) }
                .thenThrow(IllegalArgumentException("not a document"))
            documents.`when`<String> { DocumentsContract.getTreeDocumentId(uri) }
                .thenThrow(IllegalArgumentException("not a tree"))

            assertNull(LocalAudioImportManager.resolveDocumentId(uri))
            documents.verify { DocumentsContract.getTreeDocumentId(uri) }
        }
    }
}
