package moe.ouom.neriplayer.data.model.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackAudioInfoFormattingTest {

    @Test
    fun `sample rates below one kilohertz stay in hertz`() {
        assertEquals("0 Hz", formatPlaybackSampleRate(0))
        assertEquals("0 Hz", formatPlaybackSampleRate(-44_100))
        assertEquals("800 Hz", formatPlaybackSampleRate(800))
        assertEquals("48 kHz", formatPlaybackSampleRate(48_000))
        assertEquals("44.1 kHz", formatPlaybackSampleRate(44_100))
    }

    @Test
    fun `spec labels list only positive values`() {
        assertEquals(
            "44.1 kHz | 24 bit",
            PlaybackAudioInfo(PlaybackAudioSource.LOCAL, sampleRateHz = 44_100, bitDepth = 24, bitrateKbps = 0).specLabel
        )
        assertEquals("320 kbps", PlaybackAudioInfo(PlaybackAudioSource.NETEASE, bitrateKbps = 320).specLabel)
        assertNull(PlaybackAudioInfo(PlaybackAudioSource.NETEASE, sampleRateHz = -1).specLabel)
    }

    @Test
    fun `bitrate estimates need a positive length and duration`() {
        assertNull(estimateBitrateKbps(null, 1_000L))
        assertNull(estimateBitrateKbps(0L, 1_000L))
        assertNull(estimateBitrateKbps(40_000L, null))
        assertNull(estimateBitrateKbps(40_000L, 0L))
        assertNull(estimateBitrateKbps(1L, 1_000L))
        assertEquals(320, estimateBitrateKbps(40_000L, 1_000L))
    }

    @Test
    fun `preferred quality keys are looked up per remote source`() {
        val keys = PreferredQualityKeys(netease = "lossless", youtube = "very_high", bili = "hires")

        assertEquals("lossless", keys.forSource(PlaybackAudioSource.NETEASE))
        assertEquals("very_high", keys.forSource(PlaybackAudioSource.YOUTUBE_MUSIC))
        assertEquals("hires", keys.forSource(PlaybackAudioSource.BILIBILI))
        assertNull(keys.forSource(PlaybackAudioSource.LOCAL))
    }
}
