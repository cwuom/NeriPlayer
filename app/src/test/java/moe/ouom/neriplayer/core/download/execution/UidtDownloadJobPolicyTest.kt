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
    fun `user requested process exit reasons are recognized`() {
        assertTrue(isUserRequestedProcessExitReason(ApplicationExitInfo.REASON_USER_REQUESTED))
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
}
