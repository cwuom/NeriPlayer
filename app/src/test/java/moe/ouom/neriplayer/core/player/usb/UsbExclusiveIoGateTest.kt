package moe.ouom.neriplayer.core.player.usb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveIoGateTest {

    @Test
    fun `closing gate rejects new writes`() {
        val gate = UsbExclusiveIoGate()
        gate.open()
        gate.close()

        assertFalse(gate.tryEnterWrite())
        assertTrue(gate.awaitDrained(timeoutMs = 10L))
    }

    @Test
    fun `drain waits for writer that already entered`() {
        val gate = UsbExclusiveIoGate()
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        gate.open()

        val writer = thread(start = true) {
            assertTrue(gate.tryEnterWrite())
            writerEntered.countDown()
            releaseWriter.await(1, TimeUnit.SECONDS)
            gate.exitWrite()
        }

        assertTrue(writerEntered.await(1, TimeUnit.SECONDS))
        gate.close()
        assertFalse(gate.awaitDrained(timeoutMs = 20L))
        releaseWriter.countDown()
        writer.join(1_000L)
        assertTrue(gate.awaitDrained(timeoutMs = 100L))
    }

    @Test
    fun `repeated close remains idempotent`() {
        val gate = UsbExclusiveIoGate()
        gate.open()

        gate.close()
        gate.close()

        assertFalse(gate.isOpen())
        assertTrue(gate.awaitDrained(timeoutMs = 10L))
    }
}
