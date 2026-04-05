package moe.ouom.neriplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriAppThemeRevealTest {

    @Test
    fun `resolveThemeRevealSnapshotDimensions keeps smaller layouts unchanged`() {
        val snapshot = resolveThemeRevealSnapshotDimensions(
            width = 720,
            height = 1280,
            maxDimensionPx = 2000
        )

        assertEquals(720, snapshot.width)
        assertEquals(1280, snapshot.height)
    }

    @Test
    fun `resolveThemeRevealSnapshotDimensions downsamples large layouts proportionally`() {
        val snapshot = resolveThemeRevealSnapshotDimensions(
            width = 2400,
            height = 1080,
            maxDimensionPx = 1080
        )

        assertTrue(maxOf(snapshot.width, snapshot.height) <= 1080)
        val sourceAspectRatio = 2400f / 1080f
        val snapshotAspectRatio = snapshot.width.toFloat() / snapshot.height.toFloat()
        assertTrue(kotlin.math.abs(sourceAspectRatio - snapshotAspectRatio) < 0.01f)
    }
}
