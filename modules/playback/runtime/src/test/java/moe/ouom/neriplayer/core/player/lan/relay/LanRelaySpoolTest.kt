package moe.ouom.neriplayer.core.player.lan.relay

import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LanRelaySpoolTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun cachedReadsContinueWhileAnotherListenerWaitsForUpstream() {
        val extending = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val input = object : FilterInputStream(ByteArray(96 * 1024) { 7 }.inputStream()) {
            var count = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (count > 0) {
                    extending.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                return super.read(b, off, len).also { count += it }
            }
        }
        LanRelaySpool(temp.root, input, null) { Long.MAX_VALUE }.use { spool ->
            try {
                assertEquals(7, spool.cursor(0).read())
                val ahead = pool.submit<Int> { spool.cursor(80 * 1024L).read() }
                assertTrue(extending.await(5, TimeUnit.SECONDS))
                assertEquals(7, pool.submit<Int> { spool.cursor(9).read() }.get(2, TimeUnit.SECONDS))
                resume.countDown()
                assertEquals(7, ahead.get(5, TimeUnit.SECONDS))
            } finally {
                resume.countDown()
                pool.shutdownNow()
            }
        }
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun validatorsCannotBeSimulatedByJoiningAnIncompleteResponseToAnother() {
        val bytes = byteArrayOf(1, 2, 3)
        LanRelaySpool(temp.root, bytes.inputStream(), 4) { Long.MAX_VALUE }.use { spool ->
            assertThrows(EOFException::class.java) { spool.cursor(3).read() }
            assertThrows(EOFException::class.java) { spool.cursor(3).read() }
            assertEquals(2, spool.cursor(1).read())
        }
    }

    @Test fun aSeekBeyondFreeSpaceLeavesSequentialPlaybackReadableAndDoesNotDownloadTheGap() {
        val source = byteArrayOf(9, 8, 7).inputStream()
        val trackBytes = 8L * 1024 * 1024 * 1024
        LanRelaySpool(temp.root, source, trackBytes) { 18L * 1024 * 1024 }.use { spool ->
            assertEquals(trackBytes, spool.knownLength)
            val failure = assertThrows(LanSpoolSeekOutOfSpace::class.java) { spool.cursor(trackBytes - 1).read() }
            assertEquals("LOCAL_STORAGE", failure.message)
            assertEquals(3, source.available())
            assertEquals(9, spool.cursor(0).read())
        }
    }

    @Test fun sequentialReadsKeepTheReserveWhenTheVolumeFills() {
        val source = byteArrayOf(9, 8, 7).inputStream()
        var freeBytes = 16L * 1024 * 1024 + 2
        LanRelaySpool(temp.root, source, null) { freeBytes }.use { spool ->
            val cursor = spool.cursor(0)
            assertEquals(9, cursor.read())
            freeBytes -= 2
            assertEquals(8, cursor.read())
            val failure = assertThrows(IOException::class.java) { cursor.read() }
            assertEquals("LOCAL_STORAGE", failure.message)
            assertEquals(1, source.available())
            assertEquals(9, spool.cursor(0).read())
        }
    }

    @Test fun cancellingADistantSeekStopsFillingAfterTheCurrentReadAndKeepsEarlierBytes() {
        val chunk = 65536
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val bytes = ByteArray(6 * chunk) { 5 }.inputStream()
        val input = object : FilterInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (bytes.available() == 5 * chunk) {
                    started.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                return super.read(b, off, len)
            }
        }
        LanRelaySpool(temp.root, input, null) { Long.MAX_VALUE }.use { spool ->
            try {
                val far = spool.cursor(5L * chunk)
                val pending = pool.submit<Int> { far.read() }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                far.close()
                resume.countDown()
                val error = assertThrows(ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }
                assertTrue(error.cause is LanSpoolReadCancelled)
                assertEquals(4 * chunk, bytes.available())
                assertEquals(5, spool.cursor(0).read())
            } finally {
                resume.countDown()
                pool.shutdownNow()
            }
        }
    }
}
