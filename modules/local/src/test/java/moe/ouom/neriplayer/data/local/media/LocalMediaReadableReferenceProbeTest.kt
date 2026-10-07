package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.io.File

class LocalMediaReadableReferenceProbeTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java)
    private val documentUri = mock(Uri::class.java)

    @Before
    fun setUp() {
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(documentUri.scheme).thenReturn("content")
        `when`(documentUri.toString()).thenReturn(DOCUMENT_REFERENCE)
    }

    @Test
    fun `absolute references are readable only when they point at a file`() {
        val sidecar = tempFolder.newFile("Song.flac.npmeta.json")
        val directory = tempFolder.newFolder("Song.flac.npmeta.d")
        val missing = File(tempFolder.root, "missing.npmeta.json")

        assertTrue(LocalMediaSupport.isReadableLocalReference(context, sidecar.absolutePath))
        assertFalse(LocalMediaSupport.isReadableLocalReference(context, missing.absolutePath))
        assertFalse(LocalMediaSupport.isReadableLocalReference(context, directory.absolutePath))
        verifyNoInteractions(context)
    }

    @Test
    fun `rejected and non document references are not readable`() {
        val mediaItem = mock(Uri::class.java)
        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(REJECTED_REFERENCE) }
                .thenThrow(IllegalArgumentException("bad reference"))
            uris.`when`<Uri> { Uri.parse(MEDIA_REFERENCE) }.thenReturn(mediaItem)
            mockStatic(DocumentsContract::class.java).use { documents ->
                documents.`when`<Boolean> { DocumentsContract.isDocumentUri(context, mediaItem) }
                    .thenReturn(false)

                assertFalse(LocalMediaSupport.isReadableLocalReference(context, REJECTED_REFERENCE))
                assertFalse(LocalMediaSupport.isReadableLocalReference(context, MEDIA_REFERENCE))
                documents.verify { DocumentsContract.isDocumentUri(context, mediaItem) }
            }
        }
        verifyNoInteractions(resolver)
    }

    @Test
    fun `document references are readable when the provider opens the row`() {
        val cursor = rowCursor(hasRow = true)
        doReturn(cursor).`when`(resolver).query(any(), any(), any(), any(), any())
        doReturn(mock(ParcelFileDescriptor::class.java)).`when`(resolver)
            .openFileDescriptor(any(), anyString())

        assertTrue(probeDocument())
        verify(cursor).close()
    }

    @Test
    fun `document references without a provider row are not readable`() {
        doReturn(rowCursor(hasRow = false)).`when`(resolver).query(any(), any(), any(), any(), any())

        assertFalse(probeDocument())
        verify(resolver, never()).openFileDescriptor(any(), anyString())
    }

    @Test
    fun `lost document grants surface as security failures`() {
        doReturn(rowCursor(hasRow = true)).`when`(resolver).query(any(), any(), any(), any(), any())
        doThrow(SecurityException("grant revoked")).`when`(resolver)
            .openFileDescriptor(any(), anyString())

        val error = assertThrows(SecurityException::class.java) { probeDocument() }

        assertEquals("local metadata permission lost: $DOCUMENT_REFERENCE", error.message)
    }

    @Test
    fun `provider failures while opening documents are rethrown`() {
        doReturn(rowCursor(hasRow = true)).`when`(resolver).query(any(), any(), any(), any(), any())

        val error = assertThrows(IllegalStateException::class.java) { probeDocument() }

        assertEquals("provider returned null file descriptor", error.message)
    }

    private fun probeDocument(): Boolean {
        return mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(DOCUMENT_REFERENCE) }.thenReturn(documentUri)
            mockStatic(DocumentsContract::class.java).use { documents ->
                documents.`when`<Boolean> { DocumentsContract.isDocumentUri(context, documentUri) }
                    .thenReturn(true)
                LocalMediaSupport.isReadableLocalReference(context, DOCUMENT_REFERENCE)
            }
        }
    }

    private fun rowCursor(hasRow: Boolean): Cursor {
        val cursor = mock(Cursor::class.java)
        `when`(cursor.moveToFirst()).thenReturn(hasRow)
        return cursor
    }

    private companion object {
        const val DOCUMENT_REFERENCE =
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2FSong.flac.npmeta.json"
        const val MEDIA_REFERENCE = "content://media/external/audio/media/7"
        const val REJECTED_REFERENCE = "content://rejected reference"
    }
}
