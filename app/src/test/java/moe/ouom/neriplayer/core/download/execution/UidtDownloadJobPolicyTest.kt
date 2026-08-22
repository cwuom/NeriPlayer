package moe.ouom.neriplayer.core.download.execution

import android.app.job.JobParameters
import android.os.Build
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
}
