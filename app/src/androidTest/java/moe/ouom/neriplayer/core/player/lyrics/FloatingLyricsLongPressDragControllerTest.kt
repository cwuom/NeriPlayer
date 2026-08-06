package moe.ouom.neriplayer.core.player.lyrics

import android.content.Context
import android.graphics.Point
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingLyricsLongPressDragControllerTest {

    @Test
    fun tapDispatchesAnAccessibilityClickWithoutStartingADrag() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = RecordingView(context)
        var dragStartCount = 0
        val controller = FloatingLyricsLongPressDragController(
            view = view,
            longPressTimeoutMs = 60_000L,
            touchSlopPx = 12f,
            initialPositionProvider = { Point(0, 0) },
            onDragStarted = { dragStartCount += 1 },
            onDragPositionChanged = { _, _ -> Unit },
            onDragEnded = { _, _ -> Unit }
        )
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 16f, 24f, 0)
        val up = MotionEvent.obtain(0L, 8L, MotionEvent.ACTION_UP, 16f, 24f, 0)

        try {
            controller.onTouch(view, down)
            controller.onTouch(view, up)
        } finally {
            down.recycle()
            up.recycle()
        }

        assertEquals(1, view.clickCount)
        assertEquals(0, dragStartCount)
    }

    private class RecordingView(context: Context) : View(context) {
        var clickCount = 0

        override fun performClick(): Boolean {
            clickCount += 1
            return super.performClick()
        }
    }
}
