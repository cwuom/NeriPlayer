package moe.ouom.neriplayer.data.local.media.curation

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedCoverPreparerTest {

    @Test
    fun `fit keeps aspect ratio and scales by the long edge`() {
        assertEquals(
            EmbeddedCoverDimensions(500, 500),
            EmbeddedCoverPreparer.fitWithinEdge(3000, 3000, 500)
        )
        assertEquals(
            EmbeddedCoverDimensions(1000, 563),
            EmbeddedCoverPreparer.fitWithinEdge(1920, 1080, 1000)
        )
        assertEquals(
            EmbeddedCoverDimensions(281, 500),
            EmbeddedCoverPreparer.fitWithinEdge(1080, 1920, 500)
        )
    }

    @Test
    fun `fit never upscales and leaves original preset untouched`() {
        assertEquals(
            EmbeddedCoverDimensions(320, 320),
            EmbeddedCoverPreparer.fitWithinEdge(320, 320, 1000)
        )
        assertEquals(
            EmbeddedCoverDimensions(4000, 3000),
            EmbeddedCoverPreparer.fitWithinEdge(4000, 3000, null)
        )
        assertEquals(
            EmbeddedCoverDimensions(0, 0),
            EmbeddedCoverPreparer.fitWithinEdge(0, 0, 500)
        )
    }

    @Test
    fun `decode sample size stays at or above the target edge`() {
        assertEquals(1, EmbeddedCoverPreparer.decodeSampleSize(900, 900, 500))
        assertEquals(2, EmbeddedCoverPreparer.decodeSampleSize(1000, 1000, 500))
        assertEquals(4, EmbeddedCoverPreparer.decodeSampleSize(3000, 2000, 500))
        assertEquals(1, EmbeddedCoverPreparer.decodeSampleSize(3000, 3000, null))
    }

    @Test
    fun `presets expose expected edges`() {
        assertEquals(500, EmbeddedCoverSizePreset.COMPACT.maxEdgePx)
        assertEquals(1000, EmbeddedCoverSizePreset.STANDARD.maxEdgePx)
        assertEquals(null, EmbeddedCoverSizePreset.ORIGINAL.maxEdgePx)
    }
}
