package moe.ouom.neriplayer.core.player.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackAudioInfoTest {

    @Test
    fun buildPlaybackSpecLabel_formatsAvailableFields() {
        assertEquals(
            "96 kHz | 24 bit | 3129 kbps",
            buildPlaybackSpecLabel(
                sampleRateHz = 96_000,
                bitDepth = 24,
                bitrateKbps = 3_129
            )
        )
        assertEquals(
            "44.1 kHz | 320 kbps",
            buildPlaybackSpecLabel(
                sampleRateHz = 44_100,
                bitDepth = null,
                bitrateKbps = 320
            )
        )
        assertNull(
            buildPlaybackSpecLabel(
                sampleRateHz = null,
                bitDepth = null,
                bitrateKbps = null
            )
        )
    }

    @Test
    fun deriveCodecLabel_normalizesCommonMimeTypes() {
        assertEquals("FLAC", deriveCodecLabel("audio/flac"))
        assertEquals("AAC", deriveCodecLabel("audio/mp4; codecs=\"mp4a.40.2\""))
        assertEquals("E-AC-3", deriveCodecLabel("audio/eac3"))
        assertEquals("OPUS", deriveCodecLabel("audio/webm"))
        assertEquals("HLS", deriveCodecLabel("application/vnd.apple.mpegurl"))
    }

    @Test
    fun estimateBitrateKbps_usesContentLengthAndDuration() {
        assertEquals(400, estimateBitrateKbps(contentLength = 12_000_000L, durationMs = 240_000L))
        assertNull(estimateBitrateKbps(contentLength = null, durationMs = 240_000L))
        assertNull(estimateBitrateKbps(contentLength = 12_000_000L, durationMs = 0L))
    }

    @Test
    fun inferYouTubeQualityKeyFromBitrate_mapsThresholds() {
        assertEquals("very_high", inferYouTubeQualityKeyFromBitrate(192))
        assertEquals("high", inferYouTubeQualityKeyFromBitrate(128))
        assertEquals("medium", inferYouTubeQualityKeyFromBitrate(96))
        assertEquals("low", inferYouTubeQualityKeyFromBitrate(64))
        assertEquals("low", inferYouTubeQualityKeyFromBitrate(null))
    }
}
