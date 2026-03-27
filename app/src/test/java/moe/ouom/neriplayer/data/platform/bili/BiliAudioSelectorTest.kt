package moe.ouom.neriplayer.data.platform.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliAudioSelectorTest {

    @Test
    fun prioritizeBiliStreamUrls_prefersBilivideoHostsOverMountaintoys() {
        val prioritized = prioritizeBiliStreamUrls(
            primaryUrl = "https://b-demo.edge.mountaintoys.cn/upgcxcode/demo.m4s",
            backupUrls = listOf(
                "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/demo.m4s",
                "https://xy123x45x67x89xy.mcdn.bilivideo.cn:8082/v1/resource/demo.m4s"
            )
        )

        assertEquals(
            "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/demo.m4s",
            prioritized.first()
        )
        assertTrue(prioritized.last().contains("mountaintoys.cn"))
    }

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
    fun isBiliStreamHost_matchesMountaintoysEdgeDomain() {
        assertTrue(isBiliStreamHost("b-demo.edge.mountaintoys.cn"))
        assertTrue(isBiliStreamUrl("https://b-demo.edge.mountaintoys.cn/upgcxcode/demo.m4s"))
    }
}
