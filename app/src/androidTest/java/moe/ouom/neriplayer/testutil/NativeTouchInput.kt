package moe.ouom.neriplayer.testutil

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

internal fun SemanticsNodeInteraction.performNativeClick(): SemanticsNodeInteraction {
    val node = awaitFocusedNativeInputNode()
    val point = node.positionOnScreen + androidx.compose.ui.geometry.Offset(node.size.width / 2f, node.size.height / 2f)
    val downTime = SystemClock.uptimeMillis()
    // 原生输入路径才能切换 Android touch mode，Compose 直接分发手势不会更新它
    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            assertTrue("原生触摸应被宿主接受: action=$action, point=$point",
                InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }
    return this
}

internal fun SemanticsNodeInteraction.performNativeBack(): SemanticsNodeInteraction {
    awaitFocusedNativeInputNode("原生返回")
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val downTime = SystemClock.uptimeMillis()
    // 原生返回通过 UiAutomation 同步窗口输入事务，避免丢键后误报业务状态失败
    for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
        val event = KeyEvent(
            downTime, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD
        )
        assertTrue("原生返回应被宿主接受: action=$action", automation.injectInputEvent(event, true))
    }
    return this
}

private fun SemanticsNodeInteraction.awaitFocusedNativeInputNode(input: String = "原生触摸"): SemanticsNode {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.uptimeMillis() + 5_000L
    var state = "not sampled"
    do {
        val node = fetchSemanticsNode()
        val view = checkNotNull(node.root as? ViewRootForTest) { "${input}需要 Android Compose 宿主" }.view
        var ready = false
        instrumentation.runOnMainSync {
            state = "attached=${view.isAttachedToWindow}, shown=${view.isShown}, focused=${view.hasWindowFocus()}"
            ready = view.isAttachedToWindow && view.isShown && view.hasWindowFocus()
        }
        // Compose 完成布局不代表窗口已经取得输入焦点，过早注入会被启动过渡层吞掉
        if (ready) return node
        SystemClock.sleep(10L)
    } while (SystemClock.uptimeMillis() < deadline)
    throw AssertionError("${input}目标窗口未就绪: $state")
}

internal fun sendNativeSoftKeyboardText(text: String) {
    val events = requireNotNull(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    // 软键盘输入应保留触摸模式，普通硬件按键会先触发窗口的焦点导航
    for (event in events) {
        val timed = KeyEvent.changeTimeRepeat(event, SystemClock.uptimeMillis(), 0)
        instrumentation.sendKeySync(KeyEvent.changeFlags(
            timed, timed.flags or KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        ))
    }
}

internal fun ComposeTestRule.awaitAboveKeyboard(action: SemanticsNodeInteraction) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val originalFlags = automation.serviceInfo.flags
    var previous: Rect? = null
    var stableSince = 0L
    var lastGeometry = "尚未取得窗口坐标"
    try {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        waitUntil(conditionDescription = "Touch target above the visible keyboard", timeoutMillis = 5_000) {
            val node = action.fetchSemanticsNode()
            val view = (node.root as ViewRootForTest).view
            val x = node.positionOnScreen.x.toInt()
            val y = node.positionOnScreen.y.toInt()
            val button = Rect(x, y, x + node.size.width, y + node.size.height)
            val keyboard = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val imeBounds = Rect().also { keyboard?.getBoundsInScreen(it) }
            lastGeometry = "screen=$button clipped=${node.boundsInRoot} size=${node.size} ime=$imeBounds"
            // Android 10 及以下按窗口遮挡推算 IME，浮窗避开键盘后需读取实际输入法窗口
            val imeVisible = !imeBounds.isEmpty && (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            val ready = imeVisible && !button.isEmpty && button.bottom <= imeBounds.top &&
                node.boundsInRoot.height >= with(density) { 32.dp.toPx() } &&
                node.boundsInRoot.height + 1f >= node.size.height
            // 输入法出现时浮窗还会移动，待按钮的屏幕坐标稳定后再注入触摸
            val now = SystemClock.uptimeMillis()
            if (!ready || previous != button) stableSince = now
            previous = button
            ready && now - stableSince >= 150
        }
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("键盘上方的触控区域未就绪 ($lastGeometry)", e)
    } finally {
        automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
    }
}
