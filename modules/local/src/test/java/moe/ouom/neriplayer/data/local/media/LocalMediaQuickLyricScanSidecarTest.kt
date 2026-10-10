package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File

class LocalMediaQuickLyricScanSidecarTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun clearBefore() {
        LocalMediaSupport.clearCoverLookupCache()
    }

    @After
    fun clearAfter() {
        LocalMediaSupport.clearCoverLookupCache()
    }

    @Test
    fun `quick scans read every lyric sidecar beside the audio file`() {
        val album = tempFolder.newFolder("album")
        val audio = File(album, "Song.flac").apply { writeBytes(ByteArray(8)) }
        File(album, "Song.lrc").writeText("[00:01.00]original")
        File(album, "Song_trans.lrc").writeText("[00:01.00]translated")
        File(album, "Song_roma.lrc").writeText("[00:01.00]romanized")
        val resolver = mock(ContentResolver::class.java)
        val context = mock(Context::class.java)
        `when`(context.contentResolver).thenReturn(resolver)

        val result = LocalMediaSupport.inspectLyricsForScan(context, fileUri(audio))

        assertEquals("[00:01.00]original", result.lyric)
        assertEquals("[00:01.00]translated", result.translatedLyric)
        assertEquals("[00:01.00]romanized", result.romanizedLyric)
        assertTrue(result.hasOriginalSidecar)
        assertTrue(result.hasTranslatedSidecar)
        assertTrue(result.hasRomanizedSidecar)
        assertNull(result.embeddedLyric)
        assertNull(result.embeddedTranslatedLyric)
        assertNull(result.embeddedRomanizedLyric)
        verify(resolver, never()).openInputStream(any())
    }

    @Test
    fun `lyrics folder sidecars are read before the ones beside the audio file`() {
        val album = tempFolder.newFolder("album")
        val lyrics = File(album, "Lyrics").apply { mkdirs() }
        val audio = File(album, "Song.flac").apply { writeBytes(ByteArray(8)) }
        File(album, "Song.lrc").writeText("beside original")
        File(lyrics, "Song.lrc").writeText("folder original")
        File(lyrics, "Song_trans.lrc").writeText("folder translated")
        File(album, "Song_romanized.lrc.txt").writeText("beside romanized")
        val context = mock(Context::class.java)
        `when`(context.contentResolver).thenReturn(mock(ContentResolver::class.java))

        val result = LocalMediaSupport.inspectLyricsForScan(context, fileUri(audio))

        assertEquals("folder original", result.lyric)
        assertEquals("folder translated", result.translatedLyric)
        assertEquals("beside romanized", result.romanizedLyric)
        assertTrue(result.hasRomanizedSidecar)
    }

    private fun fileUri(file: File): Uri {
        val uri = mock(Uri::class.java)
        `when`(uri.scheme).thenReturn("file")
        `when`(uri.path).thenReturn(file.absolutePath)
        `when`(uri.toString()).thenReturn("file://${file.absolutePath}")
        return uri
    }
}
