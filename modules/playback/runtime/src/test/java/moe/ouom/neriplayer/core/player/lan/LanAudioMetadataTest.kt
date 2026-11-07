package moe.ouom.neriplayer.core.player.lan

import moe.ouom.neriplayer.core.player.url.decorateListenTogetherStreamUrl
import moe.ouom.neriplayer.core.player.url.qualityLabelForNetease
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import org.junit.Assert.*
import org.junit.Test

class LanAudioMetadataTest {
    private val format = LanAudioFormat("audio/mpeg", 320, 44100, 2, null)
    @Test fun hostAndMemberUseSharedCandidateQualityAndMeasuredFileInsteadOfLocalPreferences() {
        val url = decorateListenTogetherStreamUrl("https://m.music.126.net/audio.mp3", PlaybackAudioSource.NETEASE, "exhigh")
        val host = lanAudioMetadata("netease", url, format, Int::toString)
        val member = lanAudioMetadata("netease", url, format, Int::toString)
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
        val bili = decorateListenTogetherStreamUrl("https://a.bilivideo.com/audio", PlaybackAudioSource.BILIBILI, "high")
        assertEquals(PlaybackAudioSource.BILIBILI, lanAudioMetadata("bilibili", bili, format, Int::toString).source)
        val netease = lanAudioMetadata("netease", "https://m.music.126.net/audio", format, Int::toString)
        assertEquals(PlaybackAudioSource.NETEASE, netease.source)
        assertNull(netease.qualityKey)
        assertNull(netease.qualityLabel)
        assertEquals("MP3", netease.codecLabel)
    }
    @Test fun fallbackCandidateUsesItsOwnQualityAndUnknownMarkersAreNotInvented() {
        val url = decorateListenTogetherStreamUrl("https://m.music.126.net/fallback", PlaybackAudioSource.NETEASE, "standard")
        assertEquals("standard", lanAudioMetadata("netease", url, format, Int::toString).qualityKey)
        assertNull(lanAudioMetadata("bilibili", url, format, Int::toString).qualityKey)
        assertEquals(PlaybackAudioSource.YOUTUBE_MUSIC, lanAudioSource("youtubeMusic"))
    }
}
