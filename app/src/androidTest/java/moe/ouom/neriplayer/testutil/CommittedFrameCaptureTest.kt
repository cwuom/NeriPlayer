package moe.ouom.neriplayer.testutil

import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.graphics.HardwareRendererCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class CommittedFrameCaptureTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var hostView: View

    @Before
    fun assumeDeviceUnlocked() = assumeComposeHostAvailable()

    @Test
    fun capturesOffsetNodeWithoutAdvancingFrozenClock() {
        showFixture()
        val bounds = composeRule.onNodeWithTag(SampleTag).fetchSemanticsNode().boundsInRoot
        composeRule.mainClock.autoAdvance = false
        val frozenTime = composeRule.mainClock.currentTime
        val drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
        try {
            val bitmap = composeRule.captureCommittedFrame(hostView, bounds)
            try {
                assertEquals(bounds.width.roundToInt(), bitmap.width)
                assertEquals(bounds.height.roundToInt(), bitmap.height)
                assertEquals(AndroidColor.RED, bitmap.getPixel(bitmap.width / 4, bitmap.height / 2))
                assertEquals(AndroidColor.BLUE, bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2))
                assertEquals("capture advanced the frozen Compose clock", frozenTime, composeRule.mainClock.currentTime)
                assertEquals("capture changed the renderer state", drawingWasEnabled, HardwareRendererCompat.isDrawingEnabled())
            } finally {
                bitmap.recycle()
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun waitsForFrameThatTakesLongerThanComposeCaptureDeadline() {
        showFixture()
        val bounds = composeRule.onNodeWithTag(SampleTag).fetchSemanticsNode().boundsInRoot
        val delayedDraw = AtomicBoolean(false)
        val listener = ViewTreeObserver.OnDrawListener {
            // 让真实绘制超过 Compose 截图的两秒期限，验证等待的是提交后的帧
            if (delayedDraw.compareAndSet(false, true)) SystemClock.sleep(DelayedDrawMs)
        }
        val observer = composeRule.runOnUiThread {
            hostView.viewTreeObserver.also { it.addOnDrawListener(listener) }
        }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val bitmap = composeRule.captureCommittedFrame(hostView, bounds)
            try {
                assertTrue("capture skipped the delayed draw", delayedDraw.get())
                assertTrue("capture returned before the delayed frame", SystemClock.elapsedRealtime() - startedAt >= DelayedDrawMs)
                assertEquals(AndroidColor.RED, bitmap.getPixel(bitmap.width / 4, bitmap.height / 2))
                assertEquals(AndroidColor.BLUE, bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2))
            } finally {
                bitmap.recycle()
            }
        } finally {
            composeRule.runOnUiThread {
                if (observer.isAlive) observer.removeOnDrawListener(listener)
            }
        }
    }

    private fun showFixture() {
        composeRule.setContent {
            hostView = LocalView.current
            Box(Modifier.size(160.dp, 120.dp).background(Color.Magenta)) {
                Row(Modifier.offset(24.dp, 20.dp).size(80.dp, 40.dp).testTag(SampleTag)) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color.Red))
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color.Blue))
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val SampleTag = "committed_frame_sample"
        const val DelayedDrawMs = 2_250L
    }
}
