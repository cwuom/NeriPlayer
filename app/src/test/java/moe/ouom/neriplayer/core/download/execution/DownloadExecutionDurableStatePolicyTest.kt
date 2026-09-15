package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.policy.shouldRequireExplicitResume
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadExecutionDurableStatePolicyTest {
    @Test
    fun `post core states are not eligible for destructive host pause`() {
        listOf(
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "FINALIZED",
            "DEGRADED_COMPLETE",
            "COMPLETED",
            METADATA_ACTION_REQUIRED_OPERATION_STATE
        ).forEach { state ->
            assertTrue("expected post-core state: $state", isPostCoreDownloadOperationState(state))
        }
        assertTrue(isPostCoreDownloadOperationState(" completed "))
        assertFalse(isPostCoreDownloadOperationState("RUNNING"))
        assertFalse(isPostCoreDownloadOperationState("CANCELLED"))
    }

    @Test
    fun `shared pump only includes reusable transfer states`() {
        assertEquals(
            DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES,
            DownloadExecutionRoomStore.PUMP_OPERATION_STATES
        )
        assertFalse("RUNNING" in DownloadExecutionRoomStore.PUMP_OPERATION_STATES)
        assertFalse("COMMITTING" in DownloadExecutionRoomStore.PUMP_OPERATION_STATES)
        assertFalse("CORE_COMMITTED" in DownloadExecutionRoomStore.PUMP_OPERATION_STATES)
        assertFalse("ASSETS_ENRICHING" in DownloadExecutionRoomStore.PUMP_OPERATION_STATES)
        assertFalse("DEGRADED_COMPLETE" in DownloadExecutionRoomStore.PUMP_OPERATION_STATES)
    }

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
    fun `legacy completed pending recovery has a dedicated reopen path`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadExecutionRoomStore.kt"
        )
        val text = source.readText()
        val method = moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.functionBody(
            source = text,
            methodName = "reopenCorePublicationRecovery"
        )
        assertTrue(method.contains("header.state !in setOf(\"COMPLETED\", \"FINALIZED\")"))
        assertTrue(method.contains("header.stopRequestedByUser"))
        assertTrue(method.contains("state = \"DEGRADED_COMPLETE\""))
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.resolve(candidate)
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
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

    @Test
    fun `explicit resume remains required while scheduling is pending`() {
        assertTrue(
            shouldRequireExplicitResume(
                userInitiated = true,
                state = "RETRYABLE",
                hasPendingUidtJob = false,
                resumePending = true
            )
        )
        assertFalse(
            shouldRequireExplicitResume(
                userInitiated = true,
                state = "RETRYABLE",
                hasPendingUidtJob = false,
                cancellationRequestedByUser = true,
                resumePending = true
            )
        )
        assertFalse(
            shouldRequireExplicitResume(
                userInitiated = true,
                state = "RUNNING",
                hasPendingUidtJob = false,
                resumePending = false
            )
        )
    }
}
