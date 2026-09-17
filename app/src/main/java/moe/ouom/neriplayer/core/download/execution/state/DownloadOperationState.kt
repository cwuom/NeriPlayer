package moe.ouom.neriplayer.core.download.execution.state

import moe.ouom.neriplayer.core.download.execution.persistence.METADATA_ACTION_REQUIRED_OPERATION_STATE
import java.util.Locale

/**
 * 下载 operation 的持久状态
 *
 * wireName 保持数据库和旧版本兼容，业务代码通过这个枚举集中判断转移边界
 */
internal enum class DownloadOperationState(
    val wireName: String
) {
    PENDING_QUEUE("PENDING_QUEUE"),
    QUEUED("QUEUED"),
    RUNNING("RUNNING"),
    COMMITTING("COMMITTING"),
    CORE_COMMITTED("CORE_COMMITTED"),
    ASSETS_ENRICHING("ASSETS_ENRICHING"),
    FINALIZED("FINALIZED"),
    DEGRADED_COMPLETE("DEGRADED_COMPLETE"),
    COMPLETED("COMPLETED"),
    CANCEL_REQUESTED("CANCEL_REQUESTED"),
    CANCELLED("CANCELLED"),
    STOPPED("STOPPED"),
    RETRYABLE("RETRYABLE"),
    INVALID("INVALID"),
    WAITING_STORAGE_MUTATION("WAITING_STORAGE_MUTATION"),
    WAITING_HOST("WAITING_HOST"),
    WAITING_DELETE_CLEANUP("WAITING_DELETE_CLEANUP"),
    METADATA_ACTION_REQUIRED(METADATA_ACTION_REQUIRED_OPERATION_STATE),
    UNKNOWN("");

    companion object {
        private val byWireName = entries
            .filterNot { it == UNKNOWN }
            .associateBy(DownloadOperationState::wireName)

        fun parse(value: String?): DownloadOperationState {
            return value?.trim()?.let(byWireName::get) ?: UNKNOWN
        }
    }
}

/** 核心音频已经可靠落盘，宿主停止只能等待收尾或交给恢复流程 */
internal fun isPostCoreDownloadOperationState(state: String?): Boolean {
    return state?.trim()?.uppercase(Locale.ROOT) in setOf(
        DownloadOperationState.CORE_COMMITTED.wireName,
        DownloadOperationState.ASSETS_ENRICHING.wireName,
        DownloadOperationState.FINALIZED.wireName,
        DownloadOperationState.DEGRADED_COMPLETE.wireName,
        DownloadOperationState.COMPLETED.wireName,
        DownloadOperationState.METADATA_ACTION_REQUIRED.wireName,
        "COMPLETE"
    )
}

/** retry deadline 由持久化时钟判断，缺失 deadline 的旧记录仍可立即调度 */
internal fun isRetryDeadlineReady(nextRetryAtMs: Long?, nowMs: Long): Boolean {
    return nextRetryAtMs == null || nextRetryAtMs <= nowMs
}

internal const val DOWNLOAD_RETRY_BASE_DELAY_MS = 1_000L
internal const val DOWNLOAD_RETRY_MAX_DELAY_MS = 5 * 60 * 1_000L
internal const val DOWNLOAD_RETRY_MAX_COUNT = 31

/** 网络策略和取消收敛由专用唤醒器负责，不能再叠加一个盲目延迟 */
private val IMMEDIATE_DOWNLOAD_RETRY_ERROR_CODES = setOf(
    "NETWORK_POLICY_WAITING",
    "CANCELLATION_SETTLEMENT_PENDING",
    "HOST_ADMISSION_FULL",
    "HOST_TRANSFER_ADMISSION_DEFERRED"
)

internal data class DownloadRetryPlan(
    val retryCount: Int,
    val nextRetryAtMs: Long?
)

