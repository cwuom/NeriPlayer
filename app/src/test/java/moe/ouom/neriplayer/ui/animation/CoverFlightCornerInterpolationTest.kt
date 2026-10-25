package moe.ouom.neriplayer.ui.animation

import android.graphics.Matrix
import android.graphics.Region
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape
import moe.ouom.neriplayer.ui.theme.addSmoothedRoundRect
import moe.ouom.neriplayer.ui.theme.supportsContinuousCornerOutline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverFlightCornerInterpolationTest {
    @Test
    fun materialFlightInterpolatesFromActualEightDpCircularMiniToContinuousCover() {
        val state = SharedCoverFlightState()
        state.reportSource(SourceBounds, 8f, null, continuousCorners = false)
        state.reportTarget(Rect(20f, 132f, 391f, 503f), 24f)

        for ((fraction, radius, smoothing) in listOf(
            Triple(0f, 8f, 0f), Triple(0.5f, 16f, 0.5f), Triple(1f, 24f, 1f)
        )) {
            runBlocking { state.progress.snapTo(fraction) }
            val frame = requireNotNull(state.currentFrame())
            assertEquals(radius, frame.cornerPx, 0f)
            assertEquals(smoothing, frame.cornerSmoothing, 0f)
            assertTrue("旧布尔字段仍描述目标角型", frame.continuousCorners)
        }
    }

    @Test
    fun cleanFlightInterpolatesItsContinuousMiniCornersIntoCircularLyricsHeader() {
        val state = SharedCoverFlightState()
        state.reportSource(SourceBounds, 10f, null)
        val bounds = Rect(80f, 56f, 120f, 96f)
        val coordinates = mock(LayoutCoordinates::class.java)
        `when`(coordinates.isAttached).thenReturn(true)
        for (point in listOf(bounds.topLeft, bounds.topRight, bounds.bottomLeft, bounds.bottomRight)) {
            `when`(coordinates.localToWindow(point)).thenReturn(point)
        }
        state.reportVisualTarget(Any(), coordinates) {
            CoverFlightFrame(bounds, 10f, continuousCorners = false)
        }

        for ((fraction, smoothing) in listOf(0f to 1f, 0.5f to 0.5f, 1f to 0f)) {
            runBlocking { state.progress.snapTo(fraction) }
            val frame = requireNotNull(state.currentFrame())
            assertEquals(10f, frame.cornerPx, 0f)
            assertEquals(smoothing, frame.cornerSmoothing, 0f)
            assertFalse("目标 header 保留普通圆角契约", frame.continuousCorners)
        }
    }

    @Test
    fun linkedFlightFreezesSourceCornerGeometryUntilTheMiniPlayerCanReportAtRest() {
        val state = SharedCoverFlightState()
        state.reportSource(SourceBounds, 8f, ImageBitmap(2, 2), continuousCorners = false)
        state.reportTarget(Rect(20f, 132f, 391f, 503f), 24f)
        state.beginLinked()
        val movingSource = Rect(28f, 710f, 64f, 746f)
        state.reportSource(movingSource, 10f, null)

        val frozen = requireNotNull(state.currentFrame())
        assertEquals(SourceBounds, frozen.bounds)
        assertEquals(8f, frozen.cornerPx, 0f)
        assertEquals(0f, frozen.cornerSmoothing, 0f)

        state.endLinked()
        state.reportSource(movingSource, 10f, null)
        val next = requireNotNull(state.currentFrame())
        assertEquals(movingSource, next.bounds)
        assertEquals(10f, next.cornerPx, 0f)
        assertEquals(1f, next.cornerSmoothing, 0f)
    }

    @Test
    fun smoothingEndpointsUseTheSamePathsAsActualMaterialAndCleanMiniShapes() {
        val material = RoundedCornerShape(8.dp).createOutline(Square, LayoutDirection.Ltr, Density(1f))
        val clean = ContinuousRoundedCornerShape(10.dp).createOutline(Square, LayoutDirection.Ltr, Density(1f))

        assertSameRegion(outlinePath(material), smoothedPath(Square, 8f, 0f))
        assertSameRegion(outlinePath(clean), smoothedPath(Square, 10f, 1f))
    }

    @Test
    fun intermediateCornerCurveMovesBetweenCircularAndContinuousCoverage() {
        val circular = region(smoothedPath(Square, 10f, 0f))
        val middle = region(smoothedPath(Square, 10f, 0.5f))
        val continuous = region(smoothedPath(Square, 10f, 1f))

        // 对角线边界依次约为 2.929、2.897 和 2.847，放大后检查两条独立的覆盖边界
        assertFalse(circular.contains(290, 290))
        assertTrue(middle.contains(290, 290))
        assertTrue(continuous.contains(290, 290))
        assertFalse(middle.contains(287, 287))
        assertTrue(continuous.contains(287, 287))
    }

    @Test
    @Config(sdk = [28, 29])
    fun legacyAndroidFlightCornersMatchTheActualCircularShapeFallback() {
        val actual = ContinuousRoundedCornerShape(10.dp).createOutline(Square, LayoutDirection.Ltr, Density(1f))
        assertTrue("旧 Android 的实际 Clean Shape 使用圆角 fallback", actual is Outline.Rounded)
        val smoothing = if (supportsContinuousCornerOutline(Square.width, Square.height)) 1f else 0f

        assertSameRegion(outlinePath(actual), smoothedPath(Square, 10f, smoothing))
    }

    @Test
    fun oversizedFlightCornersMatchTheActualShapeFallbackOnEitherAxis() {
        for (size in listOf(Size(8192f, 40f), Size(40f, 8192f))) {
            val actual = ContinuousRoundedCornerShape(10.dp).createOutline(size, LayoutDirection.Ltr, Density(1f))
            assertTrue("超大 Shape 必须保留圆角 fallback size=$size", actual is Outline.Rounded)
            val smoothing = if (supportsContinuousCornerOutline(size.width, size.height)) 1f else 0f
            assertSameRegion(outlinePath(actual), smoothedPath(size, 10f, smoothing))
        }
        val belowLimit = ContinuousRoundedCornerShape(10.dp).createOutline(
            Size(8191f, 40f), LayoutDirection.Ltr, Density(1f)
        )
        assertTrue("有效尺寸仍保留原连续曲率", belowLimit is Outline.Generic)
    }

    private fun smoothedPath(size: Size, corner: Float, smoothing: Float): Path = Path().apply {
        addSmoothedRoundRect(size.width, size.height, corner, smoothing)
    }

    private fun outlinePath(outline: Outline): Path = when (outline) {
        is Outline.Generic -> outline.path
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    }

    private fun region(path: Path): Region {
        val scaled = android.graphics.Path(path.asAndroidPath())
        scaled.transform(Matrix().apply { setScale(100f, 100f) })
        return Region().apply { setPath(scaled, Region(0, 0, 1_000_000, 1_000_000)) }
    }

    private fun assertSameRegion(expected: Path, actual: Path) {
        val difference = region(actual).apply { op(region(expected), Region.Op.XOR) }
        assertTrue("交接端点必须沿用实际 Shape 的几何轮廓", difference.isEmpty)
    }

    private companion object {
        val SourceBounds = Rect(20f, 700f, 60f, 740f)
        val Square = Size(40f, 40f)
    }
}
