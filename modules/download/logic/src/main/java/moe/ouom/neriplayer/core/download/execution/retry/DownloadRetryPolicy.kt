package moe.ouom.neriplayer.core.download.execution.retry

import moe.ouom.neriplayer.data.model.download.execution.DownloadRetryPlan

/** retry deadline 由持久化时钟判断，缺失 deadline 的旧记录仍可立即调度 */
fun isRetryDeadlineReady(nextRetryAtMs: Long?, nowMs: Long): Boolean {
    return nextRetryAtMs == null || nextRetryAtMs <= nowMs
}

const val DOWNLOAD_RETRY_BASE_DELAY_MS = 1_000L
const val DOWNLOAD_RETRY_MAX_DELAY_MS = 5 * 60 * 1_000L
const val DOWNLOAD_RETRY_MAX_COUNT = 31
const val DOWNLOAD_INTEGRITY_MAX_FAILURES = 3
const val ARTIFACT_LEASE_CONTENDED_ERROR_CODE = "ARTIFACT_LEASE_CONTENDED"

/** 网络策略和取消收敛由专用唤醒器负责，不能再叠加一个盲目延迟 */
private val IMMEDIATE_DOWNLOAD_RETRY_ERROR_CODES = setOf(
    "NETWORK_POLICY_WAITING",
    "CANCELLATION_SETTLEMENT_PENDING",
    "HOST_ADMISSION_FULL",
    "HOST_TRANSFER_ADMISSION_DEFERRED",
    ARTIFACT_LEASE_CONTENDED_ERROR_CODE
)

fun planDownloadRetry(
    currentRetryCount: Int,
    errorCode: String?,
    nowMs: Long
): DownloadRetryPlan {
    val retryCount = currentRetryCount.coerceAtLeast(0)
        .coerceAtMost(DOWNLOAD_RETRY_MAX_COUNT - 1) + 1
    if (errorCode in IMMEDIATE_DOWNLOAD_RETRY_ERROR_CODES) {
        return DownloadRetryPlan(
            retryCount = currentRetryCount.coerceIn(0, DOWNLOAD_RETRY_MAX_COUNT),
            nextRetryAtMs = null
        )
    }
    val exponent = (retryCount - 1).coerceAtMost(30)
    val delayMs = (DOWNLOAD_RETRY_BASE_DELAY_MS shl exponent)
        .coerceAtMost(DOWNLOAD_RETRY_MAX_DELAY_MS)
    val deadline = if (nowMs > Long.MAX_VALUE - delayMs) {
        Long.MAX_VALUE
    } else {
        nowMs + delayMs
    }
    return DownloadRetryPlan(
        retryCount = if (errorCode == "NETWORK_UNAVAILABLE") {
            currentRetryCount.coerceIn(0, DOWNLOAD_RETRY_MAX_COUNT)
        } else {
            retryCount
        },
        nextRetryAtMs = deadline
    )
}
