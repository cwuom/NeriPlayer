package moe.ouom.neriplayer.core.player.usb

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class UsbExclusiveIoGate {
    private val acceptingWrites = AtomicBoolean(false)
    private val activeWriters = AtomicInteger(0)
    private val drainMonitor = Object()

    fun open() {
        acceptingWrites.set(true)
    }

    fun close() {
        acceptingWrites.set(false)
        signalIfDrained()
    }

    fun isOpen(): Boolean = acceptingWrites.get()

    fun tryEnterWrite(): Boolean {
        if (!acceptingWrites.get()) return false
        activeWriters.incrementAndGet()
        if (acceptingWrites.get()) return true
        exitWrite()
        return false
    }

    fun exitWrite() {
        val remaining = activeWriters.decrementAndGet()
        check(remaining >= 0) { "USB exclusive writer count became negative" }
        signalIfDrained()
    }

    fun awaitDrained(timeoutMs: Long = 0L): Boolean {
        if (activeWriters.get() == 0) return true
        val deadlineNs = if (timeoutMs > 0L) {
            System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
        } else {
            Long.MAX_VALUE
        }
        synchronized(drainMonitor) {
            while (activeWriters.get() > 0) {
                if (timeoutMs <= 0L) {
                    drainMonitor.wait()
                    continue
                }
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) return false
                val waitMs = (remainingNs / NANOS_PER_MILLISECOND).coerceAtLeast(1L)
                drainMonitor.wait(waitMs)
            }
        }
        return true
    }

    private fun signalIfDrained() {
        if (activeWriters.get() != 0) return
        synchronized(drainMonitor) {
            drainMonitor.notifyAll()
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
