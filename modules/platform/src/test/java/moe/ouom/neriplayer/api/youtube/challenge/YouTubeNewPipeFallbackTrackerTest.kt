package moe.ouom.neriplayer.api.youtube.challenge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YouTubeNewPipeFallbackTrackerTest {
    @Before
    fun resetTracker() {
        NewPipeFallbackTracker.reset()
    }

    @Test
    fun newPipeFallbackTrackerSignatureBlocksAfterThreshold() {
        val key = "https://music.youtube.com/player.js"
        assertFalse(NewPipeFallbackTracker.maybeSkipSignature(key))
        // 反混淆是拿固定正则套同一版 player.js, 结果是确定的; 等第二次样本等于让冷启动
        // 的头两首各白付一次三秒多的必然失败
        NewPipeFallbackTracker.recordSignatureFailure(key)
        assertTrue(NewPipeFallbackTracker.maybeSkipSignature(key))
    }

    @Test
    fun newPipeFallbackTrackerThrottlingBlocksAfterThreshold() {
        val key = "https://music.youtube.com/player.js"
        assertFalse(NewPipeFallbackTracker.maybeSkipThrottling(key))
        NewPipeFallbackTracker.recordThrottlingFailure(key)
        assertTrue(NewPipeFallbackTracker.maybeSkipThrottling(key))
    }
}
