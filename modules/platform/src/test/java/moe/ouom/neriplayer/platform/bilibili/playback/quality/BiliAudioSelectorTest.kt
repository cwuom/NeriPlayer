package moe.ouom.neriplayer.platform.bilibili.playback.quality

import moe.ouom.neriplayer.data.model.bilibili.playback.BiliAudioStreamInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class BiliAudioSelectorTest {

    @Test
    fun selectStreamByPreference_usesRealisticBiliBitrates() {
        val mediumStream = BiliAudioStreamInfo(
            id = 30232,
            mimeType = "audio/mp4",
            bitrateKbps = 92,
            qualityTag = null,
            url = "https://xy.example.bilivideo.cn/30232.m4s"
        )
        val lowStream = BiliAudioStreamInfo(
            id = 30216,
            mimeType = "audio/mp4",
            bitrateKbps = 48,
            qualityTag = null,
            url = "https://upos.example.bilivideo.com/30216.m4s"
        )
        val highStream = BiliAudioStreamInfo(
            id = 30280,
            mimeType = "audio/mp4",
            bitrateKbps = 200,
            qualityTag = null,
            url = "https://xy.example.mcdn.bilivideo.cn/30280.m4s"
        )
        val streams = listOf(lowStream, mediumStream, highStream)

        assertEquals(30280, selectStreamByPreference(streams, "high")?.id)
        assertEquals(30232, selectStreamByPreference(streams, "medium")?.id)
        assertEquals(30232, selectStreamByPreference(streams, "low")?.id)
    }

    @Test
    fun selectStreamByPreference_losslessPrefersRealFlacTrack() {
        val flacStream = BiliAudioStreamInfo(
            id = 30251,
            mimeType = "audio/flac",
            bitrateKbps = 1411,
            qualityTag = "hires",
            url = "https://upos.example.bilivideo.com/30251.m4s"
        )
        val highStream = BiliAudioStreamInfo(
            id = 30280,
            mimeType = "audio/mp4",
            bitrateKbps = 200,
            qualityTag = null,
            url = "https://upos.example.bilivideo.com/30280.m4s"
        )

        assertEquals(30251, selectStreamByPreference(listOf(highStream, flacStream), "lossless")?.id)
    }

    @Test
    fun selectStreamByPreference_normalizesQualityTagsBeforeDowngrade() {
        val hiresStream = BiliAudioStreamInfo(
            id = 30251,
            mimeType = "audio/flac",
            bitrateKbps = 1411,
            qualityTag = "HIRES",
            url = "https://upos.example.bilivideo.com/30251-hires.m4s"
        )
        val highStream = BiliAudioStreamInfo(
            id = 30280,
            mimeType = "audio/mp4",
            bitrateKbps = 200,
            qualityTag = null,
            url = "https://upos.example.bilivideo.com/30280.m4s"
        )

        assertEquals(30251, selectStreamByPreference(listOf(highStream, hiresStream), "hires")?.id)
    }

}
