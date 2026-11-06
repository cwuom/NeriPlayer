package moe.ouom.neriplayer.ui.theme.reveal

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.os.Build
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.captureCommittedFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ThemeRevealWrappedContextCaptureTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var composeHost: View
    private lateinit var effectView: View
    private lateinit var configuredContext: Context
    private var providedActivity: Activity? = null
    private val fixtureVisible = mutableStateOf(true)

    @Before
    fun assumeDeviceUnlocked() = assumeComposeHostAvailable()

    @Test
    fun attachedWindowCaptureKeepsRenderEffectWithAConfigurationOnlyLocalContext() {
        assertWrappedContextCapture(background = null)
    }

    @Test
    fun attachedWindowCaptureKeepsAHardwareBitmapBackgroundWithAConfigurationOnlyLocalContext() {
        val hardwareBackground = createHardwareBackground()
        try {
            assertEquals(Bitmap.Config.HARDWARE, hardwareBackground.config)
            assertWrappedContextCapture(hardwareBackground)
        } finally {
            hardwareBackground.recycle()
        }
    }

    @Test
    fun failedWindowCaptureDoesNotDrawHardwareBitmapsThroughSoftwareCanvas() {
        val hardwareBackground = createHardwareBackground()
        try {
            showFixture(hardwareBackground)
            composeRule.runOnIdle {
                assertTrue("the regression requires the real hardware-accelerated window",
                    composeHost.rootView.isAttachedToWindow && composeHost.rootView.isHardwareAccelerated)
            }
            var windowCaptureCalls = 0
            val snapshot = runBlocking {
                withTimeout(5_000L) {
                    withContext(Dispatchers.Main.immediate) {
                        captureThemeRevealSnapshot(null, composeHost.rootView, windowCapture = { activity ->
                            assertSame(composeRule.activity, activity)
                            windowCaptureCalls += 1
                            null
                        })
                    }
                }
            }
            try {
                assertEquals("the window capture failure must execute", 1, windowCaptureCalls)
                assertNull("a failed hardware window capture must use the reveal color fallback", snapshot)
            } finally {
                snapshot?.asAndroidBitmap()?.recycle()
            }
            composeRule.waitForIdle()
        } finally {
            try {
                composeRule.runOnIdle { fixtureVisible.value = false }
                composeRule.waitForIdle()
            } finally {
                hardwareBackground.recycle()
            }
        }
    }

    private fun createHardwareBackground(): Bitmap {
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        return try {
            source.eraseColor(AndroidColor.BLUE)
            checkNotNull(source.copy(Bitmap.Config.HARDWARE, false))
        } finally {
            source.recycle()
        }
    }

    private fun assertWrappedContextCapture(background: Bitmap?) {
        showFixture(background)
        try {
            composeRule.runOnIdle {
                assertSame(composeRule.activity, providedActivity)
                assertNull("the localized Context must not expose an Activity", findActivity(configuredContext))
                assertTrue("the fallback must be attached to the real host", composeHost.rootView.isAttachedToWindow)
                assertSame("the attached window must retain its Activity owner", providedActivity,
                    composeHost.rootView.findViewTreeLifecycleOwner())
            }
            val bounds = composeRule.onNodeWithTag(EffectViewTag).fetchSemanticsNode().boundsInRoot
            val committed = composeRule.captureCommittedFrame(composeHost, bounds)
            try {
                assertEquals("fixture background did not render", AndroidColor.BLUE,
                    committed.getPixel(committed.width / 4, committed.height / 2))
                assertEquals("fixture RenderEffect did not render", AndroidColor.GREEN,
                    committed.getPixel(committed.width * 3 / 4, committed.height / 2))
            } finally {
                committed.recycle()
            }

            val snapshot = runBlocking {
                withTimeout(5_000L) {
                    withContext(Dispatchers.Main.immediate) {
                        captureThemeRevealSnapshot(
                            activity = configuredContext as? Activity,
                            fallbackView = composeHost.rootView
                        )
                    }
                }
            }
            assertNotNull("attached window capture lost the hardware background", snapshot)
            val bitmap = checkNotNull(snapshot).asAndroidBitmap()
            try {
                assertSnapshotPixel(bitmap, 0.25f, AndroidColor.BLUE, "hardware background")
                assertSnapshotPixel(bitmap, 0.75f, AndroidColor.GREEN, "RenderEffect")
            } finally {
                bitmap.recycle()
            }
        } finally {
            composeRule.runOnIdle { fixtureVisible.value = false }
            composeRule.waitForIdle()
        }
    }

    private fun showFixture(background: Bitmap?) {
        val host = composeRule.activity
        val localizedContext = host.createConfigurationContext(
            Configuration().apply { densityDpi = host.resources.configuration.densityDpi }
        )
        composeRule.setContent {
            composeHost = LocalView.current
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalActivity provides host
            ) {
                val context = LocalContext.current
                val activity = LocalActivity.current
                SideEffect {
                    configuredContext = context
                    providedActivity = activity
                }
                Box(Modifier.size(128.dp, 96.dp)) {
                    if (fixtureVisible.value) {
                        AndroidView(
                            factory = { viewContext ->
                                object : View(viewContext) {
                                    private val paint = Paint()

                                    override fun onDraw(canvas: Canvas) {
                                        super.onDraw(canvas)
                                        canvas.drawColor(AndroidColor.RED)
                                        val imageBounds = RectF(0f, 0f, width / 2f, height.toFloat())
                                        if (background == null) {
                                            paint.color = AndroidColor.BLUE
                                            canvas.drawRect(imageBounds, paint)
                                        } else {
                                            canvas.drawBitmap(background, null, imageBounds, paint)
                                        }
                                    }
                                }.apply {
                                    setWillNotDraw(false)
                                    setRenderEffect(RenderEffect.createColorFilterEffect(
                                        ColorMatrixColorFilter(ColorMatrix(SwapRedAndGreen))
                                    ))
                                    effectView = this
                                }
                            },
                            modifier = Modifier.fillMaxSize().testTag(EffectViewTag)
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertSnapshotPixel(bitmap: Bitmap, xFraction: Float, expected: Int, label: String) {
        val sample = composeRule.runOnIdle {
            val position = IntArray(2)
            effectView.getLocationInWindow(position)
            val decor = composeHost.rootView
            val x = ((position[0] + effectView.width * xFraction) * bitmap.width / decor.width).roundToInt()
            val y = ((position[1] + effectView.height / 2f) * bitmap.height / decor.height).roundToInt()
            x to y
        }
        assertEquals("window snapshot lost $label", expected, bitmap.getPixel(sample.first, sample.second))
    }

    private tailrec fun findActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }

    private companion object {
        const val EffectViewTag = "theme-reveal-wrapped-context-effect"
        val SwapRedAndGreen = floatArrayOf(
            0f, 1f, 0f, 0f, 0f,
            1f, 0f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    }
}
