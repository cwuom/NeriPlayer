package moe.ouom.neriplayer.core.player.lan

import androidx.media3.common.Format
import moe.ouom.neriplayer.core.player.url.qualityLabelForNetease
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import org.junit.Assert.*
import org.junit.Test

class LanAudioMetadataTest {
    private val format = Format.Builder().setSampleMimeType("audio/mpeg").setAverageBitrate(320_000)
        .setSampleRate(44_100).setChannelCount(2).build()
    @Test fun hostAndMemberUseSharedCandidateQualityAndMeasuredFileInsteadOfLocalPreferences() {
        val host = lanStreamingAudioMetadata("netease", "exhigh", format, Int::toString)
        val member = lanStreamingAudioMetadata("netease", "exhigh", format, Int::toString)
        assertEquals(host, member)
        assertEquals(PlaybackAudioSource.NETEASE, member.source)
        assertEquals("exhigh", member.qualityKey)
        assertEquals(qualityLabelForNetease("exhigh", Int::toString), member.qualityLabel)
        assertEquals("MP3", member.codecLabel)
        assertEquals(320, member.bitrateKbps)
        assertEquals(44100, member.sampleRateHz)
        assertTrue(member.qualityOptions.isEmpty())
        assertFalse(member.isNeteaseLocalFallback)
    }
    @Test fun switchingPlatformsDoesNotRetainPreviousSourceOrQuality() {
        assertEquals(PlaybackAudioSource.BILIBILI, lanStreamingAudioMetadata("bilibili", "high", format, Int::toString).source)
        val netease = lanStreamingAudioMetadata("netease", null, format, Int::toString)
        assertEquals(PlaybackAudioSource.NETEASE, netease.source)
        assertNull(netease.qualityKey)
        assertNull(netease.qualityLabel)
        assertEquals("MP3", netease.codecLabel)
    }
    @Test fun aStreamWithoutFormatDetailsDoesNotGuessCodecOrBitrate() {
        val info = lanStreamingAudioMetadata("netease", "standard", Format.Builder().build(), Int::toString)
        assertEquals("standard", info.qualityKey)
        assertNull(info.mimeType)
        assertNull(info.bitrateKbps)
        assertEquals(PlaybackAudioSource.YOUTUBE_MUSIC, lanAudioSource("youtubeMusic"))
    }
}
