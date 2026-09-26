package moe.ouom.neriplayer.core.download.resource

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadTransferWatchdogTest {
    @Test
    fun `stalled transfer becomes a retryable error without leaking permit`() = runBlocking {
        var nowNs = 0L
        val registry = DownloadTransferPermitRegistry(
            maxParallelism = 1,
            nowNs = { nowNs }
        )
        val permit = registry.acquire("stalled")
        permit.markNetworkIoStarted()
        val watchdog = DownloadTransferWatchdog(
            registry = registry,
            pollIntervalMs = 1L,
            staleAfterNs = 10L,
            nowNs = { nowNs }
        )
        val failure = try {
            withTimeout(1_000L) {
                val transfer = async {
                    watchdog.run(permit) {
                        delay(500L)
                    }
                }
                yield()
                nowNs = 11L
                transfer.await()
            }
            throw AssertionError("expected DownloadTransferStalledException")
        } catch (error: DownloadTransferStalledException) {
            error
        }
        assertEquals("stalled", permit.ownerKey)
        assertEquals(1, registry.snapshot().permitCount)
        permit.release()
        assertEquals(0, registry.snapshot().permitCount)
        assertEquals(true, failure.message?.contains("no progress"))
    }

    @Test
    fun `progress keeps a transfer alive`() = runBlocking {
        var nowNs = 0L
        val registry = DownloadTransferPermitRegistry(
            maxParallelism = 1,
            nowNs = { nowNs }
        )
        val permit = registry.acquire("progressing")
        permit.markNetworkIoStarted()
        val watchdog = DownloadTransferWatchdog(
            registry = registry,
            pollIntervalMs = 1L,
            staleAfterNs = 10L,
            nowNs = { nowNs }
        )
        val result = withTimeout(1_000L) {
            val transfer = async {
                watchdog.run(permit) { "done" }
            }
            yield()
            nowNs = 11L
            permit.recordProgress(1L)
            transfer.await()
        }
        assertEquals("done", result)
        permit.release()
    }
}
