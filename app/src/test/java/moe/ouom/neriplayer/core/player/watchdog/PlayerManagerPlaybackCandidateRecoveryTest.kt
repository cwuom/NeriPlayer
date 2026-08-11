package moe.ouom.neriplayer.core.player.watchdog

import org.junit.Assert.assertFalse
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
}
