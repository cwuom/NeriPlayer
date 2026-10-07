package moe.ouom.neriplayer.ui.effect.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdvancedGlassLocalBlurBoundsIntersectionTest {

    private val bounds = AdvancedGlassLocalBlurBounds(left = 10f, top = 20f, right = 110f, bottom = 220f)

    @Test
    fun `overlapping rectangles keep only the shared area`() {
        assertEquals(
            AdvancedGlassLocalBlurBounds(left = 50f, top = 20f, right = 110f, bottom = 120f),
            bounds.intersect(left = 50f, top = 0f, right = 400f, bottom = 120f)
        )
    }

    @Test
    fun `a viewport that contains the bounds leaves them unchanged`() {
        assertEquals(bounds, bounds.intersect(left = 0f, top = 0f, right = 1080f, bottom = 2400f))
    }

    @Test
    fun `rectangles that only meet or miss horizontally have no intersection`() {
        assertNull(bounds.intersect(left = 110f, top = 20f, right = 200f, bottom = 220f))
        assertNull(bounds.intersect(left = 300f, top = 20f, right = 400f, bottom = 220f))
    }

    @Test
    fun `rectangles that overlap horizontally but miss vertically have no intersection`() {
        assertNull(bounds.intersect(left = 0f, top = 220f, right = 200f, bottom = 300f))
        assertNull(bounds.intersect(left = 0f, top = -50f, right = 200f, bottom = 0f))
    }
}
