package moe.ouom.neriplayer.data.local.audioimport

import android.net.Uri
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import java.io.File

class LocalAudioImportFastIdentityPolicyTest {
    @Test
    fun `non media store metadata sidecars always hydrate the fast identity`() {
        assertTrue(shouldHydrateLocalSongFastIdentity(song(mediaUri = CONTENT_REFERENCE), "/music/song.npmeta.json"))
    }

    @Test
    fun `media store sources hydrate only when their quick metadata needs probing`() {
        val contentUri = uri(scheme = "content", authority = "media")

        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(CONTENT_REFERENCE) }.thenReturn(contentUri)

            assertFalse(shouldHydrateLocalSongFastIdentity(song(mediaUri = CONTENT_REFERENCE), null))
            assertFalse(shouldHydrateLocalSongFastIdentity(song(mediaUri = CONTENT_REFERENCE), "   "))
            assertFalse(
                shouldHydrateLocalSongFastIdentity(song(mediaUri = CONTENT_REFERENCE), "content://media/external/file/9")
            )
            assertTrue(shouldHydrateLocalSongFastIdentity(song(mediaUri = CONTENT_REFERENCE, artist = "<unknown>"), null))
        }
    }

    @Test
    fun `file backed sources always hydrate their fast identity`() {
        val fileUri = uri(scheme = "file", authority = null)

        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.fromFile(File("/music/song.flac")) }.thenReturn(fileUri)

            assertTrue(shouldHydrateLocalSongFastIdentity(song(localFilePath = "/music/song.flac"), null))
        }
    }

    @Test
    fun `unusable sources fall back to the media store reference text`() {
        val remoteUri = uri(scheme = "https", authority = "cdn.example")

        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(CONTENT_REFERENCE) }.thenThrow(IllegalArgumentException("unparseable"))
            uris.`when`<Uri> { Uri.parse(REMOTE_REFERENCE) }.thenReturn(remoteUri)

            assertFalse(shouldHydrateLocalSongFastIdentity(song(mediaUri = CONTENT_REFERENCE), null))
            assertTrue(shouldHydrateLocalSongFastIdentity(song(mediaUri = CONTENT_REFERENCE, album = "  "), null))
            assertTrue(shouldHydrateLocalSongFastIdentity(song(mediaUri = REMOTE_REFERENCE), null))
        }
    }

    private fun uri(scheme: String, authority: String?): Uri = mock(Uri::class.java).also { uri ->
        doReturn(scheme).`when`(uri).scheme
        doReturn(authority).`when`(uri).authority
    }

    private fun song(
        mediaUri: String? = null,
        localFilePath: String? = null,
        artist: String = "Artist",
        album: String = "Album"
    ) = SongItem(
        id = 1,
        name = "Night Drive",
        artist = artist,
        album = album,
        albumId = 0,
        durationMs = 0,
        coverUrl = null,
        mediaUri = mediaUri,
        localFilePath = localFilePath
    )

    private companion object {
        const val CONTENT_REFERENCE = "content://media/external/audio/media/5"
        const val REMOTE_REFERENCE = "https://cdn.example/song.mp3"
    }
}
