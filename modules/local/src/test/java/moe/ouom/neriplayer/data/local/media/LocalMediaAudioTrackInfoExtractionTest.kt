package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.IOException

class LocalMediaAudioTrackInfoExtractionTest {
    private val context = mock(Context::class.java)
    private val uri = mock(Uri::class.java)

    @Test
    fun `the first audio track describes the source`() {
        val video = format(mime = "video/avc")
        val unnamed = format(mime = null)
        val audio = format(
            mime = "audio/mp4a-latm",
            durationUs = 180_000_000L,
            bitRate = 256_400,
            sampleRate = 44_100,
            channels = 2
        )

        val info = extract(listOf(video, unnamed, audio))

        assertEquals(
            LocalMediaSupport.AudioTrackTechInfo(
                audioMimeType = "audio/mp4a-latm",
                bitrateKbps = 256,
                sampleRateHz = 44_100,
                channelCount = 2,
                durationMs = 180_000L
            ),
            info
        )
    }

    @Test
    fun `audio tracks without usable details keep them unknown`() {
        val noDetails = extract(listOf(format(mime = "audio/flac")))
        val zeroDuration = extract(listOf(format(mime = "audio/flac", durationUs = 0L)))

        val unknown = LocalMediaSupport.AudioTrackTechInfo(
            audioMimeType = "audio/flac",
            bitrateKbps = null,
            sampleRateHz = null,
            channelCount = null,
            durationMs = null
        )
        assertEquals(unknown, noDetails)
        assertEquals(unknown, zeroDuration)
    }

    @Test
    fun `sources without an audio track have no track info`() {
        assertNull(extract(emptyList()))
        assertNull(extract(listOf(format(mime = "video/avc"), format(mime = "text/vtt"))))
    }

    @Test
    fun `sources the extractor cannot open have no track info and are released`() {
        mockConstruction(MediaExtractor::class.java) { extractor, _ ->
            doThrow(IOException("unsupported container")).`when`(extractor)
                .setDataSource(any(Context::class.java), any(Uri::class.java), anyMap())
        }.use { construction ->
            assertNull(LocalMediaSupport.inspectAudioTrackInfo(context, uri))
            verify(construction.constructed().single()).release()
        }
    }

    private fun extract(formats: List<MediaFormat>): LocalMediaSupport.AudioTrackTechInfo? {
        return mockConstruction(MediaExtractor::class.java) { extractor, _ ->
            `when`(extractor.trackCount).thenReturn(formats.size)
            formats.forEachIndexed { index, format ->
                `when`(extractor.getTrackFormat(index)).thenReturn(format)
            }
        }.use { construction ->
            val info = LocalMediaSupport.inspectAudioTrackInfo(context, uri)
            val extractor = construction.constructed().single()
            verify(extractor).setDataSource(context, uri, emptyMap())
            verify(extractor).release()
            info
        }
    }

    private fun format(
        mime: String?,
        durationUs: Long? = null,
        bitRate: Int? = null,
        sampleRate: Int? = null,
        channels: Int? = null
    ): MediaFormat {
        val format = mock(MediaFormat::class.java)
        `when`(format.containsKey(anyString())).thenReturn(false)
        mime?.let {
            `when`(format.containsKey(MediaFormat.KEY_MIME)).thenReturn(true)
            `when`(format.getString(MediaFormat.KEY_MIME)).thenReturn(it)
        }
        durationUs?.let {
            `when`(format.containsKey(MediaFormat.KEY_DURATION)).thenReturn(true)
            `when`(format.getLong(MediaFormat.KEY_DURATION)).thenReturn(it)
        }
        mapOf(
            MediaFormat.KEY_BIT_RATE to bitRate,
            MediaFormat.KEY_SAMPLE_RATE to sampleRate,
            MediaFormat.KEY_CHANNEL_COUNT to channels
        ).forEach { (key, value) ->
            if (value != null) {
                `when`(format.containsKey(key)).thenReturn(true)
                `when`(format.getInteger(key)).thenReturn(value)
            }
        }
        return format
    }
}
