package moe.ouom.neriplayer.core.player.watchdog

import moe.ouom.neriplayer.core.player.url.offlineCacheKeyFromUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerPlaybackCandidateRecoveryTest {

    @Test
    fun `startup stall does not invalidate the current cache`() {
        assertFalse(
            shouldInvalidateStalePlaybackCache(
                invalidateCurrentCache = false,
                staleCacheKey = "current-cache",
                nextCacheKey = "fallback-cache"
            )
        )
    }

    @Test
    fun `candidate recovery keeps a cache key that the next candidate reuses`() {
        assertFalse(
            shouldInvalidateStalePlaybackCache(
                invalidateCurrentCache = true,
                staleCacheKey = "shared-cache",
                nextCacheKey = "shared-cache"
            )
        )
    }

    @Test
    fun `cache recovery removes a stale cache key before a different candidate starts`() {
        assertTrue(
            shouldInvalidateStalePlaybackCache(
                invalidateCurrentCache = true,
                staleCacheKey = "stale-cache",
                nextCacheKey = "fallback-cache"
            )
        )
    }

    @Test
    fun `startup stall invalidates an offline cache only on the first recovery`() {
        val url = "http://offline.cache/bili-277912748-1320700970-hires"

        assertTrue(
            shouldInvalidateOfflineCacheForStartupStall(
                recoveryAttempt = 1,
                currentUrl = url
            )
        )
        assertFalse(
            shouldInvalidateOfflineCacheForStartupStall(
                recoveryAttempt = 2,
                currentUrl = url
            )
        )
        assertFalse(
            shouldInvalidateOfflineCacheForStartupStall(
                recoveryAttempt = 1,
                currentUrl = "https://example.com/audio.m4a"
            )
        )
    }

    @Test
    fun `offline cache key parser keeps only the synthetic resource key`() {
        assertEquals(
            "bili-277912748-1320700970-hires",
            offlineCacheKeyFromUrl("http://offline.cache/bili-277912748-1320700970-hires")
        )
    }
}
