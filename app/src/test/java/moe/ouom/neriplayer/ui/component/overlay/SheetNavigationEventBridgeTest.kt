package moe.ouom.neriplayer.ui.component.overlay

import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetNavigationEventBridgeTest {

    @Test
    fun `back gestures on the dialog reach handlers registered on the sheet`() {
        val dialog = NavigationEventDispatcher()
        val gesture = GestureInput().also(dialog::addInput)
        val bridge = SheetNavigationEventBridge()
        val sheet = RecordingHandler().also(bridge.navigationEventDispatcher::addHandler)
        bridge.attachTo(dialog)

        gesture.start(0.1f)
        gesture.progress(0.6f)
        gesture.complete()

        assertEquals(listOf("started 0.1", "progressed 0.6", "completed"), sheet.events)
        assertTrue(gesture.dialogHasHandlers)
    }

    @Test
    fun `cancelled gestures are forwarded so the sheet can settle back`() {
        val dialog = NavigationEventDispatcher()
        val gesture = GestureInput().also(dialog::addInput)
        val bridge = SheetNavigationEventBridge()
        val sheet = RecordingHandler().also(bridge.navigationEventDispatcher::addHandler)
        bridge.attachTo(dialog)

        gesture.start(0.2f)
        gesture.cancel()

        assertEquals(listOf("started 0.2", "cancelled"), sheet.events)
    }

    @Test
    fun `disabled preview keeps the sheet still until release and then performs ordinary back`() {
        val dialog = NavigationEventDispatcher()
        val gesture = GestureInput().also(dialog::addInput)
        val bridge = SheetNavigationEventBridge().apply { previewEnabled = false }
        val sheet = RecordingHandler().also(bridge.navigationEventDispatcher::addHandler)
        bridge.attachTo(dialog)

        gesture.start(0.1f)
        gesture.progress(0.6f)
        assertTrue("预览关闭时，手指拖动不能推进弹层", sheet.events.isEmpty())

        gesture.complete()
        assertEquals(listOf("started 0.0", "completed"), sheet.events)
    }

    @Test
    fun `disabled preview cancellation never starts sheet navigation`() {
        val dialog = NavigationEventDispatcher()
        val gesture = GestureInput().also(dialog::addInput)
        val bridge = SheetNavigationEventBridge().apply { previewEnabled = false }
        val sheet = RecordingHandler().also(bridge.navigationEventDispatcher::addHandler)
        bridge.attachTo(dialog)

        gesture.start(0.1f)
        gesture.progress(0.6f)
        gesture.cancel()

        assertTrue("取消手势不能关闭弹层或启动动画", sheet.events.isEmpty())
    }

    @Test
    fun `preview policy changes apply to the next gesture without changing the current owner`() {
        val dialog = NavigationEventDispatcher()
        val gesture = GestureInput().also(dialog::addInput)
        val bridge = SheetNavigationEventBridge().apply { previewEnabled = false }
        val sheet = RecordingHandler().also(bridge.navigationEventDispatcher::addHandler)
        bridge.attachTo(dialog)

        gesture.start(0.1f)
        bridge.previewEnabled = true
        gesture.progress(0.6f)
        gesture.complete()
        assertEquals(listOf("started 0.0", "completed"), sheet.events)

        sheet.events.clear()
        gesture.start(0.2f)
        bridge.previewEnabled = false
        gesture.progress(0.7f)
        gesture.cancel()
        assertEquals(listOf("started 0.2", "progressed 0.7", "cancelled"), sheet.events)
    }

    @Test
    fun `the dialog keeps its default back when the sheet has no enabled handler`() {
        val dialog = NavigationEventDispatcher()
        val gesture = GestureInput().also(dialog::addInput)
        val bridge = SheetNavigationEventBridge()
        val sheet = RecordingHandler().also(bridge.navigationEventDispatcher::addHandler)
        val detach = bridge.attachTo(dialog)
        assertTrue(gesture.dialogHasHandlers)

        sheet.isBackEnabled = false
        assertFalse(gesture.dialogHasHandlers)

        sheet.isBackEnabled = true
        detach()
        assertFalse(gesture.dialogHasHandlers)
    }

    private class GestureInput : NavigationEventInput() {
        var dialogHasHandlers = false

        override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
            dialogHasHandlers = hasEnabledHandlers
        }

        fun start(progress: Float) = dispatchOnBackStarted(NavigationEvent(progress = progress))
        fun progress(progress: Float) = dispatchOnBackProgressed(NavigationEvent(progress = progress))
        fun complete() = dispatchOnBackCompleted()
        fun cancel() = dispatchOnBackCancelled()
    }

    private class RecordingHandler :
        NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, true) {
        val events = mutableListOf<String>()

        override fun onBackStarted(event: NavigationEvent) {
            events += "started ${event.progress}"
        }

        override fun onBackProgressed(event: NavigationEvent) {
            events += "progressed ${event.progress}"
        }

        override fun onBackCompleted() {
            events += "completed"
        }

        override fun onBackCancelled() {
            events += "cancelled"
        }
    }
}
