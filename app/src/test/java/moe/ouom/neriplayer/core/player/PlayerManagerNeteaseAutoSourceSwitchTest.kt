package moe.ouom.neriplayer.core.player

import moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerNeteaseAutoSourceSwitchTest {

    @Test
    fun buildNeteaseAutoBiliCacheKey_usesBiliAutoNamespace() {
        val key = buildNeteaseAutoBiliCacheKey(
            bvid = "BV1UyuRz5ERv",
            cid = 31144021363L,
            selectedStream = biliStream(id = 30280)
        )

        assertTrue(key.startsWith("bili-auto-"))
        assertFalse(key.startsWith("netease-"))
    }

    @Test
    fun buildNeteaseAutoBiliCacheKey_isStableForSameStream() {
        val stream = biliStream(id = 30280)
        val first = buildNeteaseAutoBiliCacheKey("BV1UyuRz5ERv", 31144021363L, stream)
        val second = buildNeteaseAutoBiliCacheKey("BV1UyuRz5ERv", 31144021363L, stream)

        assertEquals(first, second)
    }

    @Test
    fun buildNeteaseAutoBiliCacheKey_separatesDifferentStreams() {
        val highKey = buildNeteaseAutoBiliCacheKey(
            bvid = "BV1UyuRz5ERv",
            cid = 31144021363L,
            selectedStream = biliStream(id = 30280)
        )
        val mediumKey = buildNeteaseAutoBiliCacheKey(
            bvid = "BV1UyuRz5ERv",
            cid = 31144021363L,
            selectedStream = biliStream(id = 30232, bitrateKbps = 92)
        )

        assertNotEquals(highKey, mediumKey)
    }

    private fun biliStream(
        id: Int?,
        bitrateKbps: Int = 200,
        qualityTag: String? = null
    ): BiliAudioStreamInfo {
        val suffix = id?.toString() ?: qualityTag ?: bitrateKbps.toString()
        return BiliAudioStreamInfo(
            id = id,
            mimeType = "audio/mp4",
            bitrateKbps = bitrateKbps,
            qualityTag = qualityTag,
            url = "https://upos.example.bilivideo.com/$suffix.m4s"
        )
    }
}
