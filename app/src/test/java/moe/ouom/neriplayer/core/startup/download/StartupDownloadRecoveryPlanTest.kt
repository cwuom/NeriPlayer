package moe.ouom.neriplayer.core.startup.download

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupDownloadRecoveryPlanTest {
    @Test
    fun `default attempts preserve startup retry cadence and reasons`() {
        assertEquals(
            listOf(
                StartupDownloadRecoveryAttempt(300L, "activity_main_ready"),
                StartupDownloadRecoveryAttempt(1_200L, "activity_main_ready_retry_1"),
                StartupDownloadRecoveryAttempt(2_500L, "activity_main_ready_retry_2")
            ),
            StartupDownloadRecoveryPlan.defaultAttempts()
        )
    }
}
