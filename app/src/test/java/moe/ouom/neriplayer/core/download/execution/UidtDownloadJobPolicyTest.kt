package moe.ouom.neriplayer.core.download.execution

import android.app.job.JobParameters
import android.os.Build
import android.app.ApplicationExitInfo
import moe.ouom.neriplayer.core.download.policy.shouldRequireExplicitResume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UidtDownloadJobPolicyTest {
    @Test
    fun `user stop is not automatically rescheduled`() {
        assertFalse(
            shouldRescheduleUidtJob(
                stopReason = JobParameters.STOP_REASON_USER,
                sdkInt = Build.VERSION_CODES.S
            )
        )
    }

    @Test
    fun `system stop remains retryable`() {
        assertTrue(
            shouldRescheduleUidtJob(
                stopReason = JobParameters.STOP_REASON_QUOTA,
                sdkInt = Build.VERSION_CODES.S
            )
        )
        assertTrue(
            shouldRescheduleUidtJob(
                stopReason = -1,
                sdkInt = Build.VERSION_CODES.R
            )
        )
    }

    @Test
    fun `app cancellation is not rescheduled`() {
        assertFalse(
            shouldRescheduleUidtJob(
                stopReason = JobParameters.STOP_REASON_CANCELLED_BY_APP,
                sdkInt = Build.VERSION_CODES.S
            )
        )
    }

    @Test
    fun `app cancellation does not become a user stop marker`() {
        assertFalse(
            shouldMarkUidtJobStopped(
                stopReason = JobParameters.STOP_REASON_CANCELLED_BY_APP,
                sdkInt = Build.VERSION_CODES.S
            )
        )
        assertTrue(
            shouldMarkUidtJobStopped(
                stopReason = JobParameters.STOP_REASON_USER,
                sdkInt = Build.VERSION_CODES.S
            )
        )
    }

    @Test
    fun `system stop keeps both UIDT and worker recovery paths alive`() {
        assertEquals(
            UidtStopAction.RETRY_WITHOUT_CANCELLING_BACKENDS,
            resolveUidtStopAction(
                stopReason = JobParameters.STOP_REASON_QUOTA,
                sdkInt = Build.VERSION_CODES.S
            )
        )
        assertEquals(
            UidtStopAction.STOP_AND_CANCEL_BACKENDS,
            resolveUidtStopAction(
                stopReason = JobParameters.STOP_REASON_USER,
                sdkInt = Build.VERSION_CODES.S
            )
        )
        assertEquals(
            UidtStopAction.STOP_AND_CANCEL_BACKENDS,
            resolveUidtStopAction(
                stopReason = JobParameters.STOP_REASON_CANCELLED_BY_APP,
                sdkInt = Build.VERSION_CODES.S
            )
        )
    }

    @Test
    fun `removing app from recents remains recoverable`() {
        assertFalse(isUserRequestedProcessExitReason(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertTrue(isUserRequestedProcessExitReason(ApplicationExitInfo.REASON_USER_STOPPED))
        assertFalse(isUserRequestedProcessExitReason(ApplicationExitInfo.REASON_CRASH))
    }

    @Test
    fun `explicit user stop wins even when a UIDT job is still pending`() {
        assertTrue(
            shouldRequireExplicitResume(
                userInitiated = true,
                state = "RUNNING",
                hasPendingUidtJob = true,
                stopRequestedByUser = true
            )
        )
    }

    @Test
    fun `explicit cancellation never reappears as a resume task`() {
        assertFalse(
            shouldRequireExplicitResume(
                userInitiated = true,
                state = "CORE_COMMITTED",
                hasPendingUidtJob = false,
                stopRequestedByUser = true,
                cancellationRequestedByUser = true
            )
        )
    }

    @Test
    fun `metadata action required does not reschedule UIDT`() {
        assertFalse(shouldRescheduleUidtExecution(DownloadExecutionResult.UserActionRequired))
        assertFalse(shouldRescheduleUidtExecution(DownloadExecutionResult.NetworkPolicyWaiting))
        assertTrue(shouldRescheduleUidtExecution(DownloadExecutionResult.Retry))
        assertTrue(
            shouldRescheduleUidtExecution(
                DownloadExecutionResult.Failed(IllegalStateException("retry"))
            )
        )
    }

    @Test
    fun `UIDT scheduling failure is reported when the shared pump is unavailable`() {
        assertFalse(
            scheduleUidtWithSharedPump(
                scheduleUidt = { true },
                scheduleSharedPump = { false }
            )
        )
    }

    @Test
    fun `terminal UIDT results retire a pending fallback`() {
        val terminalResults = listOf(
            DownloadExecutionResult.Accepted,
            DownloadExecutionResult.AlreadyHandled,
            DownloadExecutionResult.MissingOperation,
            DownloadExecutionResult.Cancelled,
            DownloadExecutionResult.UserStopped,
            DownloadExecutionResult.UserActionRequired,
            DownloadExecutionResult.NetworkPolicyWaiting
        )

        terminalResults.forEach { result ->
            assertTrue(
                "terminal result must cancel stale fallback: $result",
                shouldCancelUidtFallback(
                    result = result,
                    fallbackExecuting = false
                )
            )
        }
    }

    @Test
    fun `UIDT does not cancel a fallback that still owns execution`() {
        assertFalse(
            shouldCancelUidtFallback(
                result = DownloadExecutionResult.AlreadyHandled,
                fallbackExecuting = true
            )
        )
    }

    @Test
    fun `retryable UIDT results retain the fallback recovery path`() {
        assertFalse(
            shouldCancelUidtFallback(
                result = DownloadExecutionResult.Retry,
                fallbackExecuting = false
            )
        )
        assertFalse(
            shouldCancelUidtFallback(
                result = DownloadExecutionResult.Failed(IllegalStateException("retry")),
                fallbackExecuting = false
            )
        )
    }

    @Test
    fun `pending UIDT yields to its shared pump only during the grace window`() {
        val scheduledAtElapsedMs = 10_000L

        assertTrue(
            shouldYieldToPendingUidt(
                scheduledAtElapsedMs = scheduledAtElapsedMs,
                nowElapsedMs = scheduledAtElapsedMs + UIDT_SHARED_PUMP_GRACE_MS - 1L
            )
        )
        assertFalse(
            shouldYieldToPendingUidt(
                scheduledAtElapsedMs = scheduledAtElapsedMs,
                nowElapsedMs = scheduledAtElapsedMs + UIDT_SHARED_PUMP_GRACE_MS
            )
        )
        assertFalse(
            shouldYieldToPendingUidt(
                scheduledAtElapsedMs = 0L,
                nowElapsedMs = scheduledAtElapsedMs
            )
        )
        assertFalse(
            shouldYieldToPendingUidt(
                scheduledAtElapsedMs = scheduledAtElapsedMs,
                nowElapsedMs = scheduledAtElapsedMs - 1L
            )
        )
    }

    @Test
    fun `pending UIDT grace delay is bounded by the remaining window`() {
        val scheduledAtElapsedMs = 10_000L

        assertEquals(
            1L,
            pendingUidtGraceRemainingMs(
                scheduledAtElapsedMs = scheduledAtElapsedMs,
                nowElapsedMs = scheduledAtElapsedMs + UIDT_SHARED_PUMP_GRACE_MS - 1L
            )
        )
        assertEquals(
            0L,
            pendingUidtGraceRemainingMs(
                scheduledAtElapsedMs = scheduledAtElapsedMs,
                nowElapsedMs = scheduledAtElapsedMs + UIDT_SHARED_PUMP_GRACE_MS
            )
        )
        assertEquals(
            0L,
            pendingUidtGraceRemainingMs(
                scheduledAtElapsedMs = scheduledAtElapsedMs,
                nowElapsedMs = scheduledAtElapsedMs - 1L
            )
        )
    }

    @Test
    fun `process exit recovery requeues only pre commit states`() {
        listOf("PENDING_QUEUE", "QUEUED", "RUNNING", "RETRYABLE").forEach { state ->
            assertEquals("RETRYABLE", resolveProcessExitRecoveryState(state))
        }
        listOf(
            "COMMITTING",
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "DEGRADED_COMPLETE",
            "STOPPED",
            "CANCELLED"
        ).forEach { state ->
            assertEquals(null, resolveProcessExitRecoveryState(state))
        }
    }
}
