package moe.ouom.neriplayer.ui.screen.nowplaying

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
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
import moe.ouom.neriplayer.testutil.performNativeBack
import moe.ouom.neriplayer.ui.component.common.PREDICTIVE_DISMISS_CANCEL_DURATION_MS
import moe.ouom.neriplayer.ui.navigation.LocalPredictiveBackPreviewEnabled
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.json.JSONArray
import org.json.JSONObject
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
    private lateinit var sheetOwner: MoreOptionsSheetOwner
    private lateinit var observedSheetState: SheetState
    private lateinit var observedSheetView: View
    private var observedTouchSlop = Float.NaN
    private val observedDragInputs = mutableListOf<JSONObject>()

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
    fun shortSheetKeepsItsBottomOutsideTheWindowThroughBackGestures() =
        verifyBackGestures(UiStyle.Material, false, tall = false)

    @Test
    fun subPageOwnsBackBeforeTheNativeSheetDismisses() {
        captureLabel = "sub-page-owner"
        render(UiStyle.Material, false, startPage = MoreOptionsPage.FONT_SIZE)
        composeRule.onNodeWithTag("$PageTag${MoreOptionsPage.FONT_SIZE.name}").assertIsDisplayed()
        val idle = frame("idle")
        composeRule.mainClock.autoAdvance = false

        startGesture(NavigationEvent.EDGE_LEFT, 0.7f)
        assertRestored(idle, frame("sub-page-progress"))
        cancelGesture(idle, "sub-page-cancelled")
        composeRule.runOnIdle {
            assertEquals(MoreOptionsPage.FONT_SIZE, sheetOwner.page)
            assertEquals(0, dismissals)
        }

        startGesture(NavigationEvent.EDGE_RIGHT, 0.8f)
        composeRule.runOnIdle { gestureInput.complete() }
        advance(600L)
        composeRule.runOnIdle {
            assertEquals(MoreOptionsPage.MAIN, sheetOwner.page)
            assertTrue(visible)
            assertEquals(0, dismissals)
        }
        composeRule.onNodeWithTag("$PageTag${MoreOptionsPage.MAIN.name}").assertIsDisplayed()
        assertRestored(idle, frame("back-at-main"))

        startGesture(NavigationEvent.EDGE_LEFT, 1f)
        assertSheetPreview(idle, frame("native-sheet-progress"))
        completeGesture()
        composeRule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun savingInterceptsWindowBackUntilTheEditPageIsUnlocked() {
        captureLabel = "saving-back-owner"
        render(UiStyle.Material, false, startPage = MoreOptionsPage.EDIT_INFO, saving = true)
        composeRule.onNodeWithTag("$PageTag${MoreOptionsPage.EDIT_INFO.name}").assertIsDisplayed()
        val idle = frame("idle")
        composeRule.onNodeWithTag(BodyTag).performNativeBack()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        startGesture(NavigationEvent.EDGE_LEFT, 0.8f)
        cancelGesture(idle, "saving-cancelled")
        startGesture(NavigationEvent.EDGE_RIGHT, 1f)
        composeRule.runOnIdle { gestureInput.complete() }
        advance(500L)
        assertRestored(idle, frame("saving-still-open"))
        composeRule.runOnIdle {
            assertEquals(MoreOptionsPage.EDIT_INFO, sheetOwner.page)
            assertEquals(0, dismissals)
            assertTrue(visible)
            sheetOwner.setEditSaving(false)
        }
        advance(16L)

        composeRule.onNodeWithTag(BodyTag).performNativeBack()
        advance(600L)
        composeRule.runOnIdle {
            assertEquals(MoreOptionsPage.MAIN, sheetOwner.page)
            assertEquals(0, dismissals)
            assertTrue(visible)
        }
        composeRule.onNodeWithTag(BodyTag).performNativeBack()
        awaitDismissal()
        composeRule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun systemBackClosesTheRealSheetExactlyOnce() = verifySingleDismissal("system-back") {
        composeRule.onNodeWithTag(BodyTag).performNativeBack()
    }

    @Test
    fun draggingDownFromTheUpperBodyClosesTheRealSheetExactlyOnce() = verifySingleDismissal("drag-down") {
        composeRule.onNodeWithTag(BodyTag).performTouchInput {
            swipeDown(startY = 1f, endY = height - 1f, durationMillis = 250L)
        }
    }

    @Test
    fun materialMainTallSheetSettlesAfterRapidHandleDragReversal() =
        verifyRapidHandleDragReversal(UiStyle.Material, tall = true, fromSubPage = false)

    @Test
    fun cleanMainShortSheetSettlesAfterRapidHandleDragReversal() =
        verifyRapidHandleDragReversal(UiStyle.Clean, tall = false, fromSubPage = false)

    @Test
    fun materialShortSubPageAndMainSettleAfterRapidHandleDragReversal() =
        verifyRapidHandleDragReversal(UiStyle.Material, tall = false, fromSubPage = true)

    @Test
    fun cleanTallSubPageAndMainSettleAfterRapidHandleDragReversal() =
        verifyRapidHandleDragReversal(UiStyle.Clean, tall = true, fromSubPage = true)

    private fun verifyRapidHandleDragReversal(style: UiStyle, tall: Boolean, fromSubPage: Boolean) {
        captureLabel = "issue60-${style.name.lowercase()}-${if (tall) "tall" else "short"}-" +
            if (fromSubPage) "sub-page" else "main"
        val samples = mutableListOf<SheetDragSample>()
        var failure: Throwable? = null
        try {
            UiFailureDiagnostics.onFailure(captureLabel) {
                render(style, dark = false, tall = tall,
                    startPage = if (fromSubPage) MoreOptionsPage.FONT_SIZE else MoreOptionsPage.MAIN)
                composeRule.mainClock.autoAdvance = false
                verifyHandleReversal(samples, if (fromSubPage) "sub-page" else "main")
                if (fromSubPage) {
                    composeRule.onNodeWithTag(BodyTag).performNativeBack()
                    observeSheetDrag(samples, "return-to-main", 600L)
                    composeRule.runOnIdle {
                        assertEquals("窗口返回应进入面板主页", MoreOptionsPage.MAIN, sheetOwner.page)
                        assertEquals(0, dismissals)
                        assertTrue(visible)
                    }
                    composeRule.onNodeWithTag("$PageTag${MoreOptionsPage.MAIN.name}").assertIsDisplayed()
                    verifyHandleReversal(samples, "returned-main")
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            saveSheetDragTrace(samples, failure)
        }
    }

    private fun verifyHandleReversal(samples: MutableList<SheetDragSample>, stage: String) {
        val baseline = observeSheetDrag(samples, "$stage-baseline", 200L)
        assertSettledSheet(baseline, baseline.last().offset)
        val root = composeRule.onNode(isDialog())
        val description = composeRule.activity.getString(
            androidx.compose.material3.R.string.m3c_bottom_sheet_drag_handle_description
        )
        val handle = composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val handleBounds = handle.fetchSemanticsNode().boundsInRoot
        val start = handleBounds.center - rootBounds.topLeft
        val downDistance = with(composeRule.density) { 32.dp.toPx() }.coerceAtLeast(observedTouchSlop * 2f)
        val upDistance = with(composeRule.density) {
            80.dp.toPx().coerceAtLeast(downDistance + 24.dp.toPx())
        }
        val pulled = start + Offset(0f, downDistance)
        val returned = pulled - Offset(0f, upDistance)
        assertTrue("夹具需提供实际 Compose touchSlop", observedTouchSlop.isFinite() && observedTouchSlop > 0f)
        assertTrue("夹具中的完整触摸轨迹必须留在窗口内",
            start.x > 0f && start.x < rootBounds.width && returned.y > 0f && pulled.y < rootBounds.height)
        observedDragInputs += JSONObject().put("stage", stage)
            .put("clockMillis", composeRule.mainClock.currentTime)
            .put("startX", start.x).put("startY", start.y)
            .put("downDistancePx", downDistance).put("upDistancePx", upDistance)
            .put("windowWidthPx", rootBounds.width).put("windowHeightPx", rootBounds.height)
        val idle = frame("$stage-before-drag")
        var pointerDown = false
        try {
            root.performTouchInput {
                down(start)
                pointerDown = true
                repeat(3) { index ->
                    moveTo(start + Offset(0f, downDistance * (index + 1) / 3f), delayMillis = 10L)
                }
            }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            val pulledSample = sheetDragSample("$stage-pulled").also(samples::add)
            assertTrue("夹具必须真的向下拖动 Sheet，不能只命中点击: $pulledSample",
                pulledSample.offset != null && baseline.last().offset != null &&
                    pulledSample.offset > checkNotNull(baseline.last().offset) + 1f)
            root.performTouchInput {
                repeat(4) { index ->
                    moveTo(pulled - Offset(0f, upDistance * (index + 1) / 4f), delayMillis = 10L)
                }
                up()
                pointerDown = false
            }
        } finally {
            if (pointerDown) root.performTouchInput { cancel() }
        }
        val released = observeSheetDrag(samples, "$stage-released", 2_000L)
        assertTrue("真实触摸全程不能误关闭或丢失窗口焦点",
            (baseline + released).all { it.visible && it.dismissals == 0 && it.attached && it.focused })
        assertSettledSheet(released.takeLast(12), baseline.last().offset)
        assertRestored(idle, frame("$stage-after-drag"))
    }

    private fun observeSheetDrag(
        all: MutableList<SheetDragSample>,
        stage: String,
        durationMillis: Long
    ): List<SheetDragSample> {
        val observed = mutableListOf(sheetDragSample(stage))
        val deadline = composeRule.mainClock.currentTime + durationMillis
        while (composeRule.mainClock.currentTime < deadline) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            observed += sheetDragSample(stage)
        }
        all += observed
        return observed
    }

    private fun sheetDragSample(stage: String): SheetDragSample = composeRule.runOnIdle {
        val offset = runCatching { observedSheetState.requireOffset() }
        SheetDragSample(composeRule.mainClock.currentTime, stage, offset.getOrNull(),
            offset.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" },
            observedSheetState.currentValue, observedSheetState.targetValue, observedSheetState.isAnimationRunning,
            visible, dismissals, sheetOwner.page, observedSheetView.isAttachedToWindow,
            observedSheetView.hasWindowFocus())
    }

    private fun assertSettledSheet(samples: List<SheetDragSample>, expectedOffset: Float?) {
        assertTrue("需要连续稳定帧", samples.size >= 12 && expectedOffset != null && expectedOffset.isFinite())
        assertTrue("真实 Sheet 必须稳定在展开锚点: ${samples.last()}", samples.all {
            it.current == SheetValue.Expanded && it.target == SheetValue.Expanded && !it.running &&
                it.offset != null && it.offset.isFinite() && abs(it.offset - checkNotNull(expectedOffset)) <= 1f &&
                it.visible && it.dismissals == 0 && it.attached && it.focused
        })
    }

    private fun saveSheetDragTrace(samples: List<SheetDragSample>, failure: Throwable?) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(80)?.takeIf { it.isNotBlank() } ?: "issue60"
        val trace = JSONObject()
            .put("sdk", Build.VERSION.SDK_INT)
            .put("captureLabel", captureLabel)
            .put("inputGestures", JSONArray(observedDragInputs))
            .put("downSegments", 3).put("downSegmentMillis", 10)
            .put("upSegments", 4).put("upSegmentMillis", 10)
            .put("framesBeforeReversal", 1)
            .put("composeTouchSlopPx", observedTouchSlop.takeIf { it.isFinite() } ?: JSONObject.NULL)
            .put("failure", failure?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: JSONObject.NULL)
            .put("samples", JSONArray(samples.map { sample ->
                JSONObject().put("clockMillis", sample.clockMillis).put("stage", sample.stage)
                    .put("offset", sample.offset?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                    .put("offsetError", sample.offsetError ?: JSONObject.NULL)
                    .put("current", sample.current.name).put("target", sample.target.name)
                    .put("running", sample.running).put("visible", sample.visible)
                    .put("dismissals", sample.dismissals).put("page", sample.page.name)
                    .put("attached", sample.attached).put("focused", sample.focused)
            }))
        if (::observedSheetView.isInitialized) {
            val resources = observedSheetView.resources
            trace.put("densityDpi", resources.displayMetrics.densityDpi)
                .put("fontScale", resources.configuration.fontScale)
                .put("smallestScreenWidthDp", resources.configuration.smallestScreenWidthDp)
        }
        val file = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "$prefix-$captureLabel-drag-trace-${SystemClock.elapsedRealtimeNanos()}.json")
        file.writeText(trace.toString(2))
        android.util.Log.i("NeriIssue60", "drag trace: ${file.absolutePath}")
    }

    private data class SheetDragSample(
        val clockMillis: Long,
        val stage: String,
        val offset: Float?,
        val offsetError: String?,
        val current: SheetValue,
        val target: SheetValue,
        val running: Boolean,
        val visible: Boolean,
        val dismissals: Int,
        val page: MoreOptionsPage,
        val attached: Boolean,
        val focused: Boolean
    )

    private fun verifySingleDismissal(label: String, dismiss: () -> Unit) {
        captureLabel = label
        UiFailureDiagnostics.onFailure("more-options-$label") {
            render(UiStyle.Material, false, tall = false)
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

    private fun verifyBackGestures(style: UiStyle, dark: Boolean, tall: Boolean = true) {
        captureLabel = "${style.name.lowercase()}-${if (dark) "dark" else "light"}-${if (tall) "tall" else "short"}"
        UiFailureDiagnostics.onFailure("more-options-back-$captureLabel") {
            render(style, dark, tall)
            val idle = frame("idle")
            assertTrue("夹具必须显示真实面板", idle.height > 0)
            composeRule.mainClock.autoAdvance = false

            startGesture(NavigationEvent.EDGE_LEFT, 1f)
            val left = frame("left-progress")
            assertSheetPreview(idle, left)

            cancelGesture(idle, "left-cancelled")

            startGesture(NavigationEvent.EDGE_LEFT, 0.8f)
            composeRule.runOnIdle { gestureInput.cancel() }
            advance(48L)
            startGesture(NavigationEvent.EDGE_RIGHT, 0.6f)
            val reversedStart = frame("reversed-start")
            advance(250L)
            val reversed = frame("reversed-before-cancel-settled")
            assertSheetPreview(idle, reversed)
            assertEquals("旧取消动画不能覆盖新手势", reversedStart.width, reversed.width)
            assertTrue("部分进度应比完整手势缩得更少", reversed.width > left.width)

            cancelGesture(idle, "reversed-cancelled")
            composeRule.runOnIdle { assertEquals(0, dismissals) }

            startGesture(NavigationEvent.EDGE_RIGHT, 1f)
            val right = frame("right-progress")
            assertSheetPreview(idle, right)
            assertTrue("左右边缘应使用相同的面板缩放", abs(left.width - right.width) <= 2)
            completeGesture()
            composeRule.runOnIdle { assertEquals(1, dismissals) }
            composeRule.onNodeWithTag(BodyTag).assertDoesNotExist()
            frame("committed")
        }
    }

    private fun render(
        style: UiStyle,
        dark: Boolean,
        tall: Boolean = true,
        startPage: MoreOptionsPage = MoreOptionsPage.MAIN,
        saving: Boolean = false
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalPredictiveBackPreviewEnabled provides true) {
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
                            }.apply {
                                open(startPage)
                                setEditSaving(saving)
                            }
                        }
                        SideEffect {
                            sheetOwner = owner
                            observedSheetState = sheetState
                        }
                        MoreOptionsSheetSurface(owner, sheetState, remember { SnackbarHostState() }) {
                            val view = LocalView.current
                            val touchSlop = LocalViewConfiguration.current.touchSlop
                            SideEffect {
                                observedSheetView = view
                                observedTouchSlop = touchSlop
                            }
                            val dispatcher = requireNotNull(view.findViewTreeNavigationEventDispatcherOwner())
                                .navigationEventDispatcher
                            val color = MaterialTheme.colorScheme.surface
                            SideEffect {
                                surfaceColor = color.toArgb()
                            }
                            DisposableEffect(dispatcher) {
                                dispatcher.addInput(gestureInput)
                                onDispose { dispatcher.removeInput(gestureInput) }
                            }
                            val bodyHeight = if (tall) LocalWindowInfo.current.containerDpSize.height * 0.75f else 160.dp
                            MoreOptionsAnimatedPage(owner) { page ->
                                Box(Modifier.fillMaxWidth().height(bodyHeight).testTag(BodyTag)) {
                                    Box(Modifier.fillMaxSize().testTag("$PageTag${page.name}"))
                                }
                            }
                        }
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

    private fun cancelGesture(idle: Frame, stage: String) {
        composeRule.runOnIdle { gestureInput.cancel() }
        // 先推进完整虚拟回弹，避免逐帧截图耗尽墙钟等待时动画还没走完
        composeRule.mainClock.advanceTimeByFrame()
        advance(PREDICTIVE_DISMISS_CANCEL_DURATION_MS.toLong())
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            advance(16L)
            isRestored(idle, frame(stage, save = false))
        }
        assertRestored(idle, frame(stage))
    }

    private fun completeGesture() {
        composeRule.runOnIdle { gestureInput.complete() }
        awaitDismissal()
    }

    private fun awaitDismissal() {
        // 先完成冻结时钟中的关闭动画，避免慢帧耗尽墙钟等待
        composeRule.mainClock.advanceTimeUntil(timeoutMillis = 3_000L) { !visible }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            !visible && composeRule.onAllNodesWithTag(BodyTag).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag(BodyTag).assertDoesNotExist()
    }

    private fun advance(duration: Long) {
        composeRule.mainClock.advanceTimeBy(duration)
        composeRule.waitForIdle()
    }

    private fun assertSheetPreview(idle: Frame, current: Frame) {
        assertTrue("预测返回不能把面板底边拉进窗口: $current", current.bottomCovered)
        assertEquals("面板必须保持对称居中", idle.centerX, current.centerX, 1f)
        assertTrue("手势必须缩小整个面板", current.width in 1 until idle.width)
        assertTrue("两侧顶角必须完整且保持圆角", current.topCornersComplete)
    }

    private fun assertRestored(idle: Frame, current: Frame) {
        assertTrue("取消后面板应恢复完整高度: idle=$idle current=$current", abs(current.height - idle.height) <= 2)
        assertTrue("取消后面板应恢复完整宽度", abs(current.width - idle.width) <= 2)
        assertEquals(idle.centerX, current.centerX, 1f)
        assertTrue("面板底部应持续覆盖窗口", current.bottomCovered)
        assertTrue("恢复后顶角不能缺失", current.topCornersComplete)
    }

    private fun isRestored(idle: Frame, current: Frame): Boolean =
        abs(current.height - idle.height) <= 2 && abs(current.width - idle.width) <= 2 &&
            abs(current.centerX - idle.centerX) <= 1f && current.bottomCovered && current.topCornersComplete

    private fun frame(stage: String, save: Boolean = true): Frame {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = if (visible) composeRule.onNode(isDialog()) else composeRule.onNodeWithTag(HostTag)
        val bitmap = root.captureToImage().asAndroidBitmap()
        try {
            val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
                ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(80)?.takeIf { it.isNotBlank() }
            if (save && prefix != null) {
                val file = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
                    "$prefix-$captureLabel-$stage.png")
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
            val rows = (0 until bitmap.height).filter { matchesSurface(bitmap.getPixel(bitmap.width / 2, it)) }
            if (rows.isEmpty()) return Frame(0, 0, 0f, false, false)
            val top = rows.first()
            val bottom = rows.last()
            val y = (top + bottom) / 2
            val columns = (0 until bitmap.width).filter { matchesSurface(bitmap.getPixel(it, y)) }
            val left = columns.first()
            val right = columns.last()
            val inset = with(composeRule.density) { 40.dp.roundToPx() }
            val band = with(composeRule.density) { 16.dp.roundToPx() }
            val bottomCovered = (bitmap.width / 2 - band..bitmap.width / 2 + band).all { x ->
                matchesSurface(bitmap.getPixel(x, bitmap.height - 2))
            }
            val topCornersComplete = top + 2 < bitmap.height && right - left > inset * 2 &&
                matchesSurface(bitmap.getPixel(left + inset, top + 2)) &&
                matchesSurface(bitmap.getPixel(right - inset, top + 2)) &&
                !matchesSurface(bitmap.getPixel(left + 1, top + 1)) &&
                !matchesSurface(bitmap.getPixel(right - 1, top + 1))
            return Frame(bottom - top + 1, right - left + 1, (left + right) / 2f, bottomCovered, topCornersComplete)
        } finally {
            bitmap.recycle()
        }
    }

    private fun matchesSurface(pixel: Int): Boolean =
        abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(surfaceColor)) <= 2 &&
            abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(surfaceColor)) <= 2 &&
            abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(surfaceColor)) <= 2

    private data class Frame(
        val height: Int,
        val width: Int,
        val centerX: Float,
        val bottomCovered: Boolean,
        val topCornersComplete: Boolean
    )

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
        const val PageTag = "more-options-predictive-page-"
    }
}