internal fun planDownloadRetry(
    currentRetryCount: Int,
    errorCode: String?,
    nowMs: Long
): DownloadRetryPlan {
    val retryCount = currentRetryCount.coerceAtLeast(0)
        .coerceAtMost(DOWNLOAD_RETRY_MAX_COUNT - 1) + 1
    if (errorCode in IMMEDIATE_DOWNLOAD_RETRY_ERROR_CODES) {
        return DownloadRetryPlan(
            retryCount = retryCount,
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
        retryCount = retryCount,
        nextRetryAtMs = deadline
    )
}

internal object DownloadOperationStateTransitions {
    private val coreCommittedStates = listOf(
        DownloadOperationState.CORE_COMMITTED,
        DownloadOperationState.ASSETS_ENRICHING,
        DownloadOperationState.FINALIZED,
        DownloadOperationState.DEGRADED_COMPLETE
    )

    private val resumableCoreExecutionStates = setOf(
        DownloadOperationState.CORE_COMMITTED,
        DownloadOperationState.ASSETS_ENRICHING,
        DownloadOperationState.DEGRADED_COMPLETE
    )

    private val interruptedStates = setOf(
        DownloadOperationState.RUNNING,
        DownloadOperationState.COMMITTING,
        DownloadOperationState.CORE_COMMITTED,
        DownloadOperationState.ASSETS_ENRICHING,
        DownloadOperationState.DEGRADED_COMPLETE
    )

    val interruptedWireNames: Set<String>
        get() = interruptedStates.mapTo(linkedSetOf(), DownloadOperationState::wireName)

    val coreCommittedWireNames: List<String>
        get() = coreCommittedStates.map(DownloadOperationState::wireName)

    val resumableCoreWireNames: Set<String>
        get() = resumableCoreExecutionStates
            .mapTo(linkedSetOf(), DownloadOperationState::wireName)

    /**
     * 返回允许落库的下一状态，null 表示拒绝这次转移
     *
     * 未知状态仍按旧兼容规则处理，避免旧版本写入的新状态被意外删除
     */
    fun resolve(
        currentState: String?,
        requestedState: String
    ): String? {
        val currentRaw = currentState?.trim()?.takeIf(String::isNotEmpty)
            ?: return requestedState
        if (currentRaw == requestedState) return currentRaw

        val current = DownloadOperationState.parse(currentRaw)
        val requested = DownloadOperationState.parse(requestedState)
        if (
            current == DownloadOperationState.CANCELLED ||
            current == DownloadOperationState.COMPLETED
        ) {
            return null
        }
        if (current == DownloadOperationState.WAITING_STORAGE_MUTATION) {
            // 等待状态只能由恢复、取消或失效路径离开，禁止直接跳到完成态
            return requestedState.takeIf {
                requested in setOf(
                    DownloadOperationState.PENDING_QUEUE,
                    DownloadOperationState.QUEUED,
                    DownloadOperationState.RUNNING,
                    DownloadOperationState.RETRYABLE,
                    DownloadOperationState.CANCEL_REQUESTED,
                    DownloadOperationState.CANCELLED,
                    DownloadOperationState.INVALID
                )
            }
        }
        if (requested == DownloadOperationState.CANCEL_REQUESTED) {
            return requestedState.takeIf {
                it != currentRaw && current in setOf(
                    DownloadOperationState.QUEUED,
                    DownloadOperationState.RUNNING,
                    DownloadOperationState.STOPPED,
                    DownloadOperationState.RETRYABLE
                )
            }
        }
        if (requested == DownloadOperationState.CANCELLED) {
            return requestedState.takeIf {
                current == DownloadOperationState.CANCEL_REQUESTED ||
                    current in setOf(
                        DownloadOperationState.QUEUED,
                        DownloadOperationState.RUNNING,
                        DownloadOperationState.STOPPED,
                        DownloadOperationState.RETRYABLE
                    )
            }
        }
        if (requested == DownloadOperationState.COMMITTING) {
            return requestedState.takeIf {
                current in setOf(
                    DownloadOperationState.PENDING_QUEUE,
                    DownloadOperationState.QUEUED,
                    DownloadOperationState.RUNNING
                )
            }
        }
        if (requested == DownloadOperationState.CORE_COMMITTED) {
            return requestedState.takeIf { current == DownloadOperationState.COMMITTING }
        }
        if (
            requested == DownloadOperationState.RUNNING &&
            current in setOf(
                DownloadOperationState.RUNNING,
                DownloadOperationState.COMMITTING
            )
        ) {
            return requestedState
        }
        if (requested == DownloadOperationState.RETRYABLE) {
            return requestedState.takeIf {
                current in setOf(
                    DownloadOperationState.PENDING_QUEUE,
                    DownloadOperationState.QUEUED,
                    DownloadOperationState.RUNNING,
                    DownloadOperationState.COMMITTING
                )
            }
        }
        if (requested == DownloadOperationState.METADATA_ACTION_REQUIRED) {
            return requestedState.takeIf {
                current == DownloadOperationState.DEGRADED_COMPLETE
            }
        }
        if (current == DownloadOperationState.METADATA_ACTION_REQUIRED) {
            return requestedState.takeIf {
                requested == DownloadOperationState.ASSETS_ENRICHING ||
                    requested == DownloadOperationState.FINALIZED
            }
        }
        if (current == DownloadOperationState.CANCEL_REQUESTED) {
            return requestedState.takeIf { requested in coreCommittedStates }
        }
        if (requested == DownloadOperationState.INVALID) {
            return requestedState.takeIf {
                current in setOf(
                    DownloadOperationState.PENDING_QUEUE,
                    DownloadOperationState.QUEUED,
                    DownloadOperationState.RUNNING,
                    DownloadOperationState.COMMITTING,
                    DownloadOperationState.CANCEL_REQUESTED,
                    DownloadOperationState.STOPPED,
                    DownloadOperationState.RETRYABLE
                )
            }
        }
        if (requested == DownloadOperationState.COMPLETED) {
            return requestedState.takeIf {
                current in setOf(
                    DownloadOperationState.PENDING_QUEUE,
                    DownloadOperationState.QUEUED,
                    DownloadOperationState.RUNNING,
                    DownloadOperationState.RETRYABLE,
                    DownloadOperationState.COMMITTING,
                    DownloadOperationState.CORE_COMMITTED,
                    DownloadOperationState.ASSETS_ENRICHING,
                    DownloadOperationState.FINALIZED,
                    DownloadOperationState.DEGRADED_COMPLETE
                )
            }
        }
        if (
            current == DownloadOperationState.DEGRADED_COMPLETE &&
            requested in setOf(
                DownloadOperationState.ASSETS_ENRICHING,
                DownloadOperationState.FINALIZED
            )
        ) {
            return requestedState
        }

        val currentCoreIndex = coreCommittedStates.indexOf(current)
        if (currentCoreIndex >= 0) {
            val requestedCoreIndex = coreCommittedStates.indexOf(requested)
            return requestedState.takeIf { requestedCoreIndex >= currentCoreIndex }
        }
        return requestedState.takeIf {
            current in setOf(
                DownloadOperationState.PENDING_QUEUE,
                DownloadOperationState.QUEUED,
                DownloadOperationState.RUNNING,
                DownloadOperationState.RETRYABLE
            )
        }
    }
}
