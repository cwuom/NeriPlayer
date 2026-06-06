package moe.ouom.neriplayer.activity

import android.os.Handler
import android.os.Looper

internal class WebLoginCompletionWatcher(
    private val onCheck: () -> Boolean,
    private val pollIntervalMs: Long = 800L,
    private val debounceMs: Long = 200L
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var checkScheduled = false

    private val debouncedCheckRunnable = Runnable {
        checkScheduled = false
        if (running && onCheck()) {
            stop()
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) {
                return
            }
            if (onCheck()) {
                stop()
                return
            }
            handler.postDelayed(this, pollIntervalMs)
        }
    }

    fun start() {
        if (running) {
            return
        }
        running = true
        scheduleCheck(delayMs = 0L)
        handler.postDelayed(pollRunnable, pollIntervalMs)
    }

    fun stop() {
        running = false
        checkScheduled = false
        handler.removeCallbacksAndMessages(null)
    }

    fun scheduleCheck(delayMs: Long = debounceMs) {
        if (!running) {
            return
        }
        handler.removeCallbacks(debouncedCheckRunnable)
        checkScheduled = true
        handler.postDelayed(debouncedCheckRunnable, delayMs.coerceAtLeast(0L))
    }
}
