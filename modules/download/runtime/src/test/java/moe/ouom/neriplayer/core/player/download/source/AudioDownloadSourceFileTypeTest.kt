package moe.ouom.neriplayer.core.player.download.source

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class AudioDownloadSourceFileTypeTest {
    private val resolver = AudioDownloadSourceResolver

    @Test
    fun `stream mime types map to the stored audio extension`() {
        mapOf(
            "audio/flac" to "flac",
            "AUDIO/X-FLAC" to "flac",
            "audio/eac3" to "eac3",
            "audio/e-ac-3" to "eac3",
            "audio/mp4" to "m4a",
            "audio/m4a" to "m4a",
            "audio/aac" to "m4a",
            "video/mp4" to "mp4",
            "audio/webm" to "webm",
            "audio/ogg" to "ogg",
            "audio/mpeg" to "mp3"
        ).forEach { (mime, extension) ->
            assertEquals(mime, extension, resolver.mimeToExt(mime))
        }
    }

    @Test
    fun `unknown mime types have no extension hint`() {
        assertNull(resolver.mimeToExt("audio/x-unknown"))
        assertNull(resolver.mimeToExt(""))
    }

    @Test
    fun `url extension hint is the lowercased suffix of the last path segment`() {
        val segments = mapOf(
            "https://cdn.example/a/Track.FLAC?token=1" to "Track.FLAC",
            "https://cdn.example/a/audio.opusstream" to "audio.opusstream",
            "https://cdn.example/a/archive.tar.gz" to "archive.tar.gz"
        )

        withLastPathSegments(segments) {
            assertEquals("flac", resolver.extFromUrl("https://cdn.example/a/Track.FLAC?token=1"))
            assertEquals("opusst", resolver.extFromUrl("https://cdn.example/a/audio.opusstream"))
            assertEquals("gz", resolver.extFromUrl("https://cdn.example/a/archive.tar.gz"))
        }
    }

    @Test
    fun `url without a usable suffix has no extension hint`() {
        val segments = mapOf(
            "https://cdn.example/" to null,
            "https://cdn.example/a/stream" to "stream",
            "https://cdn.example/a/.hidden" to ".hidden",
            "https://cdn.example/a/track." to "track."
        )

        withLastPathSegments(segments) {
            segments.keys.forEach { url -> assertNull(url, resolver.extFromUrl(url)) }
        }
    }

    private fun withLastPathSegments(segments: Map<String, String?>, block: () -> Unit) {
        val parsed = segments.mapValues { (_, segment) ->
            mock(Uri::class.java).also { uri -> doReturn(segment).`when`(uri).lastPathSegment }
        }
        mockStatic(Uri::class.java).use { uris ->
            parsed.forEach { (url, uri) ->
                uris.`when`<Uri> { Uri.parse(url) }.thenReturn(uri)
            }
            block()
        }
    }
}
