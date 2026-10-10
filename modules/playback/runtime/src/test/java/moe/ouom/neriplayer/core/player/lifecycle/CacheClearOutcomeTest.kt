package moe.ouom.neriplayer.core.player.lifecycle

import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.Assert.assertEquals
import org.junit.Test

class CacheClearOutcomeTest {

    @Test
    fun `any failed removal or image clear is reported as partial`() {
        assertEquals(
            false to CoreCommonR.string.cache_clear_partial,
            cacheClearOutcome(MediaCacheClearCount(removed = 3, failed = 1), clearImage = false, imagesCleared = true)
        )
        assertEquals(
            false to CoreCommonR.string.cache_clear_partial,
            cacheClearOutcome(MediaCacheClearCount(), clearImage = true, imagesCleared = false)
        )
    }

    @Test
    fun `completed clears and empty caches keep their messages`() {
        assertEquals(
            true to CoreCommonR.string.cache_clear_complete,
            cacheClearOutcome(MediaCacheClearCount(removed = 2), clearImage = false, imagesCleared = true)
        )
        assertEquals(
            true to CoreCommonR.string.cache_clear_complete,
            cacheClearOutcome(MediaCacheClearCount(), clearImage = true, imagesCleared = true)
        )
        assertEquals(
            true to CoreCommonR.string.settings_cache_empty,
            cacheClearOutcome(MediaCacheClearCount(), clearImage = false, imagesCleared = true)
        )
    }
}
