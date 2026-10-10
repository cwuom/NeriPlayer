package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

class AdvancedGlassWindowScaleTest {

    @Test
    fun `detached or empty coordinates have no window scale`() {
        assertEquals(Offset.Unspecified, coordinates(attached = false).advancedGlassScaleInWindow())
        assertEquals(Offset.Unspecified, coordinates(size = IntSize(0, 40)).advancedGlassScaleInWindow())
        assertEquals(Offset.Unspecified, coordinates(size = IntSize(80, 0)).advancedGlassScaleInWindow())
    }

    @Test
    fun `window scale is measured independently along both local axes`() {
        val scaled = coordinates(
            size = IntSize(80, 40),
            scaleX = 1.5f,
            scaleY = 0.5f,
            translation = Offset(12f, 30f)
        )

        assertEquals(Offset(1.5f, 0.5f), scaled.advancedGlassScaleInWindow())
    }

    private fun coordinates(
        attached: Boolean = true,
        size: IntSize = IntSize(80, 40),
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        translation: Offset = Offset.Zero
    ): LayoutCoordinates = WindowCoordinates(attached, size) { local ->
        Offset(translation.x + local.x * scaleX, translation.y + local.y * scaleY)
    }

    private class WindowCoordinates(
        override val isAttached: Boolean,
        override val size: IntSize,
        private val toWindow: (Offset) -> Offset
    ) : LayoutCoordinates by mock(LayoutCoordinates::class.java) {
        override fun localToWindow(relativeToLocal: Offset): Offset = toWindow(relativeToLocal)
    }
}
