package moe.ouom.neriplayer.testutil

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
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
