package moe.ouom.neriplayer.ui.effect.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGlassOverscrollTest {
    @Test
    fun dragGetsProgressivelyMoreResistant() {
        val dragRange = 300f
        val first = dampedAdvancedGlassOverscrollOffset(75f, dragRange)
        val second = dampedAdvancedGlassOverscrollOffset(150f, dragRange)
        val third = dampedAdvancedGlassOverscrollOffset(225f, dragRange)

        assertTrue(first > 0f)
        assertTrue(second - first < first)
        assertTrue(third - second < second - first)
    }

    @Test
    fun dragIsSymmetricAndCappedAtOneThirdOfRange() {
        val dragRange = 300f

        assertEquals(100f, dampedAdvancedGlassOverscrollOffset(300f, dragRange), 0.001f)
        assertEquals(-100f, dampedAdvancedGlassOverscrollOffset(-300f, dragRange), 0.001f)
        assertEquals(100f, dampedAdvancedGlassOverscrollOffset(600f, dragRange), 0.001f)
    }

    @Test
    fun animatedOffsetRestoresEquivalentRawDrag() {
        val dragRange = 300f
        listOf(-300f, -150f, -30f, 30f, 150f, 300f).forEach { rawDrag ->
            val offset = dampedAdvancedGlassOverscrollOffset(rawDrag, dragRange)
            val restored = restoredAdvancedGlassOverscrollDrag(offset, dragRange)

            assertEquals(rawDrag, restored, 0.01f)
        }
    }
}
