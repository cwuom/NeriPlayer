package moe.ouom.neriplayer.data.traffic

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficByteAccumulatorTest {

    @Test
    fun `concurrent additions are retained before final flush`() {
        val flushedBytes = AtomicLong()
        val accumulator = TrafficByteAccumulator(Long.MAX_VALUE) { bytes ->
            flushedBytes.addAndGet(bytes)
        }
        val executor = Executors.newFixedThreadPool(8)
        repeat(8) {
            executor.submit {
                repeat(10_000) {
                    accumulator.add(1L)
                }
            }
        }
        executor.shutdown()

        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        accumulator.flush()

        assertEquals(80_000L, flushedBytes.get())
    }
}
