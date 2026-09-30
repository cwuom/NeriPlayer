package moe.ouom.neriplayer.core.player.usb.transport

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class UsbExclusiveIoGate {
    private val acceptingWrites = AtomicBoolean(false)
    private val activeWriters = AtomicInteger(0)
    private val drainLock = ReentrantLock()
    private val drainedCondition = drainLock.newCondition()

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
        return drainLock.withLock {
            while (activeWriters.get() > 0) {
                if (timeoutMs <= 0L) {
                    drainedCondition.await()
                    continue
                }
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) return@withLock false
                drainedCondition.awaitNanos(remainingNs)
            }
            true
        }
    }

    private fun signalIfDrained() {
        if (activeWriters.get() != 0) return
        drainLock.withLock {
            drainedCondition.signalAll()
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
