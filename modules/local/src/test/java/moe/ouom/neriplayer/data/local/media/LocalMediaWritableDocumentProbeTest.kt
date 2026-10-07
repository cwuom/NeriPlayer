package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.io.FileNotFoundException

class LocalMediaWritableDocumentProbeTest {
    private val resolver: ContentResolver = mock(ContentResolver::class.java)
    private val context: Context = mock(Context::class.java).also { context ->
        doReturn(resolver).`when`(context).contentResolver
    }
    private val document: Uri = mock(Uri::class.java)

    @Test
    fun `documents opening for read write are writable and their descriptor is closed`() {
        val descriptor = mock(ParcelFileDescriptor::class.java)
        doReturn(descriptor).`when`(resolver).openFileDescriptor(document, "rw")

        assertTrue(LocalMediaSupport.isWritableDocumentUri(context, document))
        verify(descriptor).close()
    }

    @Test
    fun `documents without a descriptor or failing to open are not writable`() {
        doReturn(null).`when`(resolver).openFileDescriptor(document, "rw")
        assertFalse(LocalMediaSupport.isWritableDocumentUri(context, document))

        doThrow(FileNotFoundException("read only volume")).`when`(resolver).openFileDescriptor(document, "rw")
        assertFalse(LocalMediaSupport.isWritableDocumentUri(context, document))
    }

    @Test
    fun `permission failures are propagated to the caller`() {
        val denial = SecurityException("grant revoked")
        doThrow(denial).`when`(resolver).openFileDescriptor(document, "rw")

        val thrown = assertThrows(SecurityException::class.java) {
            LocalMediaSupport.isWritableDocumentUri(context, document)
        }

        assertSame(denial, thrown)
    }
}
