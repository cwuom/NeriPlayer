package moe.ouom.neriplayer.data.local.media.metadata

import android.content.Context
import android.net.Uri
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class LocalMediaEditableSidecarFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = mock(Context::class.java)

    @Test
    fun `document and media store sources never resolve to a direct sidecar file`() {
        val document = uri("content", "com.android.externalstorage.documents")
        val mediaStore = uri("CONTENT", "media")

        assertNull(LocalMediaSupport.resolveEditableSidecarFile(context, document))
        assertNull(LocalMediaSupport.resolveEditableSidecarFile(context, mediaStore))
        verifyNoInteractions(context)
    }

    @Test
    fun `existing file sources resolve to the audio file itself`() {
        val audio = temporaryFolder.newFile("song.flac")

        assertEquals(audio, LocalMediaSupport.resolveEditableSidecarFile(context, uri("file", null, audio.absolutePath)))
        verifyNoInteractions(context)
    }

    @Test
    fun `remote sources resolve to no file`() {
        assertNull(LocalMediaSupport.resolveEditableSidecarFile(context, uri("https", "cdn.example.com", "/song.flac")))
    }

    private fun uri(scheme: String, authority: String?, path: String? = null): Uri = mock(Uri::class.java).also {
        doReturn(scheme).`when`(it).scheme
        doReturn(authority).`when`(it).authority
        doReturn(path).`when`(it).path
    }
}
