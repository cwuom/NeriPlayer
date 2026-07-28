package moe.ouom.neriplayer.core.player.state

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBlockingTest {
    @Test
    fun `blocking io propagates successful result`() {
        assertTrue(blockingIo(timeoutMs = 1_000L) { true })
    }

    @Test
    fun `blocking io timeout cancels interruptible work`() {
        val cancelled = CountDownLatch(1)
        var timedOut = false

        try {
            blockingIo(timeoutMs = 50L) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.countDown()
                }
            }
        } catch (_: TimeoutException) {
            timedOut = true
        }

        assertTrue(timedOut)
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
    }
}
