package moe.ouom.neriplayer.data.ltw.mapping

import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherStreamUrlPolicyTest {
    @Test
    fun `trusted domains accept subdomains but reject suffix impersonation and other platforms`() {
        val domains = mapOf(
            ListenTogetherChannels.NETEASE to listOf("music.126.net"),
            ListenTogetherChannels.BILIBILI to listOf("bilivideo.com", "bilivideo.cn", "hdslb.com", "mountaintoys.cn"),
            ListenTogetherChannels.YOUTUBE_MUSIC to listOf("googlevideo.com", "youtube.com", "youtube-nocookie.com")
        )
        for ((channel, hosts) in domains) for (host in hosts) {
            for (candidate in listOf("https://$host/song", "http://cdn.$host/song")) {
                assertEquals(candidate, trustedListenTogetherStreamUrl(channel, " $candidate "))
            }
            assertNull(trustedListenTogetherStreamUrl(channel, "https://$host.attacker.example/song"))
            assertNull(trustedListenTogetherStreamUrl(channel, "https://fake$host/song"))
        }
        for (url in listOf(null, "", " ", "file:///song", "content://media/song", "ftp://music.126.net/song", "invalid")) {
            assertNull(trustedListenTogetherStreamUrl(ListenTogetherChannels.NETEASE, url))
        }
        assertNull(trustedListenTogetherStreamUrl("unknown", "https://music.126.net/song"))
        assertNull(trustedListenTogetherStreamUrl(ListenTogetherChannels.NETEASE, "https://googlevideo.com/song"))
    }

    @Test
    fun `candidate limits preserve platform budget legacy fallback and trusted order`() {
        val netease = (0..3).map { "https://m$it.music.126.net/song" }
        assertEquals(netease.take(3), trustedListenTogetherStreamUrls(ListenTogetherChannels.NETEASE, netease, maxCount = 10))
        val bili = (0..2).map { "https://m$it.bilivideo.com/song" }
        assertEquals(bili.take(2), trustedListenTogetherStreamUrls(ListenTogetherChannels.BILIBILI, bili))
        val youtube = (0..1).map { "https://m$it.googlevideo.com/song" }
        assertEquals(youtube.take(1), trustedListenTogetherStreamUrls(ListenTogetherChannels.YOUTUBE_MUSIC, youtube))
        assertEquals(netease.take(1), trustedListenTogetherStreamUrls(ListenTogetherChannels.NETEASE, null, netease.first()))
        assertEquals(netease.take(2), trustedListenTogetherStreamUrls(ListenTogetherChannels.NETEASE, netease, netease.first(), maxCount = 2))
        assertTrue(trustedListenTogetherStreamUrls(ListenTogetherChannels.NETEASE, netease, maxCount = 0).isEmpty())
        assertTrue(trustedListenTogetherStreamUrls("unknown", netease).isEmpty())
    }
}
