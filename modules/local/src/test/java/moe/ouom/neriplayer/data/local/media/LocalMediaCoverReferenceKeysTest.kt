package moe.ouom.neriplayer.data.local.media

import android.net.Uri
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import java.io.File

class LocalMediaCoverReferenceKeysTest {
    @Test
    fun `cover references prefer the first non-blank candidate in priority order`() {
        assertEquals("sidecar.jpg", LocalMediaSupport.resolveCoverReferenceByPriorityImpl(" sidecar.jpg ", "embedded", "fallback"))
        assertEquals("embedded", LocalMediaSupport.resolveCoverReferenceByPriorityImpl(null, "embedded", "fallback"))
        assertEquals("fallback", LocalMediaSupport.resolveCoverReferenceByPriorityImpl("  ", "", "fallback"))
        assertNull(LocalMediaSupport.resolveCoverReferenceByPriorityImpl(null, " "))
    }

    @Test
    fun `embedded cover lookups use raw references when no local uri resolves`() {
        val song = song(localFilePath = "/music/a.flac", mediaUri = "content://media/external/audio/media/3")

        assertEquals(
            listOf("/music/a.flac", "content://media/external/audio/media/3"),
            LocalMediaSupport.embeddedCoverCacheLookupKeysImpl(song)
        )
    }

    @Test
    fun `embedded cover lookups add the file uri path and its string form`() {
        val fileUri = uri(scheme = "file", path = "/music/a.flac", text = "file:///music/a.flac")

        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.fromFile(File("/music/a.flac")) }.thenReturn(fileUri)

            assertEquals(
                listOf("/music/a.flac", "file:///music/a.flac"),
                LocalMediaSupport.embeddedCoverCacheLookupKeysImpl(song(localFilePath = "/music/a.flac", mediaUri = null))
            )
        }
    }

    @Test
    fun `embedded cover lookups skip blank paths and the path of content uris`() {
        val contentUri = uri(scheme = "content", path = "/external/audio/media/4", text = "content://media/external/audio/media/4")

        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse("content://media/external/audio/media/4") }.thenReturn(contentUri)

            assertEquals(
                listOf("content://media/external/audio/media/4"),
                LocalMediaSupport.embeddedCoverCacheLookupKeysImpl(
                    song(localFilePath = "  ", mediaUri = "content://media/external/audio/media/4")
                )
            )
        }
    }

    private fun uri(scheme: String, path: String, text: String): Uri = mock(Uri::class.java).also { uri ->
        doReturn(scheme).`when`(uri).scheme
        doReturn(path).`when`(uri).path
        doReturn(text).`when`(uri).toString()
    }

    private fun song(localFilePath: String?, mediaUri: String?) = SongItem(
        id = 3,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 0,
        durationMs = 0,
        coverUrl = null,
        mediaUri = mediaUri,
        localFilePath = localFilePath
    )
}
