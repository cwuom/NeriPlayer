package moe.ouom.neriplayer.data.local.media

import android.net.Uri
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class LocalMediaAudioCollectionUriTest {
    @Test
    fun `media store item uris map to their parent collection`() {
        val collection = mock(Uri::class.java)
        val builder = mock(Uri.Builder::class.java)
        val item = uriWithPath("/external/audio/media/42")
        doReturn(builder).`when`(item).buildUpon()
        doReturn(builder).`when`(builder).path("/external/audio/media")
        doReturn(collection).`when`(builder).build()

        assertSame(collection, collectionOf(item))
        verify(builder).path("/external/audio/media")
    }

    @Test
    fun `uris without a parent path have no collection`() {
        val withoutPath = uriWithPath(null)
        val bareId = uriWithPath("42")
        val topLevel = uriWithPath("/42")

        assertNull(collectionOf(withoutPath))
        assertNull(collectionOf(bareId))
        assertNull(collectionOf(topLevel))
        verify(bareId, never()).buildUpon()
        verify(topLevel, never()).buildUpon()
    }

    private fun collectionOf(uri: Uri): Uri? = with(LocalMediaSupport) { uri.mediaStoreAudioCollectionUri() }

    private fun uriWithPath(path: String?): Uri = mock(Uri::class.java).also { uri ->
        doReturn(path).`when`(uri).path
    }
}
