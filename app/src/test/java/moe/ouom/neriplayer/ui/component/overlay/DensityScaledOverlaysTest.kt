package moe.ouom.neriplayer.ui.component.overlay

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DensityScaledOverlaysTest {
    @Test
    fun `overlay scaling shrinks its incoming width constraint`() {
        assertEquals(
            1062,
            resolveScaledOverlaySurfaceMaxWidth(
                availableWidthPx = 1118,
                minimumWidthPx = 0,
                surfaceScale = 0.95f
            )
        )
    }

    @Test
    fun `only a non-unit scale constrains an overlay surface`() {
        assertFalse(shouldScaleOverlaySurface(1f))
        assertTrue(shouldScaleOverlaySurface(0.95f))
    }

    @Test
    fun `overlay scaling preserves the caller minimum width`() {
        assertEquals(
            1000,
            resolveScaledOverlaySurfaceMaxWidth(
                availableWidthPx = 1118,
                minimumWidthPx = 1000,
                surfaceScale = 0.8f
            )
        )
    }

    @Test
    fun `overlay scaling never expands past its incoming constraint`() {
        assertEquals(
            1118,
            resolveScaledOverlaySurfaceMaxWidth(
                availableWidthPx = 1118,
                minimumWidthPx = 0,
                surfaceScale = 1.1f
            )
        )
        assertEquals(
            Constraints.Infinity,
            resolveScaledOverlaySurfaceMaxWidth(
                availableWidthPx = Constraints.Infinity,
                minimumWidthPx = 0,
                surfaceScale = 0.95f
            )
        )
    }
}
