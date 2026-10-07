package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.`when`

class LocalMediaFastDurationResolutionTest {
    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java)
    private val uri = mock(Uri::class.java)

    @Before
    fun setUp() {
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(uri.scheme).thenReturn("content")
        `when`(uri.toString()).thenReturn("content://media/external/audio/media/42")
    }

    @Test
    fun `provider durations are used without opening the audio`() {
        stubProviderDuration(240_000L)

        mockConstruction(MediaExtractor::class.java).use { construction ->
            assertEquals(240_000L, LocalMediaSupport.resolveDurationFast(context, uri))
            assertTrue(construction.constructed().isEmpty())
        }
    }

    @Test
    fun `missing provider durations fall back to the audio track`() {
        stubProviderDuration(0L)
        assertEquals(180_000L, resolveWithTrack(audioTrack(durationUs = 180_000_000L)))

        doReturn(null).`when`(resolver).query(any(), any(), any(), any(), any())
        assertEquals(0L, resolveWithTrack(audioTrack(durationUs = null)))
        assertEquals(0L, resolveWithTrack(null))
    }

    @Test
    fun `provider and native media failures leave the duration unknown`() {
        doThrow(SecurityException("grant revoked")).`when`(resolver).getType(any())

        mockConstruction(MediaExtractor::class.java) { extractor, _ ->
            doThrow(UnsatisfiedLinkError("media_jni")).`when`(extractor)
                .setDataSource(any(Context::class.java), any(Uri::class.java), anyMap())
        }.use { construction ->
            assertEquals(0L, LocalMediaSupport.resolveDurationFast(context, uri))
            assertEquals(1, construction.constructed().size)
        }
    }

    private fun resolveWithTrack(track: MediaFormat?): Long {
        return mockConstruction(MediaExtractor::class.java) { extractor, _ ->
            `when`(extractor.trackCount).thenReturn(if (track == null) 0 else 1)
            if (track != null) {
                `when`(extractor.getTrackFormat(0)).thenReturn(track)
            }
        }.use {
            LocalMediaSupport.resolveDurationFast(context, uri)
        }
    }

    private fun stubProviderDuration(durationMs: Long) {
        val cursor = mock(Cursor::class.java)
        `when`(cursor.moveToFirst()).thenReturn(true)
        `when`(cursor.getColumnIndex(anyString())).thenReturn(-1)
        `when`(cursor.getColumnIndex("duration")).thenReturn(3)
        `when`(cursor.getLong(3)).thenReturn(durationMs)
        doReturn(cursor).`when`(resolver).query(any(), any(), any(), any(), any())
    }

    private fun audioTrack(durationUs: Long?): MediaFormat {
        val format = mock(MediaFormat::class.java)
        `when`(format.containsKey(anyString())).thenReturn(false)
        `when`(format.containsKey(MediaFormat.KEY_MIME)).thenReturn(true)
        `when`(format.getString(MediaFormat.KEY_MIME)).thenReturn("audio/flac")
        if (durationUs != null) {
            `when`(format.containsKey(MediaFormat.KEY_DURATION)).thenReturn(true)
            `when`(format.getLong(MediaFormat.KEY_DURATION)).thenReturn(durationUs)
        }
        return format
    }
}
