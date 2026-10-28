package moe.ouom.neriplayer.ui.screen.nowplaying

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import moe.ouom.neriplayer.ui.navigation.LocalPredictiveBackPreviewEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoreOptionsSheetPredictiveBackRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var hostView: View
    private var dismissals = 0
    private val gestureInput = GestureInput()

    @Test
    fun `short sheet keeps covering the window bottom during predictive back`() =
        assertWindowBottomCovered(tall = false)

    @Test
    fun `tall sheet keeps covering the window bottom during predictive back`() =
        assertWindowBottomCovered(tall = true)

    @Test
    fun `sheet predictive back shrinks symmetrically without horizontal drift`() {
        setSurface(tall = true)
        val initial = render()
        val idle = initial.surfaceWidthAndCenter()
        val idleHeight = initial.surfaceRows().count()
        initial.recycle()

        listOf(NavigationEvent.EDGE_LEFT, NavigationEvent.EDGE_RIGHT).forEach { edge ->
            startGesture(edge)
            val preview = render().surfaceWidthAndCenter()
            assertTrue("手势必须确实缩小面板", preview.first < idle.first)
            assertEquals("面板缩放必须保持对称居中", idle.second, preview.second, 1f)

            cancelGesture(idleHeight, idle)
            assertEquals(idle, render().surfaceWidthAndCenter())
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h600dp-mdpi")
    fun `sheet content keeps circles round during predictive back and cancellation`() {
        setSurface(tall = true, withAspectMarker = true)
        val initial = render()
        val idle = initial.surfaceWidthAndCenter()
        val idleHeight = initial.surfaceRows().count()
        val marker = initial.markerSize()
        initial.recycle()
        assertEquals("夹具中的图形必须是圆形", marker.first.toFloat(), marker.second.toFloat(), 1f)

        listOf(NavigationEvent.EDGE_LEFT, NavigationEvent.EDGE_RIGHT).forEach { edge ->
            startGesture(edge)
            val preview = render()
            val scaledMarker = preview.markerSize()
            preview.recycle()
            assertTrue("手势必须确实缩小内容", scaledMarker.first < marker.first)
            assertEquals("面板内容应等比缩放，圆形不能变成椭圆", scaledMarker.first.toFloat(), scaledMarker.second.toFloat(), 1f)

            cancelGesture(idleHeight, idle)
            val restored = render()
            assertEquals("取消后内容应恢复原尺寸", marker, restored.markerSize())
            restored.recycle()
        }
    }

    @Test
    fun `clicking the real drag handle hides the sheet exactly once`() {
        setSurface(tall = false)
        val description = composeRule.activity.getString(
            androidx.compose.material3.R.string.m3c_bottom_sheet_drag_handle_description
        )
        composeRule.onNodeWithContentDescription(description).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { dismissals == 1 }
        composeRule.waitForIdle()

        assertEquals(1, dismissals)
        val hidden = render()
        assertTrue("点击拖柄后真实面板应完全隐藏", hidden.surfaceRows().isEmpty())
        hidden.recycle()
    }

    private fun assertWindowBottomCovered(tall: Boolean) {
        setSurface(tall)
        val initial = render()
        val idleHeight = initial.surfaceRows().count()
        val idle = initial.surfaceWidthAndCenter()
        initial.recycle()
        assertTrue("夹具必须显示真实面板", idleHeight > 0)

        listOf(NavigationEvent.EDGE_LEFT, NavigationEvent.EDGE_RIGHT).forEach { edge ->
            startGesture(edge)
            val preview = render()
            assertTrue(
                "预测性返回不能把面板底边拉进窗口: tall=$tall edge=$edge",
                preview.getPixel(preview.width / 2, preview.height - 2) == android.graphics.Color.RED
            )

            cancelGesture(idleHeight, idle)
            assertEquals(idleHeight, render().surfaceRows().count())
            assertEquals(0, dismissals)
        }
    }

    private fun setSurface(tall: Boolean, withAspectMarker: Boolean = false) {
        composeRule.setContent {
            CompositionLocalProvider(LocalPredictiveBackPreviewEnabled provides true) {
                MaterialTheme(colorScheme = lightColorScheme(surface = Color.Red)) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    val scope = rememberCoroutineScope()
                    val owner = remember(sheetState, scope) {
                        MoreOptionsSheetOwner(scope, sheetState::hide, sheetState::expand) { dismissals++ }
                    }
                    MoreOptionsSheetSurface(owner, sheetState, remember { SnackbarHostState() }) {
                        hostView = LocalView.current
                        val dispatcher = requireNotNull(hostView.findViewTreeNavigationEventDispatcherOwner())
                            .navigationEventDispatcher
                        DisposableEffect(dispatcher) {
                            dispatcher.addInput(gestureInput)
                            onDispose { dispatcher.removeInput(gestureInput) }
                        }
                        val bodyHeight = if (tall) LocalWindowInfo.current.containerDpSize.height * 0.75f else 160.dp
                        Box(Modifier.fillMaxWidth().height(bodyHeight).background(Color.Red)) {
                            if (withAspectMarker) {
                                ComposeCanvas(Modifier.padding(start = 48.dp, top = 32.dp).size(64.dp)) {
                                    drawCircle(Color.Blue)
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun startGesture(edge: Int) {
        composeRule.runOnIdle {
            assertTrue("面板窗口必须有启用的返回处理器", gestureInput.hasEnabledHandlers)
            gestureInput.start(edge)
        }
        composeRule.runOnIdle { gestureInput.progress(edge, 1f) }
        composeRule.waitForIdle()
    }

    private fun cancelGesture(idleHeight: Int, idle: Pair<Int, Float>) {
        composeRule.runOnIdle { gestureInput.cancel() }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            val bitmap = render()
            try {
                bitmap.surfaceRows().count() == idleHeight && bitmap.surfaceWidthAndCenter() == idle
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun render(): Bitmap = composeRule.runOnIdle {
        Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            hostView.draw(Canvas(bitmap))
        }
    }

    private fun Bitmap.surfaceRows(): List<Int> = (0 until height).filter { y ->
        getPixel(width / 2, y) == android.graphics.Color.RED
    }

    private fun Bitmap.surfaceWidthAndCenter(): Pair<Int, Float> {
        val rows = surfaceRows()
        assertTrue("面板必须可见", rows.isNotEmpty())
        val y = (rows.first() + rows.last()) / 2
        val columns = (0 until width).filter { x -> getPixel(x, y) == android.graphics.Color.RED }
        return columns.last() - columns.first() + 1 to (columns.first() + columns.last()) / 2f
    }

    private fun Bitmap.markerSize(): Pair<Int, Int> {
        var left = width
        var right = -1
        var top = height
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (getPixel(x, y) == android.graphics.Color.BLUE) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue("真实 Surface 内必须存在圆形内容", right >= left && bottom >= top)
        return right - left + 1 to bottom - top + 1
    }

    private class GestureInput : NavigationEventInput() {
        fun start(edge: Int) = dispatchOnBackStarted(NavigationEvent(swipeEdge = edge))
        fun progress(edge: Int, progress: Float) =
            dispatchOnBackProgressed(NavigationEvent(swipeEdge = edge, progress = progress))
        fun cancel() = dispatchOnBackCancelled()
    }
}
