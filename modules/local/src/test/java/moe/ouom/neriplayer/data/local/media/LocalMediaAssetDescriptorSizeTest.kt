package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.io.FileNotFoundException

class LocalMediaAssetDescriptorSizeTest {
    private val resolver: ContentResolver = mock(ContentResolver::class.java)
    private val context: Context = mock(Context::class.java).also { context ->
        doReturn(resolver).`when`(context).contentResolver
    }
    private val audio: Uri = uri(scheme = "content")

    @Test
    fun `descriptor lengths are reported and the descriptor is closed`() {
        val descriptor = descriptor(length = 5_242_880L)
        doReturn(descriptor).`when`(resolver).openAssetFileDescriptor(audio, "r")

        assertEquals(5_242_880L, LocalMediaSupport.resolveSizeFromAssetDescriptor(context, audio))
        verify(descriptor).close()
    }

    @Test
    fun `unknown lengths and missing descriptors have no size`() {
        val unknown = descriptor(length = AssetFileDescriptor.UNKNOWN_LENGTH)
        doReturn(unknown).`when`(resolver).openAssetFileDescriptor(audio, "r")
        assertNull(LocalMediaSupport.resolveSizeFromAssetDescriptor(context, audio))
        verify(unknown).close()

        doReturn(null).`when`(resolver).openAssetFileDescriptor(audio, "r")
        assertNull(LocalMediaSupport.resolveSizeFromAssetDescriptor(context, audio))
    }

    @Test
    fun `provider failures have no size`() {
        doThrow(FileNotFoundException("deleted")).`when`(resolver).openAssetFileDescriptor(audio, "r")

        assertNull(LocalMediaSupport.resolveSizeFromAssetDescriptor(context, audio))
    }

    @Test
    fun `remote uris are never opened`() {
        assertNull(LocalMediaSupport.resolveSizeFromAssetDescriptor(context, uri(scheme = "https")))
        verifyNoInteractions(resolver)
    }

    private fun uri(scheme: String): Uri = mock(Uri::class.java).also { uri ->
        doReturn(scheme).`when`(uri).scheme
    }

    private fun descriptor(length: Long): AssetFileDescriptor = mock(AssetFileDescriptor::class.java).also {
        doReturn(length).`when`(it).length
    }
}
