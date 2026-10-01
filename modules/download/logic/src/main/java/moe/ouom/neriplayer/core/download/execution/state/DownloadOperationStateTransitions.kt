package moe.ouom.neriplayer.core.download.execution.state

import java.util.Locale
import moe.ouom.neriplayer.data.model.download.execution.DownloadOperationState

/** 核心音频已经可靠落盘，宿主停止只能等待收尾或交给恢复流程 */
fun isPostCoreDownloadOperationState(state: String?): Boolean {
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

object DownloadOperationStateTransitions {
    val interruptedWireNames: Set<String>
        get() = DownloadOperationStateGroups.interrupted.mapTo(linkedSetOf(), DownloadOperationState::wireName)

    val coreCommittedWireNames: List<String>
        get() = DownloadOperationStateGroups.coreCommitted.map(DownloadOperationState::wireName)

    val resumableCoreWireNames: Set<String>
        get() = DownloadOperationStateGroups.resumableCore.mapTo(linkedSetOf(), DownloadOperationState::wireName)

    /** 取消、核心提交和收尾规则按持久状态的既有优先级执行 */
    fun resolve(currentState: String?, requestedState: String): String? {
        val currentRaw = normalizedCurrentState(currentState) ?: return requestedState
        if (currentRaw == requestedState) return currentRaw
        val current = DownloadOperationState.parse(currentRaw)
        if (isTerminal(current)) return null
        val requested = DownloadOperationState.parse(requestedState)
        // false 是明确拒绝，只有 null 才把转移交给下一组规则
        val allowed = DownloadControlTransitionPolicy.resolve(current, requested)
            ?: DownloadCoreTransitionPolicy.resolve(current, requested)
            ?: DownloadFinalizationTransitionPolicy.resolve(current, requested)
        return requestedState.takeIf { allowed }
    }

    private fun normalizedCurrentState(state: String?): String? =
        state?.trim()?.takeIf(String::isNotEmpty)

    private fun isTerminal(state: DownloadOperationState): Boolean =
        state == DownloadOperationState.CANCELLED || state == DownloadOperationState.COMPLETED
}
