package moe.ouom.neriplayer.core.download.storage.tree.query

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedStorageQueryRunnerTest {
    @Test
    fun frozenProviderDoesNotAccumulateQueriesOrPublishItsLateResult() {
        val runner = BoundedStorageQueryRunner<String>(parallelism = 2, timeoutMs = 50L)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            assertNull(runner.query("frozen-root") {
                calls.incrementAndGet()
                release.await()
                finished.countDown()
                "stale snapshot"
            })
            repeat(20) {
                assertNull(runner.query("frozen-root") { calls.incrementAndGet(); "duplicate" })
            }
            assertEquals(1, calls.get())
            assertEquals("healthy snapshot", runner.query("healthy-root") { "healthy snapshot" })
            release.countDown()
            assertTrue(finished.await(2L, TimeUnit.SECONDS))
            var snapshot: String? = null
            repeat(100) {
                if (snapshot == null) {
                    snapshot = runner.query("frozen-root") { "fresh snapshot" }
                    if (snapshot == null) Thread.sleep(5L)
                }
            }
            assertEquals("fresh snapshot", snapshot)
        } finally {
            release.countDown()
            runner.close()
        }
    }

    @Test
    fun exhaustedProviderSlotsHaveNoUnboundedQueue() {
        val runner = BoundedStorageQueryRunner<String>(parallelism = 1, timeoutMs = 50L)
        val release = CountDownLatch(1)
        val executed = AtomicInteger()
        try {
            assertNull(runner.query("blocked") { release.await(); "old" })
            repeat(100) { index ->
                assertNull(runner.query("queued-$index") { executed.incrementAndGet(); "unexpected" })
            }
            assertEquals(0, executed.get())
        } finally {
            release.countDown()
            runner.close()
        }
    }

    @Test
    fun permissionFailureIsNotReportedAsAnEmptyDirectory() {
        BoundedStorageQueryRunner<String>().use { runner ->
            assertThrows(SecurityException::class.java) {
                runner.query("denied") { throw SecurityException("permission revoked") }
            }
            assertEquals("restored", runner.query("denied") { "restored" })
        }
    }
}
