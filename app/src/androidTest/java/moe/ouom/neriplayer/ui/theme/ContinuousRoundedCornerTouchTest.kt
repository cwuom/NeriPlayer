package moe.ouom.neriplayer.ui.theme

import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.performNativeClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContinuousRoundedCornerTouchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    @Before
    fun requireHardwareRenderedEmulator() {
        assumeComposeHostAvailable()
        assumeTrue("仅在 API 30+ 模拟器验证真实 Generic outline 命中", Build.VERSION.SDK_INT >= 30 &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        composeRule.activityRule.scenario.onActivity {
            assertTrue("需要硬件渲染窗口", it.window.decorView.isHardwareAccelerated)
        }
    }

    @Test
    fun vertical6000pxClipReceivesNativeButtonTouch() {
        assertFarButtonTouch(lengthPx = 6_000, horizontal = false)
    }

    @Test
    fun vertical9000pxClipReceivesNativeButtonTouchBeyond8192px() {
        assertFarButtonTouch(lengthPx = 9_000, horizontal = false)
    }

    @Test
    fun horizontal9000pxClipReceivesNativeButtonTouchBeyond8192px() {
        assertFarButtonTouch(lengthPx = 9_000, horizontal = true)
    }

    private fun assertFarButtonTouch(lengthPx: Int, horizontal: Boolean) {
        val calls = AtomicInteger()
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val length = with(density) { lengthPx.toDp() }
                    val inset = with(density) { 200.toDp() }
                    val shortSide = minOf(with(density) { 1_000.toDp() }, if (horizontal) maxHeight else maxWidth)
                    val content: @Composable () -> Unit = {
                        Box(
                            Modifier
                                .width(if (horizontal) length else shortSide)
                                .height(if (horizontal) shortSide else length)
                                .clip(ContinuousRoundedCornerShape(22.dp))
                                .background(Color(0xFFE1ECF6))
                                .testTag(ClipTag)
                        ) {
                            Button(
                                onClick = { calls.incrementAndGet() },
                                modifier = Modifier
                                    .align(if (horizontal) Alignment.CenterEnd else Alignment.BottomCenter)
                                    .absoluteOffset(x = if (horizontal) -inset else 0.dp,
                                        y = if (horizontal) 0.dp else -inset)
                                    .testTag(ButtonTag)
                            ) {
                                Text("Native touch")
                            }
                        }
                    }
                    if (horizontal) {
                        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) { content() }
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
                    }
                }
            }
        }
        val target = composeRule.onNodeWithTag(ButtonTag)
        target.performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeRule.waitForIdle()
        val clipCoordinates = composeRule.onNodeWithTag(ClipTag).fetchSemanticsNode().layoutInfo.coordinates
        val buttonNode = target.fetchSemanticsNode()
        val nativePoint = buttonNode.positionOnScreen + Offset(buttonNode.size.width / 2f, buttonNode.size.height / 2f)
        composeRule.runOnIdle {
            val local = clipCoordinates.screenToLocal(nativePoint)
            val measuredLength = if (horizontal) clipCoordinates.size.width else clipCoordinates.size.height
            val alongAxis = if (horizontal) local.x else local.y
            Log.i("ContinuousCornerTouch", "horizontal=$horizontal, clip=${clipCoordinates.size}, " +
                "button=${buttonNode.size}, nativePoint=$nativePoint, nativePointInClip=$local")
            assertEquals("裁剪层必须保留指定的真实物理长度", lengthPx, measuredLength)
            assertTrue("实际注入点必须位于裁剪层内", local.x >= 0f && local.y >= 0f &&
                local.x < clipCoordinates.size.width && local.y < clipCoordinates.size.height)
            assertTrue("实际注入点必须位于裁剪层远端", alongAxis > lengthPx - 1_000f)
            if (lengthPx > 8_192) {
                assertTrue("触摸点局部坐标必须超过 8192px", alongAxis > 8_192f)
            } else {
                assertTrue("对照触摸点位于 8192px 以内", alongAxis < 8_192f)
            }
            assertEquals(0, calls.get())
        }
        target.performNativeClick()
        composeRule.waitUntil(5_000L) { calls.get() == 1 }
        composeRule.runOnIdle { assertEquals("一次原生点按必须恰好触发一次回调", 1, calls.get()) }
    }

    private companion object {
        const val ClipTag = "continuous-rounded-clip"
        const val ButtonTag = "far-native-button"
    }
}
