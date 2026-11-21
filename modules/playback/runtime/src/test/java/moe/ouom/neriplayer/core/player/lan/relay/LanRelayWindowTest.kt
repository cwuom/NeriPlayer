package moe.ouom.neriplayer.core.player.lan.relay

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LanRelayWindowTest {
    private val bytes = ByteArray(350_123) { (it * 13).toByte() }

    @Test fun twoMembersReadingDifferentRangesKeepTheirSequentialUpstreamConnections() {
        val block = 64 * 1024
        val gap = 68 * block
        val source = ByteArray(gap + 11 * block) { ((it / block + it) % 251).toByte() }
        val reopened = mutableListOf<Long>()
        LanRelayWindow(source.size.toLong(), source.inputStream()) { offset ->
            reopened.add(offset)
            ByteArrayInputStream(source, offset.toInt(), source.size - offset.toInt())
        }.use { relay ->
            DataInputStream(relay.open(0, null).stream).use { first ->
                DataInputStream(relay.open(gap.toLong(), null).stream).use { second ->
                    val buffer = ByteArray(block)
                    repeat(10) { index ->
                        val at = index * block
                        first.readFully(buffer)
                        assertArrayEquals(source.copyOfRange(at, at + block), buffer)
                        second.readFully(buffer)
                        assertArrayEquals(source.copyOfRange(gap + at, gap + at + block), buffer)
                    }
                }
            }
            assertEquals(listOf(gap.toLong()), reopened)
        }
    }

    @Test fun repeatedSeeksEvictIdleConnectionsAndRoomCloseReleasesTheRest() {
        val ranges = 12
        val block = 64 * 1024
        val source = ByteArray(2 * ranges * block)
        val closes = CountDownLatch(ranges)
        fun open(offset: Int) = object : ByteArrayInputStream(source, offset, source.size - offset) {
            override fun close() { closes.countDown() }
        }
        LanRelayWindow(source.size.toLong(), open(0)) { at -> open(at.toInt()) }.use { relay ->
            repeat(ranges) { index ->
                relay.open(2L * index * block, 1).stream.use { assertEquals(0, it.read()) }
            }
            assertTrue(closes.count in 1L..4L)
        }
        assertTrue(closes.await(5, TimeUnit.SECONDS))
    }

    @Test fun concurrentMembersShareInitialBytesAndSeekWithoutFinishingTheTrack() {
        val consumed = AtomicInteger()
        val offsets = mutableListOf<Int>()
        fun source(offset: Int) = object : ByteArrayInputStream(bytes, offset, bytes.size - offset) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) consumed.addAndGet(it) }
        }
        LanRelayWindow(bytes.size.toLong(), source(0)) { at ->
            synchronized(offsets) { offsets.add(at.toInt()) }
            source(at.toInt())
        }.use { relay ->
            val pool = Executors.newFixedThreadPool(4)
            try {
                val reads = pool.invokeAll(List(4) { Callable { relay.open(10, 400).stream.use { it.readBytes() } } })
                for (read in reads) assertArrayEquals(bytes.copyOfRange(10, 410), read.get(5, TimeUnit.SECONDS))
                assertEquals(64 * 1024, consumed.get())
                val target = 246_800
                val range = relay.open(target.toLong(), 117)
                assertEquals(117L, range.byteLength)
                assertArrayEquals(bytes.copyOfRange(target, target + 117), range.stream.use { it.readBytes() })
                assertEquals(listOf(3 * 65536), offsets)
                assertTrue(consumed.get() < bytes.size)
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun unknownLengthEndsAtEofAndKnownTruncationIsReported() {
        val relay = LanRelayWindow(null, bytes.inputStream()) { error("unexpected seek") }
        val transfer = relay.open(0, null)
        assertNull(transfer.byteLength)
        assertArrayEquals(bytes, transfer.stream.use { it.readBytes() })
        assertEquals(123L, relay.open(bytes.size - 123L, null).byteLength)
        relay.close()
        assertThrows(IOException::class.java) { relay.open(0, 1).stream.use { it.read() } }
        LanRelayWindow(bytes.size + 1L, bytes.inputStream()) { error("unexpected seek") }.use { bad ->
            assertThrows(EOFException::class.java) { bad.open(0, null).stream.use { it.readBytes() } }
        }
    }

    @Test fun revokedReaderStopsWaitingWithoutInterruptingAnotherReaderOfTheSameBlock() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val raw = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return 42
            }
        }
        val relay = LanRelayWindow(1, raw) { error("unexpected reopen") }
        val pool = Executors.newFixedThreadPool(2)
        val first = relay.open(0, 1).stream
        val second = relay.open(0, 1).stream
        try {
            val stopped = pool.submit(Callable { first.read() })
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            val remaining = pool.submit(Callable { second.read() })
            first.close()
            assertTrue(assertThrows(ExecutionException::class.java) { stopped.get(1, TimeUnit.SECONDS) }.cause is IOException)
            release.countDown()
            assertEquals(42, remaining.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            first.close()
            second.close()
            relay.close()
            pool.shutdownNow()
        }
    }

    @Test fun closingTheRelayCancelsUpstreamBeforeTheWorkerClosesTheSource() {
        val reading = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val released = CountDownLatch(1)
        val inRead = AtomicBoolean()
        val closedDuringRead = AtomicBoolean()
        val raw = object : InputStream() {
            override fun read(): Int {
                inRead.set(true)
                reading.countDown()
                try {
                    check(cancelled.await(5, TimeUnit.SECONDS))
                    throw IOException("cancelled")
                } finally {
                    inRead.set(false)
                }
            }

            override fun close() {
                closedDuringRead.set(inRead.get())
                released.countDown()
            }
        }
        val relay = LanRelayWindow(1, raw, stopUpstream = { cancelled.countDown() }) { error("unexpected reopen") }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val reader = pool.submit(Callable { relay.open(0, 1).stream.use { it.read() } })
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            relay.close()
            assertTrue(assertThrows(ExecutionException::class.java) { reader.get(1, TimeUnit.SECONDS) }.cause is IOException)
            assertTrue(released.await(5, TimeUnit.SECONDS))
            assertFalse(closedDuringRead.get())
        } finally {
            cancelled.countDown()
            relay.close()
            pool.shutdownNow()
        }
    }
}
