package moe.ouom.neriplayer.core.player.lyrics

import android.app.Activity
import android.graphics.Point
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class FloatingLyricsLongPressDragControllerTest {
    private lateinit var view: View
    private lateinit var controller: FloatingLyricsLongPressDragController
    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = View(activity).apply {
            setOnClickListener { events += "click" }
            setOnLongClickListener {
                events += "longClick"
                true
            }
        }
        activity.setContentView(view)
        controller = FloatingLyricsLongPressDragController(
            view = view,
            longPressTimeoutMs = LONG_PRESS_MS,
            touchSlopPx = TOUCH_SLOP_PX,
            initialPositionProvider = { Point(100, 200) },
            onDragStarted = { events += "dragStarted" },
            onDragPositionChanged = { x, y -> events += "move:$x,$y" },
            onDragEnded = { x, y -> events += "end:$x,$y" }
        )
        view.setOnTouchListener(controller)
    }

    @Test
    fun `quick tap performs a click without dragging`() {
        touch(MotionEvent.ACTION_DOWN, 10f, 10f)
        touch(MotionEvent.ACTION_MOVE, 12f, 11f)
        touch(MotionEvent.ACTION_UP, 12f, 11f)
        advance(LONG_PRESS_MS * 2)

        assertEquals(listOf("click"), events)
    }

    @Test
    fun `long press starts a drag that follows the finger and commits on release`() {
        touch(MotionEvent.ACTION_DOWN, 10f, 10f)
        advance(LONG_PRESS_MS + 1)
        touch(MotionEvent.ACTION_MOVE, 40f, -20f)
        touch(MotionEvent.ACTION_MOVE, 55.4f, 5.6f)
        touch(MotionEvent.ACTION_UP, 55.4f, 5.6f)

        assertEquals(
            listOf("longClick", "dragStarted", "move:130,170", "move:145,196", "end:145,196"),
            events
        )
    }

    @Test
    fun `moving beyond the touch slop before the timeout cancels both drag and click`() {
        touch(MotionEvent.ACTION_DOWN, 10f, 10f)
        touch(MotionEvent.ACTION_MOVE, 10f, 10f + TOUCH_SLOP_PX + 1f)
        advance(LONG_PRESS_MS * 2)
        touch(MotionEvent.ACTION_MOVE, 80f, 80f)
        touch(MotionEvent.ACTION_UP, 80f, 80f)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `cancel during a drag still commits the last position`() {
        touch(MotionEvent.ACTION_DOWN, 0f, 0f)
        advance(LONG_PRESS_MS + 1)
        touch(MotionEvent.ACTION_MOVE, 5f, 6f)
        touch(MotionEvent.ACTION_CANCEL, 5f, 6f)

        assertEquals(listOf("longClick", "dragStarted", "move:105,206", "end:105,206"), events)
    }

    @Test
    fun `cancelled gesture without a drag reports nothing`() {
        touch(MotionEvent.ACTION_DOWN, 0f, 0f)
        touch(MotionEvent.ACTION_CANCEL, 0f, 0f)
        advance(LONG_PRESS_MS * 2)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `external cancel stops a pending long press`() {
        touch(MotionEvent.ACTION_DOWN, 0f, 0f)
        controller.cancel()
        advance(LONG_PRESS_MS * 2)
        touch(MotionEvent.ACTION_MOVE, 30f, 30f)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `untracked moves are ignored and other actions report tracking state`() {
        assertTrue(touch(MotionEvent.ACTION_MOVE, 30f, 30f))
        assertFalse(touch(MotionEvent.ACTION_HOVER_MOVE, 30f, 30f))

        touch(MotionEvent.ACTION_DOWN, 0f, 0f)
        assertTrue(touch(MotionEvent.ACTION_HOVER_MOVE, 0f, 0f))

        assertTrue(events.isEmpty())
    }

    private fun touch(action: Int, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        return try {
            controller.onTouch(view, event)
        } finally {
            event.recycle()
        }
    }

    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    private companion object {
        const val LONG_PRESS_MS = 400L
        const val TOUCH_SLOP_PX = 8f
    }
}
