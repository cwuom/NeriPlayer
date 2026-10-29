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

private fun SemanticsNodeInteraction.awaitFocusedNativeInputNode(): SemanticsNode {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.uptimeMillis() + 5_000L
    var state = "not sampled"
    do {
        val node = fetchSemanticsNode()
        val view = checkNotNull(node.root as? ViewRootForTest) { "原生触摸需要 Android Compose 宿主" }.view
        var ready = false
        instrumentation.runOnMainSync {
            state = "attached=${view.isAttachedToWindow}, shown=${view.isShown}, focused=${view.hasWindowFocus()}"
            ready = view.isAttachedToWindow && view.isShown && view.hasWindowFocus()
        }
        // Compose 完成布局不代表窗口已经取得输入焦点，过早注入会被启动过渡层吞掉
        if (ready) return node
        SystemClock.sleep(10L)
    } while (SystemClock.uptimeMillis() < deadline)
    throw AssertionError("原生触摸目标窗口未就绪: $state")
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
