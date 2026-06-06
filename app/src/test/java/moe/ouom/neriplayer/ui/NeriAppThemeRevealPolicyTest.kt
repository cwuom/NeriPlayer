package moe.ouom.neriplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriAppThemeRevealPolicyTest {

    @Test
    fun `large theme reveal snapshot is downsampled under max dimension`() {
        val dimensions = resolveThemeRevealSnapshotDimensions(
            width = 3200,
            height = 1440
        )

        assertEquals(1080, dimensions.width)
        assertEquals(486, dimensions.height)
        assertTrue(maxOf(dimensions.width, dimensions.height) <= 1080)
    }

    @Test
    fun `small theme reveal snapshot keeps original size`() {
        val dimensions = resolveThemeRevealSnapshotDimensions(
            width = 900,
            height = 600
        )

        assertEquals(900, dimensions.width)
        assertEquals(600, dimensions.height)
    }
}
