package moe.ouom.neriplayer.core.download.execution.state

import moe.ouom.neriplayer.data.model.download.execution.DownloadOperationState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DownloadOperationTransitionMatrixTest(
    private val current: String,
    private val allowed: Set<String>
) {
    @Test
    fun `persistent transition matrix retains cancellation and publication boundaries`() {
        DownloadOperationState.entries.filterNot { it == DownloadOperationState.UNKNOWN }
            .forEach { requested ->
                val expected = requested.wireName.takeIf { it == current || it in allowed }
                assertEquals(
                    "$current -> ${requested.wireName}", expected,
                    DownloadOperationStateTransitions.resolve(current, requested.wireName)
                )
            }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun states(): List<Array<Any>> = listOf(
            row("PENDING_QUEUE", "QUEUED RUNNING COMMITTING ASSETS_ENRICHING FINALIZED DEGRADED_COMPLETE COMPLETED STOPPED RETRYABLE INVALID WAITING_STORAGE_MUTATION WAITING_HOST WAITING_DELETE_CLEANUP"),
            row("QUEUED", "PENDING_QUEUE RUNNING COMMITTING ASSETS_ENRICHING FINALIZED DEGRADED_COMPLETE COMPLETED CANCEL_REQUESTED CANCELLED STOPPED RETRYABLE INVALID WAITING_STORAGE_MUTATION WAITING_HOST WAITING_DELETE_CLEANUP"),
            row("RUNNING", "PENDING_QUEUE QUEUED COMMITTING ASSETS_ENRICHING FINALIZED DEGRADED_COMPLETE COMPLETED CANCEL_REQUESTED CANCELLED STOPPED RETRYABLE INVALID WAITING_STORAGE_MUTATION WAITING_HOST WAITING_DELETE_CLEANUP"),
            row("COMMITTING", "RUNNING CORE_COMMITTED COMPLETED RETRYABLE INVALID"),
            row("CORE_COMMITTED", "ASSETS_ENRICHING FINALIZED DEGRADED_COMPLETE COMPLETED"),
            row("ASSETS_ENRICHING", "FINALIZED DEGRADED_COMPLETE COMPLETED"),
            row("FINALIZED", "DEGRADED_COMPLETE COMPLETED"),
            row("DEGRADED_COMPLETE", "ASSETS_ENRICHING FINALIZED COMPLETED METADATA_ACTION_REQUIRED"),
            row("COMPLETED", ""),
            row("CANCEL_REQUESTED", "CANCELLED ASSETS_ENRICHING FINALIZED DEGRADED_COMPLETE"),
            row("CANCELLED", ""),
            row("STOPPED", "CANCEL_REQUESTED CANCELLED INVALID"),
            row("RETRYABLE", "PENDING_QUEUE QUEUED RUNNING ASSETS_ENRICHING FINALIZED DEGRADED_COMPLETE COMPLETED CANCEL_REQUESTED CANCELLED STOPPED INVALID WAITING_STORAGE_MUTATION WAITING_HOST WAITING_DELETE_CLEANUP"),
            row("INVALID", ""),
            row("WAITING_STORAGE_MUTATION", "PENDING_QUEUE QUEUED RUNNING RETRYABLE CANCEL_REQUESTED CANCELLED INVALID"),
            row("WAITING_HOST", ""),
            row("WAITING_DELETE_CLEANUP", ""),
            row("METADATA_ACTION_REQUIRED", "ASSETS_ENRICHING FINALIZED")
        )

        private fun row(current: String, allowed: String): Array<Any> =
            arrayOf(current, allowed.split(' ').filter(String::isNotEmpty).toSet())
    }
}
