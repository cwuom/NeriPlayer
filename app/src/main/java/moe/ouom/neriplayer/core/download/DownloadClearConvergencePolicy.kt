package moe.ouom.neriplayer.core.download

import kotlinx.coroutines.withTimeoutOrNull

/** 限制单次进程内清空尝试，持久栅栏交给启动或显式重试继续收敛 */
internal const val DOWNLOAD_CLEAR_MAX_CONVERGENCE_ROUNDS = 6

/** 限制清空阶段的持久化重试次数，超过后交给下一轮恢复 */
internal const val DOWNLOAD_CLEAR_MAX_DURABLE_RETRY_ROUNDS = 6

/** 清空收敛中的单次 Room 调用上限，超时后保留持久栅栏并稍后重试 */
internal const val DOWNLOAD_CLEAR_ROOM_OPERATION_TIMEOUT_MS = 2_000L

/** 清空任务展示的硬截止时间，超过后物理善后不能继续阻塞播放和新调度 */
internal const val DOWNLOAD_CLEAR_HARD_DEADLINE_MS = 3_000L

internal class DownloadClearRoomTimeoutException(
    operation: String,
    timeoutMs: Long
) : IllegalStateException(
    "download clear Room operation timed out: operation=$operation, " +
        "timeoutMs=$timeoutMs"
)

/**
 * Room busy 时不能让清空协程无限等待
 *
 * 超时通过异常返回给清空收敛循环，由调用方保留 durable fence，不能把
 * 空结果当成查询成功后继续 purge
 */
internal suspend fun <T> withDownloadClearRoomTimeout(
    operation: String,
    timeoutMs: Long = DOWNLOAD_CLEAR_ROOM_OPERATION_TIMEOUT_MS,
    block: suspend () -> T
): T {
    require(operation.isNotBlank()) { "operation must not be blank" }
    require(timeoutMs > 0L) { "timeoutMs must be positive" }
    val result = withTimeoutOrNull(timeoutMs) {
        DownloadClearRoomValue(block())
    } ?: throw DownloadClearRoomTimeoutException(operation, timeoutMs)
    return result.value
}

private data class DownloadClearRoomValue<T>(val value: T)

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

internal fun hasDownloadClearExceededDeadline(
    requestedAtMs: Long?,
    nowMs: Long,
    deadlineMs: Long = DOWNLOAD_CLEAR_HARD_DEADLINE_MS
): Boolean {
    require(deadlineMs > 0L) { "deadlineMs must be positive" }
    val startedAtMs = requestedAtMs?.takeIf { it > 0L } ?: return false
    if (nowMs < startedAtMs) return false
    return nowMs - startedAtMs >= deadlineMs
}
