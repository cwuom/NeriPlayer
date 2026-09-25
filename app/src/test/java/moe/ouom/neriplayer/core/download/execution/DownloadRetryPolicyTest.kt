package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.state.DOWNLOAD_RETRY_MAX_COUNT
import moe.ouom.neriplayer.core.download.execution.state.DOWNLOAD_RETRY_MAX_DELAY_MS
import moe.ouom.neriplayer.core.download.execution.state.DownloadRetryPlan
import moe.ouom.neriplayer.core.download.execution.state.planDownloadRetry
import moe.ouom.neriplayer.core.download.execution.state.isAutomaticDownloadRetryExhausted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                    retryCount = 2,
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
    fun `deterministic failures stop after bounded attempts while network waits preserve budget`() {
        listOf("DOWNLOAD_INTEGRITY_DURATION_MISMATCH", "DOWNLOAD_INTEGRITY_CHECKSUM_MISMATCH",
            "DOWNLOAD_SOURCE_MISSING", "CORE_AUDIO_IDENTITY_MISMATCH",
            "CORE_AUDIO_DURATION_MISMATCH", "CORE_AUDIO_MISSING_CONFIRMED").forEach { code ->
            assertFalse(isAutomaticDownloadRetryExhausted(code, 2))
            assertTrue(isAutomaticDownloadRetryExhausted(code, 3))
            assertTrue(isAutomaticDownloadRetryExhausted(code, 31))
        }
        assertTrue(isAutomaticDownloadRetryExhausted("DOWNLOAD_FAILED", 6))
        assertFalse(isAutomaticDownloadRetryExhausted("DOWNLOAD_NO_PROGRESS", 5))
        assertTrue(isAutomaticDownloadRetryExhausted("DOWNLOAD_NO_PROGRESS", 6))
        assertFalse(isAutomaticDownloadRetryExhausted("DOWNLOAD_STORAGE_UNAVAILABLE", 5))
        assertTrue(isAutomaticDownloadRetryExhausted("DOWNLOAD_STORAGE_UNAVAILABLE", 6))
        assertFalse(isAutomaticDownloadRetryExhausted("DOWNLOAD_HOST_FAILURE:IOException", 5))
        assertTrue(isAutomaticDownloadRetryExhausted("DOWNLOAD_HOST_FAILURE:IOException", 6))
        assertTrue(isAutomaticDownloadRetryExhausted("DOWNLOAD_TRANSIENT_FAILURE", 8))
        listOf("NETWORK_UNAVAILABLE", "NETWORK_POLICY_WAITING", "HOST_ADMISSION_FULL").forEach { code ->
            assertFalse(isAutomaticDownloadRetryExhausted(code, 31))
            assertEquals(2, planDownloadRetry(2, code, 100L).retryCount)
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
