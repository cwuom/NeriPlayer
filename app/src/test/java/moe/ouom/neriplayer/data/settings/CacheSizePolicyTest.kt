package moe.ouom.neriplayer.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheSizePolicyTest {

    @Test
    fun `slider end maps to unlimited cache`() {
        assertEquals(
            CacheSizePolicy.UNLIMITED_CACHE_SIZE_BYTES,
            CacheSizePolicy.fromSliderValue(CacheSizePolicy.CACHE_SIZE_SLIDER_UNLIMITED_VALUE)
        )
        assertEquals(
            CacheSizePolicy.CACHE_SIZE_SLIDER_UNLIMITED_VALUE,
            CacheSizePolicy.toSliderValue(CacheSizePolicy.UNLIMITED_CACHE_SIZE_BYTES)
        )
    }

    @Test
    fun `ten gigabytes remains the largest finite cache`() {
        val maxFiniteSlider = CacheSizePolicy.CACHE_SIZE_SLIDER_MAX_FINITE_MB

        assertEquals(
            CacheSizePolicy.MAX_FINITE_CACHE_SIZE_BYTES,
            CacheSizePolicy.fromSliderValue(maxFiniteSlider)
        )
        assertEquals(
            maxFiniteSlider,
            CacheSizePolicy.toSliderValue(CacheSizePolicy.MAX_FINITE_CACHE_SIZE_BYTES)
        )
    }

    @Test
    fun `small slider values keep the existing no cache option`() {
        assertEquals(0L, CacheSizePolicy.fromSliderValue(0f))
        assertEquals(0L, CacheSizePolicy.fromSliderValue(9.99f))
        assertEquals(
            10L * 1024L * 1024L,
            CacheSizePolicy.fromSliderValue(CacheSizePolicy.CACHE_SIZE_SLIDER_NO_CACHE_THRESHOLD_MB)
        )
    }

    @Test
    fun `invalid persisted values are clamped without losing unlimited sentinel`() {
        assertEquals(0L, CacheSizePolicy.normalizeCacheSizeBytes(-2L))
        assertEquals(
            CacheSizePolicy.MAX_FINITE_CACHE_SIZE_BYTES,
            CacheSizePolicy.normalizeCacheSizeBytes(Long.MAX_VALUE)
        )
        assertEquals(
            CacheSizePolicy.UNLIMITED_CACHE_SIZE_BYTES,
            CacheSizePolicy.normalizeCacheSizeBytes(CacheSizePolicy.UNLIMITED_CACHE_SIZE_BYTES)
        )
    }
}
