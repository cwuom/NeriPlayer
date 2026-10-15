package moe.ouom.neriplayer.data.local.media.curation

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `exif orientations map to the pixel transform that makes artwork upright`() {
        assertEquals(CoverOrientation.NORMAL, CoverOrientation.fromExif(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(CoverOrientation.NORMAL, CoverOrientation.fromExif(ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals(CoverOrientation(90, false), CoverOrientation.fromExif(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(CoverOrientation(180, false), CoverOrientation.fromExif(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(CoverOrientation(270, false), CoverOrientation.fromExif(ExifInterface.ORIENTATION_ROTATE_270))
        assertEquals(CoverOrientation(0, true), CoverOrientation.fromExif(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
        assertEquals(CoverOrientation(180, true), CoverOrientation.fromExif(ExifInterface.ORIENTATION_FLIP_VERTICAL))
        assertEquals(CoverOrientation(90, true), CoverOrientation.fromExif(ExifInterface.ORIENTATION_TRANSPOSE))
        assertEquals(CoverOrientation(270, true), CoverOrientation.fromExif(ExifInterface.ORIENTATION_TRANSVERSE))
        assertTrue(CoverOrientation.NORMAL.isIdentity)
        assertFalse(CoverOrientation(0, true).isIdentity)
    }

    @Test
    fun `presets expose expected edges`() {
        assertEquals(500, EmbeddedCoverSizePreset.COMPACT.maxEdgePx)
        assertEquals(1000, EmbeddedCoverSizePreset.STANDARD.maxEdgePx)
        assertEquals(null, EmbeddedCoverSizePreset.ORIGINAL.maxEdgePx)
    }
}
