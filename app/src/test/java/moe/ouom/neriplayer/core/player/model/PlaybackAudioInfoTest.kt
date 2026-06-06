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
    fun mergeLocalPlaybackAudioInfoWithRemoteQuality_preservesDisplayedRemoteQuality() {
        val localAudioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.LOCAL,
            codecLabel = "FLAC",
            bitrateKbps = 1411,
            sampleRateHz = 48_000,
            bitDepth = 24
        )
        val previousRemoteAudioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.BILIBILI,
            qualityKey = "lossless",
            qualityLabel = "无损",
            qualityOptions = listOf(PlaybackQualityOption("lossless", "无损"))
        )

        val merged = mergeLocalPlaybackAudioInfoWithRemoteQuality(
            localAudioInfo = localAudioInfo,
            previousAudioInfo = previousRemoteAudioInfo
        )

        assertEquals(PlaybackAudioSource.LOCAL, merged?.source)
        assertEquals("lossless", merged?.qualityKey)
        assertEquals("无损", merged?.qualityLabel)
        assertEquals(emptyList<PlaybackQualityOption>(), merged?.qualityOptions)
        assertEquals("FLAC", merged?.codecLabel)
        assertEquals(1411, merged?.bitrateKbps)
        assertEquals(48_000, merged?.sampleRateHz)
        assertEquals(24, merged?.bitDepth)
    }

    @Test
    fun mergeLocalPlaybackAudioInfoWithRemoteQuality_ignoresLocalPreviousInfo() {
        val localAudioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.LOCAL,
            codecLabel = "AAC",
            bitrateKbps = 256
        )
        val previousLocalAudioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.LOCAL,
            qualityKey = "lossless",
            qualityLabel = "无损"
        )

        val merged = mergeLocalPlaybackAudioInfoWithRemoteQuality(
            localAudioInfo = localAudioInfo,
            previousAudioInfo = previousLocalAudioInfo
        )

        assertEquals(localAudioInfo, merged)
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
