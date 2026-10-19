package moe.ouom.neriplayer.ui.screen.nowplaying

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

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
    fun `predictive back keeps the translated sheet body intact from either edge`() {
        setSurface()
        val idleHeight = render().surfaceRows().count()
        assertTrue("夹具必须显示短面板", idleHeight > 0)

        listOf(NavigationEvent.EDGE_LEFT, NavigationEvent.EDGE_RIGHT).forEach { edge ->
            startGesture(edge)

            val gestureHeight = render().surfaceRows().count()
            assertTrue("手势必须确实缩小面板", gestureHeight < idleHeight)
            assertTrue(
                "预测性返回只能缩放面板，不能按面板的竖直位移裁掉下缘: edge=$edge idle=$idleHeight gesture=$gestureHeight",
                gestureHeight >= idleHeight * 0.85f
            )

            composeRule.runOnIdle { gestureInput.cancel() }
            composeRule.waitForIdle()
            assertEquals(idleHeight, render().surfaceRows().count())
            assertEquals(0, dismissals)
        }
    }

    private fun setSurface() {
        composeRule.setContent {
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
                    Box(Modifier.fillMaxWidth().height(160.dp).background(Color.Red))
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

    private fun render(): Bitmap = composeRule.runOnIdle {
        Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            hostView.draw(Canvas(bitmap))
        }
    }

    private fun Bitmap.surfaceRows(): List<Int> = (0 until height).filter { y ->
        getPixel(width / 2, y) == android.graphics.Color.RED
    }

    private class GestureInput : NavigationEventInput() {
        fun start(edge: Int) = dispatchOnBackStarted(NavigationEvent(swipeEdge = edge))
        fun progress(edge: Int, progress: Float) =
            dispatchOnBackProgressed(NavigationEvent(swipeEdge = edge, progress = progress))
        fun cancel() = dispatchOnBackCancelled()
    }
}
