package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingCoverPreviewTransformTest {

    @Test
    fun `zoom and pan stay within preview bounds`() {
        val result = updateCoverPreviewTransform(
            current = CoverPreviewTransform(),
            zoomChange = 10f,
            panChange = Offset(9_000f, -9_000f),
            viewportSizePx = 1_000f,
            fittedContentSizePx = Size(1_000f, 1_000f)
        )

        assertEquals(5f, result.scale, 0.001f)
        assertEquals(2_000f, result.offset.x, 0.001f)
        assertEquals(-2_000f, result.offset.y, 0.001f)
    }

    @Test
    fun `zooming back to baseline recenters artwork`() {
        val result = updateCoverPreviewTransform(
            current = CoverPreviewTransform(
                scale = 2f,
                offset = Offset(240f, -160f)
            ),
            zoomChange = 0.1f,
            panChange = Offset(40f, 40f),
            viewportSizePx = 1_000f,
            fittedContentSizePx = Size(1_000f, 1_000f)
        )

        assertEquals(CoverPreviewTransform(), result)
    }

    @Test
    fun `landscape artwork uses independent horizontal and vertical bounds`() {
        val fittedContentSize = fitCoverPreviewContentSize(
            viewportSizePx = 1_000f,
            intrinsicSizePx = Size(2_000f, 1_000f)
        )
        val result = updateCoverPreviewTransform(
            current = CoverPreviewTransform(),
            zoomChange = 2f,
            panChange = Offset(9_000f, 9_000f),
            viewportSizePx = 1_000f,
            fittedContentSizePx = fittedContentSize
        )

        assertEquals(Size(1_000f, 500f), fittedContentSize)
        assertEquals(500f, result.offset.x, 0.001f)
        assertEquals(0f, result.offset.y, 0.001f)
    }

    @Test
    fun `updated artwork aspect ratio clamps an existing offset`() {
        val result = updateCoverPreviewTransform(
            current = CoverPreviewTransform(
                scale = 2f,
                offset = Offset(500f, 500f)
            ),
            zoomChange = 1f,
            panChange = Offset.Zero,
            viewportSizePx = 1_000f,
            fittedContentSizePx = Size(1_000f, 500f)
        )

        assertEquals(500f, result.offset.x, 0.001f)
        assertEquals(0f, result.offset.y, 0.001f)
    }

    @Test
    fun `unknown artwork size falls back to square viewport bounds`() {
        val fittedContentSize = fitCoverPreviewContentSize(
            viewportSizePx = 1_000f,
            intrinsicSizePx = Size.Unspecified
        )

        assertEquals(Size(1_000f, 1_000f), fittedContentSize)
    }

    @Test
    fun `double tap toggles between enlarged and reset states`() {
        val enlarged = toggleCoverPreviewZoom(CoverPreviewTransform())
        val reset = toggleCoverPreviewZoom(enlarged)

        assertEquals(2f, enlarged.scale, 0.001f)
        assertEquals(Offset.Zero, enlarged.offset)
        assertEquals(CoverPreviewTransform(), reset)
    }
}
