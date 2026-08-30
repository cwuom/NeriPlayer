package moe.ouom.neriplayer.core.download

/** 限制单次进程内清空尝试，持久栅栏交给启动或显式重试继续收敛 */
internal const val DOWNLOAD_CLEAR_MAX_CONVERGENCE_ROUNDS = 6

/** 限制清空阶段的持久化重试次数，超过后交给下一轮恢复 */
internal const val DOWNLOAD_CLEAR_MAX_DURABLE_RETRY_ROUNDS = 6

internal fun shouldDeferDownloadClearAfterConvergenceRound(
    round: Int,
    maxRounds: Int = DOWNLOAD_CLEAR_MAX_CONVERGENCE_ROUNDS
): Boolean {
    require(maxRounds > 0) { "maxRounds must be positive" }
    return round >= maxRounds
}

internal fun shouldDeferDownloadClearAfterDurableRetry(
    round: Int,
    maxRounds: Int = DOWNLOAD_CLEAR_MAX_DURABLE_RETRY_ROUNDS
): Boolean {
    require(maxRounds > 0) { "maxRounds must be positive" }
    return round >= maxRounds
}
