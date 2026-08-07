package moe.ouom.neriplayer.ui.effect.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGlassOverscrollTest {
    @Test
    fun dragGetsProgressivelyMoreResistant() {
        val resistanceScale = 300f
        val first = dampedAdvancedGlassOverscrollOffset(75f, resistanceScale)
        val second = dampedAdvancedGlassOverscrollOffset(150f, resistanceScale)
        val third = dampedAdvancedGlassOverscrollOffset(225f, resistanceScale)

        assertTrue(first > 0f)
        assertTrue(second - first < first)
        assertTrue(third - second < second - first)
    }

    @Test
    fun dragIsSymmetricAndContinuesWithIncreasingResistance() {
        val resistanceScale = 100f

        val first = dampedAdvancedGlassOverscrollOffset(100f, resistanceScale)
        val second = dampedAdvancedGlassOverscrollOffset(200f, resistanceScale)
        val third = dampedAdvancedGlassOverscrollOffset(300f, resistanceScale)

        assertEquals(100f * kotlin.math.ln(2f), first, 0.001f)
        assertEquals(-third, dampedAdvancedGlassOverscrollOffset(-300f, resistanceScale), 0.001f)
        assertTrue(third > second)
        assertTrue(third - second < second - first)
    }

    @Test
    fun animatedOffsetRestoresEquivalentRawDrag() {
        val resistanceScale = 100f
        listOf(-300f, -150f, -30f, 30f, 150f, 300f).forEach { rawDrag ->
            val offset = dampedAdvancedGlassOverscrollOffset(rawDrag, resistanceScale)
            val restored = restoredAdvancedGlassOverscrollDrag(offset, resistanceScale)

            assertEquals(rawDrag, restored, 0.01f)
        }
    }
}
