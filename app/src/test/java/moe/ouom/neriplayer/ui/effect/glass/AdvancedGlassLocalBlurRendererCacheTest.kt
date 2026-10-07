package moe.ouom.neriplayer.ui.effect.glass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class AdvancedGlassLocalBlurRendererCacheTest {

    @Test
    fun `the renderer is reused only while the cache key stays the same`() {
        val backdrop = AdvancedGlassBackdrop()

        val first = backdrop.localBlurRenderer(cacheKey = 7)
        assertSame(first, backdrop.localBlurRenderer(cacheKey = 7))

        val replaced = backdrop.localBlurRenderer(cacheKey = 8)
        assertNotSame(first, replaced)
        assertSame(replaced, backdrop.localBlurRenderer(cacheKey = 8))
    }

    @Test
    fun `invalidation drops the cached renderer and unfreezes the frame`() {
        val backdrop = AdvancedGlassBackdrop()
        val cached = backdrop.localBlurRenderer(cacheKey = 7)
        backdrop.freezeLocalBlurFrame = true

        backdrop.invalidateLocalBlurRenderer()

        assertFalse(backdrop.freezeLocalBlurFrame)
        assertNotSame(cached, backdrop.localBlurRenderer(cacheKey = 7))
    }
}
