package moe.ouom.neriplayer.ui.theme.reveal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.component.common.ThemeRevealOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ThemeRevealSnapshotBudgetRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() = assumeComposeHostAvailable()

    @Test
    fun budgetedFallbackSnapshotFillsTheViewportDuringForwardReveal() {
        assertBudgetedSnapshotReveal(reverse = false)
    }

    @Test
    fun budgetedFallbackSnapshotFillsTheViewportDuringReverseReveal() {
        assertBudgetedSnapshotReveal(reverse = true)
    }

    private fun assertBudgetedSnapshotReveal(reverse: Boolean) {
        val snapshot = captureDetachedFourKView()
        val bitmap = snapshot.asAndroidBitmap()
        val fixtureVisible = mutableStateOf(true)
        val revealVisible = mutableStateOf(false)
        var viewportOrigin = Offset.Zero
        var viewportSize = IntSize.Zero
        var finishCallbacks = 0
        var contentInstalled = false
        val autoAdvance = composeRule.mainClock.autoAdvance
        val durationMillis = if (reverse) THEME_REVEAL_REVERSE_DURATION_MILLIS
            else THEME_REVEAL_DURATION_MILLIS
        try {
            val runtimeBudget = minOf(18L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16)
            assertTrue("the actual snapshot allocation must fit the runtime budget: " +
                "${bitmap.allocationByteCount} > $runtimeBudget", bitmap.allocationByteCount.toLong() <= runtimeBudget)
            assertTrue("the 4K fallback must actually be downsampled",
                bitmap.width < SourceWidth && bitmap.height < SourceHeight)
            assertTrue("downsampling must preserve the source aspect ratio",
                abs(bitmap.width.toDouble() / bitmap.height - 16.0 / 9) < 0.002)
            assertSnapshotFillsBounds(bitmap)

            composeRule.mainClock.autoAdvance = false
            contentInstalled = true
            composeRule.setContent {
                if (fixtureVisible.value) {
                    Box(
                        Modifier.requiredSize(width = 240.dp, height = 135.dp)
                            .background(NewThemeColor)
                            .testTag(ViewportTag)
                            .onGloballyPositioned {
                                viewportOrigin = it.positionInWindow()
                                viewportSize = it.size
                            }
                    ) {
                        if (revealVisible.value) {
                            ThemeRevealOverlay(
                                snapshot = snapshot,
                                fallbackColor = Color.Magenta,
                                originInWindow = viewportOrigin +
                                    Offset(viewportSize.width / 2f, viewportSize.height / 2f),
                                reverse = reverse,
                                durationMillis = durationMillis,
                                onFinished = {
                                    finishCallbacks += 1
                                    revealVisible.value = false
                                }
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertTrue("the real viewport must be laid out before the reveal starts",
                    viewportSize.width > 0 && viewportSize.height > 0)
                revealVisible.value = true
            }
            composeRule.mainClock.advanceTimeByFrame()
            withViewportBitmap(::assertSnapshotFillsBounds)

            composeRule.mainClock.advanceTimeBy(durationMillis / 2L)
            withViewportBitmap { frame ->
                if (reverse) {
                    assertPixel("reverse reveal must uncover the viewport edge", NewThemeColor, frame, 0.02f, 0.02f)
                    assertPixel("reverse reveal must still retain the shrinking snapshot", BottomColor, frame, 0.5f, 0.6f)
                } else {
                    assertPixel("forward reveal must uncover content beyond its initial radius", NewThemeColor, frame, 0.5f, 0.25f)
                    assertPixel("forward reveal must still retain the outer snapshot", TopColor, frame, 0.02f, 0.02f)
                }
            }
            composeRule.runOnIdle { assertEquals("the reveal must still be running halfway through", 0, finishCallbacks) }

            repeat(80) { composeRule.mainClock.advanceTimeByFrame() }
            composeRule.runOnIdle { assertEquals("the reveal must finish exactly once before cleanup", 1, finishCallbacks) }
            withViewportBitmap { frame ->
                for (x in listOf(0.1f, 0.5f, 0.9f)) {
                    for (y in listOf(0.1f, 0.5f, 0.9f)) {
                        assertPixel("the completed reveal must show the new theme", NewThemeColor, frame, x, y)
                    }
                }
            }
        } finally {
            try {
                if (contentInstalled) {
                    composeRule.runOnIdle { fixtureVisible.value = false }
                    composeRule.waitForIdle()
                }
                bitmap.recycle()
            } finally {
                composeRule.mainClock.autoAdvance = autoAdvance
            }
        }
    }

    private fun captureDetachedFourKView(): ImageBitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var source: View
        instrumentation.runOnMainSync {
            source = object : View(instrumentation.targetContext) {
                private val paint = Paint()

                override fun onDraw(canvas: Canvas) {
                    canvas.drawColor(TopColor.toArgb())
                    paint.color = BottomColor.toArgb()
                    canvas.drawRect(0f, height / 2f, width.toFloat(), height.toFloat(), paint)
                    paint.color = RightMarkerColor.toArgb()
                    canvas.drawRect(width * 0.75f, 0f, width.toFloat(), height.toFloat(), paint)
                }
            }
            source.measure(
                View.MeasureSpec.makeMeasureSpec(SourceWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(SourceHeight, View.MeasureSpec.EXACTLY)
            )
            source.layout(0, 0, SourceWidth, SourceHeight)
            assertEquals(SourceWidth, source.width)
            assertEquals(SourceHeight, source.height)
            assertFalse("the fallback source must not belong to a host window", source.isAttachedToWindow)
        }
        return runBlocking { checkNotNull(captureThemeRevealSnapshot(activity = null, fallbackView = source)) }
    }

    private fun assertSnapshotFillsBounds(bitmap: Bitmap) {
        assertPixel("the upper source half must fill the viewport", TopColor, bitmap, 0.1f, 0.25f)
        assertPixel("the lower source half must fill the viewport", BottomColor, bitmap, 0.1f, 0.75f)
        assertPixel("the right marker must reach the upper viewport", RightMarkerColor, bitmap, 0.9f, 0.25f)
        assertPixel("the right marker must reach the lower viewport", RightMarkerColor, bitmap, 0.9f, 0.75f)
    }

    private fun assertPixel(message: String, expected: Color, bitmap: Bitmap, xFraction: Float, yFraction: Float) {
        val x = (bitmap.width * xFraction).toInt().coerceIn(0, bitmap.width - 1)
        val y = (bitmap.height * yFraction).toInt().coerceIn(0, bitmap.height - 1)
        val actual = Color(bitmap.getPixel(x, y))
        val difference = maxOf(abs(expected.red - actual.red), abs(expected.green - actual.green),
            abs(expected.blue - actual.blue))
        assertTrue("$message at ($x,$y): expected=$expected actual=$actual", difference < 0.08f)
    }

    private fun withViewportBitmap(block: (Bitmap) -> Unit) {
        val bitmap = composeRule.onNodeWithTag(ViewportTag).captureToImage().asAndroidBitmap()
        try {
            block(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val SourceWidth = 3840
        const val SourceHeight = 2160
        const val ViewportTag = "theme-reveal-snapshot-budget-viewport"
        val TopColor = Color.Red
        val BottomColor = Color.Blue
        val RightMarkerColor = Color.Yellow
        val NewThemeColor = Color.Green
    }
}
