package moe.ouom.neriplayer.testutil

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

internal fun SemanticsNodeInteraction.performNativeClick(): SemanticsNodeInteraction {
    val node = fetchSemanticsNode()
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
