package moe.ouom.neriplayer.core.player.engine

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegFlacMediaCodecSelectorTest {

    @Test
    fun `enabled FFmpeg FLAC policy does not query platform decoder`() {
        val delegate = RecordingMediaCodecSelector()
        val selector = FfmpegFlacMediaCodecSelector(
            delegate = delegate,
            shouldPreferFfmpegForFlac = true
        )

        assertTrue(selector.getDecoderInfos(MimeTypes.AUDIO_FLAC, false, false).isEmpty())
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun `disabled FFmpeg FLAC policy delegates to platform decoder`() {
        val delegate = RecordingMediaCodecSelector()
        val selector = FfmpegFlacMediaCodecSelector(
            delegate = delegate,
            shouldPreferFfmpegForFlac = false
        )

        selector.getDecoderInfos(MimeTypes.AUDIO_FLAC, true, true)

        assertEquals(
            listOf(DecoderRequest(MimeTypes.AUDIO_FLAC, true, true)),
            delegate.requests
        )
    }

    @Test
    fun `non FLAC formats retain the platform decoder selection`() {
        val delegate = RecordingMediaCodecSelector()
        val selector = FfmpegFlacMediaCodecSelector(
            delegate = delegate,
            shouldPreferFfmpegForFlac = true
        )

        selector.getDecoderInfos(MimeTypes.AUDIO_MPEG, false, true)

        assertEquals(
            listOf(DecoderRequest(MimeTypes.AUDIO_MPEG, false, true)),
            delegate.requests
        )
    }
}

private class RecordingMediaCodecSelector : MediaCodecSelector {
    val requests = mutableListOf<DecoderRequest>()

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo> {
        requests += DecoderRequest(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder
        )
        return emptyList()
    }
}

private data class DecoderRequest(
    val mimeType: String,
    val requiresSecureDecoder: Boolean,
    val requiresTunnelingDecoder: Boolean
)
