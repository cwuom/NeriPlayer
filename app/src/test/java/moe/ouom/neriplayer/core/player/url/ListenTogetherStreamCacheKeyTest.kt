package moe.ouom.neriplayer.core.player.url

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherStreamCacheKeyTest {

    @Test
    fun `shared stream cache key isolates track and direct URL without retaining the URL`() {
        val firstUrl = "https://m701.music.126.net/audio.mp3?token=one"
        val secondUrl = "https://m702.music.126.net/audio.mp3?token=two"
        val firstKey = listenTogetherStreamCacheKey("netease:1", firstUrl)
        val differentUrlKey = listenTogetherStreamCacheKey("netease:1", secondUrl)
        val differentTrackKey = listenTogetherStreamCacheKey("netease:2", firstUrl)

        assertTrue(firstKey.startsWith("$LISTEN_TOGETHER_STREAM_CACHE_KEY_PREFIX-"))
        assertFalse(firstKey.contains(firstUrl))
        assertNotEquals(firstKey, differentUrlKey)
        assertNotEquals(firstKey, differentTrackKey)
    }
}
