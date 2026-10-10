package moe.ouom.neriplayer.core.player.usb.transport

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveIoGateDrainWakeupTest {

    @Test
    fun `timed drain returns as soon as the last writer exits`() {
        assertDrainWakesWhenWriterExits(timeoutMs = 60_000L, parkedState = Thread.State.TIMED_WAITING)
    }

    @Test
    fun `untimed drain blocks until the last writer exits`() {
        assertDrainWakesWhenWriterExits(timeoutMs = 0L, parkedState = Thread.State.WAITING)
    }

    private fun assertDrainWakesWhenWriterExits(timeoutMs: Long, parkedState: Thread.State) {
        val gate = UsbExclusiveIoGate()
        gate.open()
        assertTrue(gate.tryEnterWrite())
        gate.close()
        val drained = AtomicReference<Boolean?>()

        val waiter = thread(isDaemon = true) { drained.set(gate.awaitDrained(timeoutMs)) }
        awaitParked(waiter, parkedState)
        assertNull(drained.get())

        gate.exitWrite()
        waiter.join(TimeUnit.SECONDS.toMillis(5))

        assertFalse(waiter.isAlive)
        assertEquals(true, drained.get())
    }

    private fun awaitParked(waiter: Thread, state: Thread.State) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (waiter.state != state) {
            check(System.nanoTime() < deadline) { "drain waiter never parked, state=${waiter.state}" }
            Thread.onSpinWait()
        }
    }
}
