package moe.ouom.neriplayer.data.local.media

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

class LocalMediaReadableTitleCandidateTest {
    private val source: Uri = mock(Uri::class.java).also { uri ->
        doReturn("track01.flac").`when`(uri).lastPathSegment
    }

    @Test
    fun `blank and uri shaped candidates are never readable titles`() {
        assertFalse(readable("   "))
        assertFalse(readable(" content://media/external/audio/media/1"))
        assertFalse(readable("File:///music/track01.flac"))
    }

    @Test
    fun `source file names only count as titles when they are also the fallback title`() {
        assertFalse(readable(" track01.flac ", fallbackTitle = "Track 01"))
        assertTrue(readable("track01.flac", fallbackTitle = "track01.flac"))
        assertTrue(readable("Night Drive", fallbackTitle = "Track 01"))
    }

    private fun readable(candidate: String, fallbackTitle: String = "Track 01") =
        LocalMediaSupport.isReadableLocalTitleCandidate(candidate, source, fallbackTitle)
}
