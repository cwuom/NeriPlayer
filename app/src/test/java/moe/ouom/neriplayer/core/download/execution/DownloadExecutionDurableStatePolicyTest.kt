package moe.ouom.neriplayer.core.download.execution

import org.junit.Assert.assertNull
import org.junit.Test

class DownloadExecutionDurableStatePolicyTest {
    @Test
    fun `core operation states reject retry and cancellation transitions`() {
        val durableStates = listOf(
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "FINALIZED",
            "DEGRADED_COMPLETE",
            "COMPLETED"
        )
        val downgradeRequests = listOf("RETRYABLE", "CANCEL_REQUESTED", "CANCELLED")

        durableStates.forEach { current ->
            downgradeRequests.forEach { requested ->
                assertNull(
                    "$current must not be replaced by $requested",
                    resolveDownloadOperationState(current, requested)
                )
            }
        }
    }
}
