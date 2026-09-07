package moe.ouom.neriplayer.core.download.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRetryPolicyTest {
    @Test
    fun `retry delay grows exponentially and caps`() {
        assertEquals(
            DownloadRetryPlan(
                retryCount = 1,
                nextRetryAtMs = 1_001L
            ),
            planDownloadRetry(
                currentRetryCount = 0,
                errorCode = "IO_FAILURE",
                nowMs = 1L
            )
        )
        assertEquals(
            DownloadRetryPlan(
                retryCount = 2,
                nextRetryAtMs = 2_002L
            ),
            planDownloadRetry(
                currentRetryCount = 1,
                errorCode = "IO_FAILURE",
                nowMs = 2L
            )
        )
        val capped = planDownloadRetry(
            currentRetryCount = Int.MAX_VALUE,
            errorCode = "IO_FAILURE",
            nowMs = 10L
        )
        assertEquals(DOWNLOAD_RETRY_MAX_COUNT, capped.retryCount)
        assertEquals(
            10L + DOWNLOAD_RETRY_MAX_DELAY_MS,
            capped.nextRetryAtMs
        )
    }

    @Test
    fun `event driven retry reasons do not add a second deadline`() {
        listOf(
            "NETWORK_POLICY_WAITING",
            "CANCELLATION_SETTLEMENT_PENDING"
        ).forEach { errorCode ->
            assertEquals(
                DownloadRetryPlan(
                    retryCount = 3,
                    nextRetryAtMs = null
                ),
                planDownloadRetry(
                    currentRetryCount = 2,
                    errorCode = errorCode,
                    nowMs = 100L
                )
            )
        }
    }

    @Test
    fun `deadline saturates when wall clock is near long max`() {
        val plan = planDownloadRetry(
            currentRetryCount = 0,
            errorCode = "IO_FAILURE",
            nowMs = Long.MAX_VALUE
        )
        assertEquals(1, plan.retryCount)
        assertEquals(Long.MAX_VALUE, plan.nextRetryAtMs)
        assertNull(
            planDownloadRetry(
                currentRetryCount = 0,
                errorCode = "NETWORK_POLICY_WAITING",
                nowMs = Long.MAX_VALUE
            ).nextRetryAtMs
        )
    }
}
