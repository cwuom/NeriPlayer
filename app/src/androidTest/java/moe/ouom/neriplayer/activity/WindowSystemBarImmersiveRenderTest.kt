package moe.ouom.neriplayer.activity

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.captureCommittedFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class WindowSystemBarImmersiveRenderTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var hostView: View

    @Before
    fun requireVisibleGestureNavigation() {
        assumeComposeHostAvailable()
        assumeTrue("需要手势导航模式，三键导航不在此渲染夹具范围内",
            Settings.Secure.getInt(composeRule.activity.contentResolver, "navigation_mode", -1) == 2)
    }

    @Test
    @Suppress("DEPRECATION")
    fun gestureAreaKeepsTheWindowBackgroundThroughThemeRoundTrips() {
        val activity = composeRule.activity
        val dark = mutableStateOf(false)
        composeRule.runOnUiThread {
            activity.enableNeriEdgeToEdge()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                show(WindowInsetsCompat.Type.navigationBars())
            }
        }
        composeRule.setContent {
            hostView = LocalView.current
            val isDark = dark.value
            SideEffect {
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    isAppearanceLightStatusBars = !isDark
                    isAppearanceLightNavigationBars = !isDark
                }
                activity.window.applyMainWindowCaptionAppearance(isDark)
                activity.window.applyNeriSystemBarTransparency()
            }
            Canvas(Modifier.fillMaxSize()) {
                drawRect(if (isDark) Color(DarkLeft) else Color(LightLeft))
                drawRect(
                    color = if (isDark) Color(DarkRight) else Color(LightRight),
                    topLeft = Offset(size.width / 2f, 0f),
                    size = Size(size.width / 2f, size.height)
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000L) {
            composeRule.runOnUiThread {
                ViewCompat.getRootWindowInsets(hostView)
                    ?.isVisible(WindowInsetsCompat.Type.navigationBars()) == true
            }
        }
        val navigationInsets = composeRule.runOnUiThread {
            checkNotNull(ViewCompat.getRootWindowInsets(hostView))
                .getInsets(WindowInsetsCompat.Type.navigationBars())
        }
        val tappableInsets = composeRule.runOnUiThread {
            checkNotNull(ViewCompat.getRootWindowInsets(hostView))
                .getInsets(WindowInsetsCompat.Type.tappableElement())
        }
        assumeTrue("需要底部可见的小横条区域，侧边导航或平板任务栏不在此夹具范围内",
            navigationInsets.bottom > 0 && tappableInsets.bottom == 0)
        val windowFlags = composeRule.runOnUiThread { activity.window.attributes.flags }
        assertImmersiveFrame(isDark = false, navigationInsets, windowFlags)

        for (nextDark in listOf(true, false)) {
            composeRule.runOnIdle {
                activity.window.statusBarColor = AndroidColor.RED
                activity.window.navigationBarColor = AndroidColor.MAGENTA
                activity.window.navigationBarDividerColor = AndroidColor.CYAN
                activity.window.isStatusBarContrastEnforced = true
                activity.window.isNavigationBarContrastEnforced = true
                assertTrue(activity.window.isNavigationBarContrastEnforced)
                dark.value = nextDark
            }
            composeRule.waitForIdle()
            assertImmersiveFrame(nextDark, navigationInsets, windowFlags)
        }
    }

    @Test
    fun gesturePillRemainsVisibleOnLightDarkLightWindowBackgrounds() {
        val activity = composeRule.activity
        val dark = mutableStateOf(false)
        composeRule.runOnUiThread {
            activity.enableNeriEdgeToEdge()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                show(WindowInsetsCompat.Type.navigationBars())
            }
        }
        composeRule.setContent {
            hostView = LocalView.current
            val isDark = dark.value
            SideEffect {
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    isAppearanceLightStatusBars = !isDark
                    isAppearanceLightNavigationBars = !isDark
                }
                activity.window.applyMainWindowCaptionAppearance(isDark)
                activity.window.applyNeriSystemBarTransparency()
            }
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(if (isDark) PillDarkBackground else PillLightBackground))
            }
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(PillAppearanceTimeoutMs) {
            composeRule.runOnUiThread {
                ViewCompat.getRootWindowInsets(hostView)
                    ?.isVisible(WindowInsetsCompat.Type.navigationBars()) == true
            }
        }
        val navigationInsets = composeRule.runOnUiThread {
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(hostView))
            val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            assumeTrue("需要底部手势小横条，排除侧边导航和系统任务栏",
                navigation.bottom > 0 &&
                    insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom == 0)
            navigation
        }
        val output = File(
            checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
            "system-bar-pill-pixels-${System.currentTimeMillis()}"
        )
        check(output.mkdirs()) { "无法创建 pill 像素诊断目录: $output" }
        for ((index, nextDark) in listOf(false, true, false).withIndex()) {
            composeRule.runOnIdle { dark.value = nextDark }
            composeRule.waitForIdle()
            assertVisibleGesturePill(nextDark, navigationInsets, File(output, "stage-$index"))
        }
    }

    @Test
    fun oversizedOvalCannotMasqueradeAsTheGesturePill() {
        val navigationBounds = AndroidRect(0, 0, 1080, 72)
        val bitmap = Bitmap.createBitmap(navigationBounds.width(), navigationBounds.height(), Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(PillLightBackground)
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val dx = (x - 540.0) / 450.0
                    val dy = (y - 16.0) / 14.0
                    if (dx * dx + dy * dy <= 1.0) bitmap.setPixel(x, y, PillDarkBackground)
                }
            }
            // 大椭圆避开既有背景采样点，检测器也必须排除它的细窄边缘
            for (y in listOf(navigationBounds.top, navigationBounds.centerY(), navigationBounds.bottom - 2)) {
                assertPixel(bitmap, bitmap.width / 4, y, PillLightBackground)
                assertPixel(bitmap, bitmap.width * 3 / 4, y, PillLightBackground)
            }
            assertNull("大椭圆的边缘不能当作中心手势条",
                findGesturePill(bitmap, navigationBounds, 3f, isDark = false,
                    expectedBackground = PillLightBackground))
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertVisibleGesturePill(isDark: Boolean, navigationInsets: Insets, output: File) {
        val expectedBackground = if (isDark) PillDarkBackground else PillLightBackground
        val bounds = composeRule.runOnUiThread {
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(hostView))
            assertTrue(insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
            assertEquals(navigationInsets, insets.getInsets(WindowInsetsCompat.Type.navigationBars()))
            assertEquals(0, insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom)
            val location = IntArray(2)
            hostView.getLocationInWindow(location)
            val decor = composeRule.activity.window.decorView
            Rect(-location[0].toFloat(), -location[1].toFloat(),
                decor.width - location[0].toFloat(), decor.height - location[1].toFloat())
        }
        val committed = composeRule.captureCommittedFrame(hostView, bounds)
        val frameWidth = committed.width
        val frameHeight = committed.height
        try {
            for (y in listOf(frameHeight - navigationInsets.bottom / 2 - 1, frameHeight - 2)) {
                assertPixel(committed, frameWidth / 4, y, expectedBackground)
                assertPixel(committed, frameWidth * 3 / 4, y, expectedBackground)
            }
        } finally {
            committed.recycle()
        }
        val origin = composeRule.runOnUiThread {
            IntArray(2).also { composeRule.activity.window.decorView.getLocationOnScreen(it) }
        }
        val navigationBounds = AndroidRect(origin[0], origin[1] + frameHeight - navigationInsets.bottom,
            origin[0] + frameWidth, origin[1] + frameHeight)
        val density = composeRule.activity.resources.displayMetrics.density
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var screenshot: Bitmap? = null
        var attempts = 0
        var lastCaptureAt = 0L
        var observation = "尚无合成截图"
        val metrics = File("${output.path}-metrics.txt")
        metrics.writeText("nav=$navigationBounds density=$density background=$expectedBackground " +
            "minimumContrast=$PillMinimumContrast timeoutMs=$PillAppearanceTimeoutMs\n")
        try {
            composeRule.waitUntil(PillAppearanceTimeoutMs) {
                val now = SystemClock.uptimeMillis()
                if (now - lastCaptureAt < PillPollIntervalMs) return@waitUntil false
                lastCaptureAt = now
                screenshot?.recycle()
                screenshot = null
                val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                screenshot = screen
                attempts++
                val failure = try {
                    assertTrue("导航区域必须完整处于合成截图内: $navigationBounds ${screen.width}x${screen.height}",
                        navigationBounds.left >= 0 && navigationBounds.top >= 0 &&
                            navigationBounds.right <= screen.width && navigationBounds.bottom <= screen.height)
                    val pill = findGesturePill(screen, navigationBounds, density, isDark, expectedBackground)
                    assertTrue("中心手势区域没有细长且高对比的小横条", pill != null)
                    val handle = checkNotNull(pill)
                    val padding = maxOf(1, (4f * density).roundToInt())
                    for (y in handle.top until handle.bottom) {
                        assertPixel(screen, handle.left - padding, y, expectedBackground)
                        assertPixel(screen, handle.right + padding, y, expectedBackground)
                    }
                    for (y in listOf(navigationBounds.top, navigationBounds.centerY(), navigationBounds.bottom - 2)) {
                        assertPixel(screen, navigationBounds.left + frameWidth / 4, y, expectedBackground)
                        assertPixel(screen, navigationBounds.left + frameWidth * 3 / 4, y, expectedBackground)
                    }
                    observation = "pass: handle=$handle nav=$navigationBounds density=$density"
                    null
                } catch (failure: AssertionError) {
                    failure.message ?: failure.toString()
                }
                if (failure != null) observation = failure
                metrics.appendText("attempt=$attempts uptimeMs=$now dark=$isDark $observation\n")
                if (attempts == 1) savePillScreenshot(screen, File("${output.path}-first.png"))
                failure == null
            }
        } finally {
            screenshot?.let { screen ->
                try {
                    savePillScreenshot(screen, File("${output.path}-last.png"))
                } finally {
                    screen.recycle()
                }
            }
            Log.i("NeriPillPixels", "${output.path}: attempts=$attempts $observation")
        }
    }

    private fun findGesturePill(
        screen: Bitmap,
        navigationBounds: AndroidRect,
        density: Float,
        isDark: Boolean,
        expectedBackground: Int
    ): AndroidRect? {
        val minimumWidth = maxOf((24f * density).roundToInt(), navigationBounds.width() / 16)
        val maximumWidth = navigationBounds.width() * 3 / 5
        val maximumHeight = maxOf(1, minOf((8f * density).roundToInt(), navigationBounds.height() / 2))
        val centerTolerance = maxOf((8f * density).roundToInt(), navigationBounds.width() / 30)
        val backgroundLuma = pixelLuma(expectedBackground)
        val width = navigationBounds.width()
        val height = navigationBounds.height()
        val foreground = BooleanArray(width * height) { index ->
            val x = navigationBounds.left + index % width
            val y = navigationBounds.top + index / width
            val delta = pixelLuma(screen.getPixel(x, y)) - backgroundLuma
            (if (isDark) delta else -delta) >= PillMinimumContrast
        }
        val queue = IntArray(foreground.size)
        var queued = 0
        fun enqueue(index: Int) {
            if (foreground[index]) {
                foreground[index] = false
                queue[queued++] = index
            }
        }
        val padding = maxOf(1, (4f * density).roundToInt())
        var best: AndroidRect? = null
        // 先统计完整连通块，避免大色块被尺寸过滤切成小横条
        for (index in foreground.indices) {
            if (!foreground[index]) continue
            queued = 0
            enqueue(index)
            var visited = 0
            val handle = AndroidRect(
                navigationBounds.left + index % width, navigationBounds.top + index / width,
                navigationBounds.left + index % width + 1, navigationBounds.top + index / width + 1
            )
            while (visited < queued) {
                val current = queue[visited++]
                val x = current % width
                val y = current / width
                handle.union(navigationBounds.left + x, navigationBounds.top + y,
                    navigationBounds.left + x + 1, navigationBounds.top + y + 1)
                if (x > 0) enqueue(current - 1)
                if (x + 1 < width) enqueue(current + 1)
                if (y > 0) enqueue(current - width)
                if (y + 1 < height) enqueue(current + width)
            }
            if (handle.width() in minimumWidth..maximumWidth &&
                abs(handle.centerX() - navigationBounds.centerX()) <= centerTolerance &&
                handle.height() in 1..maximumHeight && handle.width() >= handle.height() * 8 &&
                handle.left - padding >= navigationBounds.left &&
                handle.right + padding < navigationBounds.right && handle.width() > (best?.width() ?: 0)
            ) best = handle
        }
        return best
    }

    private fun pixelLuma(color: Int): Int =
        (AndroidColor.red(color) * 299 + AndroidColor.green(color) * 587 + AndroidColor.blue(color) * 114) / 1_000

    private fun savePillScreenshot(bitmap: Bitmap, file: File) {
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "保存合成截图失败: $file" }
        }
    }

    @Suppress("DEPRECATION")
    private fun assertImmersiveFrame(isDark: Boolean, navigationInsets: Insets, windowFlags: Int) {
        val bounds = composeRule.runOnUiThread {
            val window = composeRule.activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertEquals(!isDark, controller.isAppearanceLightStatusBars)
            assertEquals(!isDark, controller.isAppearanceLightNavigationBars)
            assertEquals(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT, controller.systemBarsBehavior)
            assertEquals(windowFlags, window.attributes.flags)
            assertEquals(AndroidColor.TRANSPARENT, window.statusBarColor)
            assertEquals(AndroidColor.TRANSPARENT, window.navigationBarColor)
            assertEquals(AndroidColor.TRANSPARENT, window.navigationBarDividerColor)
            assertFalse(window.isStatusBarContrastEnforced)
            assertFalse(window.isNavigationBarContrastEnforced)
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(hostView))
            assertTrue(insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
            assertEquals(navigationInsets, insets.getInsets(WindowInsetsCompat.Type.navigationBars()))
            val location = IntArray(2)
            hostView.getLocationInWindow(location)
            Rect(-location[0].toFloat(), -location[1].toFloat(),
                window.decorView.width - location[0].toFloat(), window.decorView.height - location[1].toFloat())
        }
        val bitmap = composeRule.captureCommittedFrame(hostView, bounds)
        val frameWidth = bitmap.width
        val frameHeight = bitmap.height
        try {
            for (y in listOf(bitmap.height - navigationInsets.bottom / 2 - 1, bitmap.height - 2)) {
                assertPixel(bitmap, bitmap.width / 4, y, if (isDark) DarkLeft else LightLeft)
                assertPixel(bitmap, bitmap.width * 3 / 4, y, if (isDark) DarkRight else LightRight)
            }
        } finally {
            bitmap.recycle()
        }
        val screenOrigin = composeRule.runOnUiThread {
            IntArray(2).also { composeRule.activity.window.decorView.getLocationOnScreen(it) }
        }
        val screen = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            for (y in listOf(frameHeight - navigationInsets.bottom / 2 - 1, frameHeight - 2)) {
                assertPixel(screen, screenOrigin[0] + frameWidth / 4, screenOrigin[1] + y,
                    if (isDark) DarkLeft else LightLeft)
                assertPixel(screen, screenOrigin[0] + frameWidth * 3 / 4, screenOrigin[1] + y,
                    if (isDark) DarkRight else LightRight)
            }
        } finally {
            screen.recycle()
        }
    }

    private fun assertPixel(bitmap: Bitmap, x: Int, y: Int, expected: Int) {
        val actual = bitmap.getPixel(x, y)
        val difference = maxOf(abs(AndroidColor.red(actual) - AndroidColor.red(expected)),
            abs(AndroidColor.green(actual) - AndroidColor.green(expected)),
            abs(AndroidColor.blue(actual) - AndroidColor.blue(expected)))
        assertTrue("导航区域背景未延伸到 ($x,$y): expected=$expected actual=$actual", difference <= 4)
    }

    private companion object {
        const val PillLightBackground = 0xFFF4F4F4.toInt()
        const val PillDarkBackground = 0xFF141414.toInt()
        const val PillMinimumContrast = 64
        const val PillAppearanceTimeoutMs = 5_000L
        const val PillPollIntervalMs = 100L
        const val LightLeft = 0xFF56C0E8.toInt()
        const val LightRight = 0xFFE8B456.toInt()
        const val DarkLeft = 0xFF204080.toInt()
        const val DarkRight = 0xFF805020.toInt()
    }
}
