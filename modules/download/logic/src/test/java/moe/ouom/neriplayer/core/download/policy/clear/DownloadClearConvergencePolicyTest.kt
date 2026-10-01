package moe.ouom.neriplayer.core.download.policy.clear

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadClearConvergencePolicyTest {
    @Test
    fun `successful nullable Room result is not a timeout`() = runTest {
        assertEquals(null, withDownloadClearRoomTimeout<String?>("lookup") { null })
        assertEquals(42, withDownloadClearRoomTimeout("count") { 42 })
    }

    @Test
    fun `Room timeout identifies the failed operation`() = runTest {
        val error = runCatching {
            withDownloadClearRoomTimeout("purge", timeoutMs = 20L) {
                delay(21L)
            }
        }.exceptionOrNull()

        assertTrue(error is DownloadClearRoomTimeoutException)
        assertTrue(requireNotNull(error).message.orEmpty().contains("operation=purge"))
        assertTrue(error.message.orEmpty().contains("timeoutMs=20"))
    }

    @Test
    fun `Room failures and caller cancellation retain their type and original cause`() = runTest {
        val failure = IllegalStateException("busy")
        val cancellation = CancellationException("host stopped")
        for (error in listOf(failure, cancellation)) {
            val actual = requireNotNull(runCatching {
                withDownloadClearRoomTimeout("query") { throw error }
            }.exceptionOrNull())
            assertEquals(error.javaClass, actual.javaClass)
            assertEquals(error.message, actual.message)
            assertTrue(generateSequence(actual) { it.cause }.any { it === error })
        }
    }

    @Test
    fun `invalid Room timeout arguments fail before invoking the operation`() = runTest {
        var invoked = false
        assertTrue(runCatching {
            withDownloadClearRoomTimeout(" ") { invoked = true }
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            withDownloadClearRoomTimeout("query", timeoutMs = 0L) { invoked = true }
        }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(invoked)
    }

    @Test
    fun `convergence and durable retries defer at the configured round budget`() {
        assertFalse(shouldDeferDownloadClearAfterConvergenceRound(5))
        assertTrue(shouldDeferDownloadClearAfterConvergenceRound(6))
        assertTrue(shouldDeferDownloadClearAfterConvergenceRound(7))
        assertFalse(shouldDeferDownloadClearAfterDurableRetry(5))
        assertTrue(shouldDeferDownloadClearAfterDurableRetry(6))
        assertTrue(shouldDeferDownloadClearAfterDurableRetry(7))
        assertThrows(IllegalArgumentException::class.java) {
            shouldDeferDownloadClearAfterConvergenceRound(1, maxRounds = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            shouldDeferDownloadClearAfterDurableRetry(1, maxRounds = 0)
        }
    }

    @Test
    fun `clear deadline ignores missing timestamps and backwards clock movement`() {
        assertFalse(hasDownloadClearExceededDeadline(null, 9_000L))
        assertFalse(hasDownloadClearExceededDeadline(0L, 9_000L))
        assertFalse(hasDownloadClearExceededDeadline(-1L, 9_000L))
        assertFalse(hasDownloadClearExceededDeadline(10L, 9L))
        assertFalse(hasDownloadClearExceededDeadline(10L, 3_009L))
        assertTrue(hasDownloadClearExceededDeadline(10L, 3_010L))
        assertTrue(hasDownloadClearExceededDeadline(10L, 3_011L))
        assertFalse(hasDownloadClearExceededDeadline(1L, Long.MAX_VALUE, Long.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) {
            hasDownloadClearExceededDeadline(10L, 20L, deadlineMs = 0L)
        }
    }
}
