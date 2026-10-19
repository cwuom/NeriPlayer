package moe.ouom.neriplayer.ui.screen.nowplaying

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class MoreOptionsSheetPredictiveBackTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    private val gestureInput = GestureInput()
    private var visible by mutableStateOf(true)
    private var dismissals = 0
    private var surfaceColor = 0
    private var captureLabel = ""

    @Before
    fun requireComposeHost() {
        assumeComposeHostAvailable()
    }

    @After
    fun closeOwnedSheet() {
        composeRule.mainClock.autoAdvance = true
        composeRule.runOnIdle { visible = false }
        composeRule.waitForIdle()
    }

    @Test
    fun materialLightKeepsTheSheetWholeThroughBackGestures() = verifyBackGestures(UiStyle.Material, false)

    @Test
    fun materialDarkKeepsTheSheetWholeThroughBackGestures() = verifyBackGestures(UiStyle.Material, true)

    @Test
    fun cleanLightKeepsTheSheetWholeThroughBackGestures() = verifyBackGestures(UiStyle.Clean, false)

    @Test
    fun cleanDarkKeepsTheSheetWholeThroughBackGestures() = verifyBackGestures(UiStyle.Clean, true)

    @Test
    fun systemBackClosesTheRealSheetExactlyOnce() = verifySingleDismissal("system-back") {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }

    @Test
    fun draggingDownFromTheUpperBodyClosesTheRealSheetExactlyOnce() = verifySingleDismissal("drag-down") {
        composeRule.onNodeWithTag(BodyTag).performTouchInput {
            swipeDown(startY = 1f, endY = height - 1f, durationMillis = 250L)
        }
    }

    private fun verifySingleDismissal(label: String, dismiss: () -> Unit) {
        captureLabel = label
        UiFailureDiagnostics.onFailure("more-options-$label") {
            render(UiStyle.Material, false)
            composeRule.onNodeWithTag(BodyTag).assertIsDisplayed()
            frame("idle")

            dismiss()
            composeRule.waitUntil(timeoutMillis = 5_000L) { !visible }
            composeRule.waitForIdle()

            composeRule.runOnIdle { assertEquals(1, dismissals) }
            composeRule.onNodeWithTag(BodyTag).assertDoesNotExist()
            frame("dismissed")
        }
    }

    private fun verifyBackGestures(style: UiStyle, dark: Boolean) {
        captureLabel = "${style.name.lowercase()}-${if (dark) "dark" else "light"}"
        UiFailureDiagnostics.onFailure("more-options-back-$captureLabel") {
            render(style, dark)
            val idle = frame("idle")
            assertTrue("夹具必须显示真实短面板", idle.height > 0)
            composeRule.mainClock.autoAdvance = false

            startGesture(NavigationEvent.EDGE_LEFT, 1f)
            val left = frame("left-progress")
            assertWholeBody(idle, left)
            assertTrue("左侧返回应向右偏移", left.centerX > idle.centerX)

            cancelGesture()
            assertRestored(idle, frame("left-cancelled"))

            startGesture(NavigationEvent.EDGE_LEFT, 0.8f)
            composeRule.runOnIdle { gestureInput.cancel() }
            advance(48L)
            startGesture(NavigationEvent.EDGE_RIGHT, 0.6f)
            advance(250L)
            val reversed = frame("reversed-before-cancel-settled")
            assertWholeBody(idle, reversed)
            assertTrue("反向手势必须取代前一次取消动画", reversed.centerX < idle.centerX)

            cancelGesture()
            assertRestored(idle, frame("reversed-cancelled"))
            composeRule.runOnIdle { assertEquals(0, dismissals) }

            startGesture(NavigationEvent.EDGE_RIGHT, 1f)
            val right = frame("right-progress")
            assertWholeBody(idle, right)
            assertTrue("右侧返回应向左偏移", right.centerX < idle.centerX)
            composeRule.runOnIdle { gestureInput.complete() }
            advance(500L)
            composeRule.runOnIdle { assertEquals(1, dismissals) }
            composeRule.onNodeWithTag(BodyTag).assertDoesNotExist()
            frame("committed")
        }
    }

    private fun render(style: UiStyle, dark: Boolean) {
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = dark,
                dynamicColor = false,
                seedColorHex = "6750A4",
                uiStyle = style,
                animateColors = false
            ) {
                Box(Modifier.fillMaxSize().background(Color(0xFF1E74D9)).testTag(HostTag))
                if (visible) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    val scope = rememberCoroutineScope()
                    val owner = remember(sheetState, scope) {
                        MoreOptionsSheetOwner(scope, sheetState::hide, sheetState::expand) {
                            dismissals++
                            visible = false
                        }
                    }
                    MoreOptionsSheetSurface(owner, sheetState, remember { SnackbarHostState() }) {
                        val view = LocalView.current
                        val dispatcher = requireNotNull(view.findViewTreeNavigationEventDispatcherOwner())
                            .navigationEventDispatcher
                        val color = MaterialTheme.colorScheme.surface
                        SideEffect { surfaceColor = color.toArgb() }
                        DisposableEffect(dispatcher) {
                            dispatcher.addInput(gestureInput)
                            onDispose { dispatcher.removeInput(gestureInput) }
                        }
                        Box(Modifier.fillMaxWidth().height(160.dp).testTag(BodyTag))
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun startGesture(edge: Int, progress: Float) {
        composeRule.runOnIdle {
            assertTrue("独立窗口必须有启用的返回处理器", gestureInput.hasEnabledHandlers)
            gestureInput.start(edge)
        }
        composeRule.runOnIdle { gestureInput.progress(edge, progress) }
        advance(16L)
    }

    private fun cancelGesture() {
        composeRule.runOnIdle { gestureInput.cancel() }
        advance(250L)
    }

    private fun advance(duration: Long) {
        composeRule.mainClock.advanceTimeBy(duration)
        composeRule.waitForIdle()
    }

    private fun assertWholeBody(idle: Frame, current: Frame) {
        assertTrue("手势必须缩小真实面板", current.height < idle.height)
        assertTrue("面板下缘不能被外层裁剪: idle=$idle current=$current", current.height >= idle.height * 0.85f)
    }

    private fun assertRestored(idle: Frame, current: Frame) {
        assertTrue("取消后面板应恢复完整高度: idle=$idle current=$current", abs(current.height - idle.height) <= 2)
        assertEquals(idle.centerX, current.centerX, 1f)
    }

    private fun frame(stage: String): Frame {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = if (visible) composeRule.onNode(isDialog()) else composeRule.onNodeWithTag(HostTag)
        val bitmap = root.captureToImage().asAndroidBitmap()
        try {
            val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
                ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(80)?.takeIf { it.isNotBlank() }
            if (prefix != null) {
                val file = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
                    "$prefix-$captureLabel-$stage.png")
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
            val rows = (0 until bitmap.height).filter { matchesSurface(bitmap.getPixel(bitmap.width / 2, it)) }
            if (rows.isEmpty()) return Frame(0, 0f)
            val y = rows[rows.size / 2]
            val columns = (0 until bitmap.width).filter { matchesSurface(bitmap.getPixel(it, y)) }
            return Frame(rows.size, (columns.first() + columns.last()) / 2f)
        } finally {
            bitmap.recycle()
        }
    }

    private fun matchesSurface(pixel: Int): Boolean =
        abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(surfaceColor)) <= 2 &&
            abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(surfaceColor)) <= 2 &&
            abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(surfaceColor)) <= 2

    private data class Frame(val height: Int, val centerX: Float)

    private class GestureInput : NavigationEventInput() {
        fun start(edge: Int) = dispatchOnBackStarted(NavigationEvent(swipeEdge = edge))
        fun progress(edge: Int, progress: Float) =
            dispatchOnBackProgressed(NavigationEvent(swipeEdge = edge, progress = progress))
        fun cancel() = dispatchOnBackCancelled()
        fun complete() = dispatchOnBackCompleted()
    }

    private companion object {
        const val BodyTag = "more-options-predictive-body"
        const val HostTag = "more-options-predictive-host"
    }
}
