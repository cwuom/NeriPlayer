package moe.ouom.neriplayer.core.startup.download

internal data class StartupDownloadRecoveryAttempt(
    val delayMs: Long,
    val reason: String
)

internal object StartupDownloadRecoveryPlan {
    private val RECOVERY_DELAYS_MS = listOf(300L, 1_200L, 2_500L)

    fun defaultAttempts(): List<StartupDownloadRecoveryAttempt> {
        return RECOVERY_DELAYS_MS.mapIndexed { index, delayMs ->
            StartupDownloadRecoveryAttempt(
                delayMs = delayMs,
                reason = if (index == 0) {
                    "activity_main_ready"
                } else {
                    "activity_main_ready_retry_$index"
                }
            )
        }
    }
}
