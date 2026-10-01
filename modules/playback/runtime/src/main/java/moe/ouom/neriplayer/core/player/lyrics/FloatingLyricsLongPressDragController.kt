package moe.ouom.neriplayer.core.player.lyrics

import android.graphics.Point
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

internal class FloatingLyricsLongPressDragController(
    private val view: View,
    private val longPressTimeoutMs: Long,
    private val touchSlopPx: Float,
    private val initialPositionProvider: () -> Point,
    private val onDragStarted: () -> Unit,
    private val onDragPositionChanged: (Int, Int) -> Unit,
    private val onDragEnded: (Int, Int) -> Unit
) : View.OnTouchListener {
    private var trackingTouch = false
    private var dragging = false
    private var clickEligible = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var initialX = 0
    private var initialY = 0
    private var currentX = 0
    private var currentY = 0

    private val startDragRunnable = Runnable {
        if (!trackingTouch || dragging) {
            return@Runnable
        }
        dragging = true
        view.performLongClick()
        onDragStarted()
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginTracking(event)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                updateTracking(event)
                true
            }

            MotionEvent.ACTION_UP -> {
                val shouldPerformClick = !dragging && clickEligible
                finishTracking(commit = true)
                if (shouldPerformClick) {
                    view.performClick()
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                finishTracking(commit = true)
                true
            }

            else -> trackingTouch
        }
    }

    fun cancel() {
        view.removeCallbacks(startDragRunnable)
        trackingTouch = false
        dragging = false
        clickEligible = false
    }

    private fun beginTracking(event: MotionEvent) {
        cancel()
        val initialPosition = initialPositionProvider()
        trackingTouch = true
        clickEligible = true
        downRawX = event.rawX
        downRawY = event.rawY
        initialX = initialPosition.x
        initialY = initialPosition.y
        currentX = initialX
        currentY = initialY
        view.postDelayed(startDragRunnable, longPressTimeoutMs)
    }

    private fun updateTracking(event: MotionEvent) {
        if (!trackingTouch) {
            return
        }
        val deltaX = event.rawX - downRawX
        val deltaY = event.rawY - downRawY
        if (!dragging && (abs(deltaX) > touchSlopPx || abs(deltaY) > touchSlopPx)) {
            view.removeCallbacks(startDragRunnable)
            clickEligible = false
            return
        }
        if (!dragging) {
            return
        }
        currentX = initialX + deltaX.roundToInt()
        currentY = initialY + deltaY.roundToInt()
        onDragPositionChanged(currentX, currentY)
    }

    private fun finishTracking(commit: Boolean) {
        val wasDragging = dragging
        view.removeCallbacks(startDragRunnable)
        trackingTouch = false
        dragging = false
        clickEligible = false
        if (commit && wasDragging) {
            onDragEnded(currentX, currentY)
        }
    }
}
