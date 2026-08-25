package moe.ouom.neriplayer.core.download.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `degraded complete can reenter metadata finalization`() {
        assertEquals(
            "ASSETS_ENRICHING",
            resolveDownloadOperationState("DEGRADED_COMPLETE", "ASSETS_ENRICHING")
        )
        assertEquals(
            "FINALIZED",
            resolveDownloadOperationState("DEGRADED_COMPLETE", "FINALIZED")
        )
    }

    @Test
    fun `unsupported metadata state stops automatic recovery but allows explicit finalization`() {
        assertEquals(
            METADATA_ACTION_REQUIRED_OPERATION_STATE,
            resolveDownloadOperationState(
                "DEGRADED_COMPLETE",
                METADATA_ACTION_REQUIRED_OPERATION_STATE
            )
        )
        assertFalse(
            METADATA_ACTION_REQUIRED_OPERATION_STATE in INTERRUPTED_DOWNLOAD_OPERATION_STATES
        )
        assertFalse(
            METADATA_ACTION_REQUIRED_OPERATION_STATE in
                DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
        )
        assertFalse(
            METADATA_ACTION_REQUIRED_OPERATION_STATE in
                DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES
        )
        assertFalse(canScheduleDownloadOperation(METADATA_ACTION_REQUIRED_OPERATION_STATE))
        assertFalse(shouldHandleHostStop(METADATA_ACTION_REQUIRED_OPERATION_STATE))
        assertNull(
            resolveDownloadOperationState(
                METADATA_ACTION_REQUIRED_OPERATION_STATE,
                "RETRYABLE"
            )
        )
        assertEquals(
            "ASSETS_ENRICHING",
            resolveDownloadOperationState(
                METADATA_ACTION_REQUIRED_OPERATION_STATE,
                "ASSETS_ENRICHING"
            )
        )
        assertEquals(
            "FINALIZED",
            resolveDownloadOperationState(
                METADATA_ACTION_REQUIRED_OPERATION_STATE,
                "FINALIZED"
            )
        )
    }
}
