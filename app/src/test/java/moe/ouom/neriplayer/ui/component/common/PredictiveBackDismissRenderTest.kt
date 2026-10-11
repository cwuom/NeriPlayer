package moe.ouom.neriplayer.ui.component.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PredictiveBackDismissRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var hostView: View

    private val dispatcher get() = composeRule.activity.onBackPressedDispatcher

    private fun setSurface() {
        composeRule.setContent {
            hostView = LocalView.current
            Box(Modifier.size(300.dp, 200.dp).background(Color.Blue)) {
                val state = rememberPredictiveDismissState()
                PredictiveDismissHandler(state = state, onDismiss = {})
                Box(
                    Modifier
                        .fillMaxSize()
                        .predictiveDismissTransform(state)
                        .background(Color.Red)
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun backEvent(progress: Float) =
        BackEventCompat(touchX = 0f, touchY = 0f, progress = progress, swipeEdge = BackEventCompat.EDGE_LEFT)

    private fun render(): Bitmap = composeRule.runOnIdle {
        Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            hostView.draw(Canvas(bitmap))
        }
    }

    private fun Bitmap.colorAt(xFraction: Float, yFraction: Float, width: Int, height: Int): Int =
        getPixel((width * xFraction).toInt(), (height * yFraction).toInt())

    @Test
    fun `full gesture shrinks the surface, shifts it away from the edge and rounds its corners`() {
        setSurface()
        val width = with(composeRule.density) { 300.dp.roundToPx() }
        val height = with(composeRule.density) { 200.dp.roundToPx() }
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(1f)) }

        val bitmap = render()

        // 缩放到 90% 后右移 (1/20 宽度 - 8dp)：300dp 宽时左缘约在 22dp、右缘约在 292dp
        assertEquals(android.graphics.Color.BLUE, bitmap.colorAt(0.07f, 0.5f, width, height))
        assertEquals(android.graphics.Color.RED, bitmap.colorAt(0.5f, 0.5f, width, height))
        assertEquals(android.graphics.Color.RED, bitmap.colorAt(0.96f, 0.5f, width, height))
        assertEquals(android.graphics.Color.BLUE, bitmap.colorAt(0.985f, 0.5f, width, height))
        assertEquals(android.graphics.Color.BLUE, bitmap.colorAt(0.5f, 0.03f, width, height))
        // 圆角裁掉缩小后左上角的像素
        assertEquals(android.graphics.Color.BLUE, bitmap.colorAt(0.105f, 0.055f, width, height))
    }

    @Test
    fun `cancelled gesture draws the surface edge to edge again`() {
        setSurface()
        val width = with(composeRule.density) { 300.dp.roundToPx() }
        val height = with(composeRule.density) { 200.dp.roundToPx() }
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.8f)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        composeRule.waitForIdle()

        val bitmap = render()

        assertEquals(android.graphics.Color.RED, bitmap.colorAt(0.01f, 0.01f, width, height))
        assertEquals(android.graphics.Color.RED, bitmap.colorAt(0.5f, 0.5f, width, height))
    }
}
