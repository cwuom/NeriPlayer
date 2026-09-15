package moe.ouom.neriplayer.core.download.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadOperationStateTest {
    @Test
    fun `wire names round trip through the typed state`() {
        DownloadOperationState.entries
            .filterNot { it == DownloadOperationState.UNKNOWN }
            .forEach { state ->
                assertEquals(state, DownloadOperationState.parse(state.wireName))
            }
        assertEquals(DownloadOperationState.UNKNOWN, DownloadOperationState.parse("future"))
    }

    @Test
    fun `terminal state cannot be downgraded`() {
        listOf("CANCELLED", "COMPLETED").forEach { terminal ->
            assertNull(resolveDownloadOperationState(terminal, "RETRYABLE"))
            assertNull(resolveDownloadOperationState(terminal, "CANCEL_REQUESTED"))
        }
    }

    @Test
    fun `core commit transition is monotonic`() {
        assertEquals(
            "CORE_COMMITTED",
            resolveDownloadOperationState("COMMITTING", "CORE_COMMITTED")
        )
        assertEquals(
            "FINALIZED",
            resolveDownloadOperationState("DEGRADED_COMPLETE", "FINALIZED")
        )
        assertEquals(
            "DEGRADED_COMPLETE",
            resolveDownloadOperationState("ASSETS_ENRICHING", "DEGRADED_COMPLETE")
        )
        assertNull(resolveDownloadOperationState("CORE_COMMITTED", "RETRYABLE"))
        assertNull(
            resolveDownloadOperationState(
                "CORE_COMMITTED",
                "WAITING_STORAGE_MUTATION"
            )
        )
        assertNull(
            resolveDownloadOperationState(
                "COMMITTING",
                "WAITING_STORAGE_MUTATION"
            )
        )
    }

    @Test
    fun `interrupted state set comes from the typed model`() {
        assertTrue("RUNNING" in INTERRUPTED_DOWNLOAD_OPERATION_STATES)
        assertTrue("DEGRADED_COMPLETE" in INTERRUPTED_DOWNLOAD_OPERATION_STATES)
        assertEquals(
            setOf("CORE_COMMITTED", "ASSETS_ENRICHING", "DEGRADED_COMPLETE"),
            DownloadOperationStateTransitions.resumableCoreWireNames
        )
    }

    @Test
    fun `storage waiting only leaves through a recoverable or terminal control path`() {
        assertEquals(
            "QUEUED",
            resolveDownloadOperationState("WAITING_STORAGE_MUTATION", "QUEUED")
        )
        assertEquals(
            "CANCEL_REQUESTED",
            resolveDownloadOperationState("WAITING_STORAGE_MUTATION", "CANCEL_REQUESTED")
        )
        assertNull(
            resolveDownloadOperationState("WAITING_STORAGE_MUTATION", "COMPLETED")
        )
    }

    @Test
    fun `verified cached audio can close every pre-core transfer state`() {
        listOf("PENDING_QUEUE", "QUEUED", "RUNNING", "RETRYABLE").forEach { state ->
            assertEquals(
                "COMPLETED",
                resolveDownloadOperationState(state, "COMPLETED")
            )
        }
    }

    @Test
    fun `host capacity handoff retries without exponential backoff`() {
        listOf("HOST_ADMISSION_FULL", "HOST_TRANSFER_ADMISSION_DEFERRED").forEach { errorCode ->
            assertNull(
                planDownloadRetry(
                    currentRetryCount = 0,
                    errorCode = errorCode,
                    nowMs = 1_000L
                ).nextRetryAtMs
            )
        }
    }
}
