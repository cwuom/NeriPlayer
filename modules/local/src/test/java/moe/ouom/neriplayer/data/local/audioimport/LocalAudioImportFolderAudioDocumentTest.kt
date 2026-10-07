package moe.ouom.neriplayer.data.local.audioimport

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class LocalAudioImportFolderAudioDocumentTest {
    private val documentUri: Uri = mock(Uri::class.java)

    @Test
    fun `audio mime types are accepted whatever the file name`() {
        assertTrue(isAudio(child(mimeType = "AUDIO/FLAC", displayName = "cover.jpg")))
        assertTrue(isAudio(child(mimeType = "audio/mpeg", displayName = "README")))
    }

    @Test
    fun `generic mime types fall back to the file extension`() {
        assertTrue(isAudio(child(mimeType = "application/octet-stream", displayName = "Night Drive.FLAC")))
        assertTrue(isAudio(child(mimeType = "", displayName = "track.opus")))

        assertFalse(isAudio(child(mimeType = "application/octet-stream", displayName = "notes.txt")))
        assertFalse(isAudio(child(mimeType = "application/octet-stream", displayName = "README")))
        assertFalse(isAudio(child(mimeType = "application/octet-stream", displayName = "trailing.")))
    }

    private fun isAudio(child: QueriedFolderChild): Boolean =
        with(LocalAudioImportManager) { child.isSupportedAudioDocument() }

    private fun child(mimeType: String, displayName: String) =
        QueriedFolderChild(documentUri, displayName, mimeType, isDirectory = false)
}
