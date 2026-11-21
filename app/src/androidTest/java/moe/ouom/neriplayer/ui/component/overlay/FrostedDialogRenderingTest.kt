package moe.ouom.neriplayer.ui.component.overlay

import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.roundToInt
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class FrostedDialogRenderingTest {
    @get:Rule
    val compose = createAndroidComposeRule<TestComposeActivity>()

    @Test
    @OptIn(ExperimentalMaterial3Api::class)
    fun dialogBlursBothActivityAndSheetAcrossTheSheetEdge() {
        assumeComposeHostAvailable()
        val showDialog = mutableStateOf(false)
        compose.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "6750A4",
                uiStyle = UiStyle.Clean,
                enhancedEffects = true,
                animateColors = false
            ) {
                Stripes(Color.Red, Modifier.fillMaxSize())
                DensityScaledModalBottomSheet(onDismissRequest = {}, dragHandle = null) {
                    Stripes(Color.Blue, Modifier.fillMaxWidth().fillMaxHeight(0.5f).testTag("sheet"))
                    if (showDialog.value) {
                        DensityScaledAlertDialog(
                            onDismissRequest = {},
                            confirmButton = {},
                            text = { Spacer(Modifier.fillMaxWidth().height(224.dp).testTag("dialogBody")) }
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val sheet = compose.onNodeWithTag("sheet").fetchSemanticsNode()
        val centerX = sheet.positionOnScreen.x.roundToInt() + sheet.size.width / 2
        val edgeY = sheet.positionOnScreen.y.roundToInt()
        val inset = with(compose.density) { 48.dp.roundToPx() }
        val columns = centerX - inset until centerX + inset
        val verticalInset = minOf(inset, sheet.size.height / 4)
        val upperY = edgeY - verticalInset
        val lowerY = edgeY + verticalInset
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

        fun awaitRows(description: String, ready: (StripeSample, StripeSample) -> Boolean) {
            var lastSample: Pair<StripeSample, StripeSample>? = null
            try {
                compose.waitUntil(5_000) {
                    val screen = checkNotNull(automation.takeScreenshot())
                    val (upper, lower) = try {
                        screen.stripeSample(columns, upperY) to screen.stripeSample(columns, lowerY)
                    } finally {
                        screen.recycle()
                    }
                    lastSample = upper to lower
                    val matches = ready(upper, lower)
                    if (!matches) SystemClock.sleep(100)
                    matches
                }
            } catch (timeout: ComposeTimeoutException) {
                throw AssertionError("$description, last (activity, sheet) = $lastSample", timeout)
            }
        }

        // 确认两层条纹都已经显示，再让弹窗采样
        awaitRows("expected sharp red and blue stripes before opening the dialog") { upper, lower ->
            upper.mean > 30 && lower.mean < -30 && upper.maxEdge > 30 && lower.maxEdge > 30
        }
        compose.runOnIdle { showDialog.value = true }
        val body = compose.onNodeWithTag("dialogBody").fetchSemanticsNode()
        assertTrue(
            "sample rows must fall inside the dialog",
            upperY >= body.positionOnScreen.y && lowerY < body.positionOnScreen.y + body.size.height
        )
        // 磨砂保留缓慢的色彩变化，但条纹边缘应消失
        awaitRows("expected blurred red and blue on both sides of the sheet edge") { upper, lower ->
            upper.mean > 8 && lower.mean < -8 && upper.maxEdge < 4 && lower.maxEdge < 4
        }
    }

    @Composable
    private fun Stripes(color: Color, modifier: Modifier) {
        Canvas(modifier) {
            drawRect(Color.Black)
            val stripe = 4.dp.roundToPx()
            for (x in 0 until size.width.roundToInt() step 2 * stripe) {
                drawRect(color, Offset(x.toFloat(), 0f), Size(stripe.toFloat(), size.height))
            }
        }
    }

    private data class StripeSample(val mean: Double, val maxEdge: Int)

    private fun Bitmap.stripeSample(columns: IntRange, y: Int): StripeSample {
        val redMinusBlue = columns.map { x ->
            val pixel = getPixel(x, y)
            PixelColor.red(pixel) - PixelColor.blue(pixel)
        }
        val maxEdge = redMinusBlue.zipWithNext { left, right -> abs(left - right) }.max()
        return StripeSample(redMinusBlue.average(), maxEdge)
    }
}
