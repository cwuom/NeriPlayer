package moe.ouom.neriplayer.data.traffic

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficByteAccumulatorTest {

    @Test
    fun `nonpositive additions and repeated empty flushes are ignored`() {
        val flushed = mutableListOf<Long>()
        val accumulator = TrafficByteAccumulator(10L, flushed::add)
        accumulator.add(0L)
        accumulator.add(-1L)
        accumulator.flush()
        accumulator.flush()

        assertTrue(flushed.isEmpty())
    }

    @Test
    fun `reaching the threshold flushes accumulated bytes and retains the next batch`() {
        val flushed = mutableListOf<Long>()
        val accumulator = TrafficByteAccumulator(10L, flushed::add)
        accumulator.add(4L)
        assertTrue(flushed.isEmpty())
        accumulator.add(6L)
        assertEquals(listOf(10L), flushed)
        accumulator.add(3L)
        accumulator.flush()
        accumulator.flush()

        assertEquals(listOf(10L, 3L), flushed)
    }

    @Test
    fun `overflow saturates the flushed batch and the next batch starts at zero`() {
        val flushed = mutableListOf<Long>()
        val accumulator = TrafficByteAccumulator(Long.MAX_VALUE, flushed::add)
        accumulator.add(Long.MAX_VALUE - 1L)
        assertTrue(flushed.isEmpty())
        accumulator.add(10L)
        accumulator.add(7L)
        accumulator.flush()

        assertEquals(listOf(Long.MAX_VALUE, 7L), flushed)
    }

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
