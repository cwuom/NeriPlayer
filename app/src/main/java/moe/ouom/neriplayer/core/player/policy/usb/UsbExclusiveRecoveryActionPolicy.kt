package moe.ouom.neriplayer.core.player.policy.usb

import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveErrorCode
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveFeedbackMode
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveFeedbackState
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveRecoveryAction
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveRecoveryActionAckStatus
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveRecoveryActionOwner
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveRuntimeMetrics
import moe.ouom.neriplayer.core.player.usb.transport.isKotlinTerminalAction

internal enum class UsbExclusiveRecoveryRouteAction {
    None,
    FreshOpen,
    StopPreserveIntent
}

internal data class UsbExclusiveRecoveryActionKey(
    val recoveryEpoch: Long,
    val actionGeneration: Long,
    val actionId: Long
)

internal data class UsbExclusiveRecoveryActionDecision(
    val routeAction: UsbExclusiveRecoveryRouteAction,
    val nativeAction: UsbExclusiveRecoveryAction,
    val actionKey: UsbExclusiveRecoveryActionKey?,
    val shouldAcknowledge: Boolean,
    val reason: String,
    val freshOpenBudgetUsed: Int = 0,
    val freshOpenBudgetLimit: Int = 0,
    val recoveryBudgetKey: String? = null
) {
    val shouldExecute: Boolean
        get() = routeAction != UsbExclusiveRecoveryRouteAction.None
}

internal class UsbExclusiveRecoveryActionPolicy(
    private val maxFreshOpenActionsPerEpoch: Int = DEFAULT_MAX_FRESH_OPEN_ACTIONS_PER_EPOCH,
    private val stableGraceMs: Long = DEFAULT_STABLE_GRACE_MS,
    private val retainedActionLimit: Int = DEFAULT_RETAINED_ACTION_LIMIT
) {
    private val processedActions = LinkedHashSet<UsbExclusiveRecoveryActionKey>()
    private val actionOrder = ArrayDeque<UsbExclusiveRecoveryActionKey>()
    private val pendingActions = linkedMapOf<
        UsbExclusiveRecoveryActionKey,
        UsbExclusiveRecoveryActionDecision
    >()
    private val freshOpenCounts = linkedMapOf<String, Int>()
    private var stablePlaybackSinceMs: Long? = null

    init {
        require(maxFreshOpenActionsPerEpoch > 0) { "fresh open budget must be positive" }
        require(stableGraceMs >= 0L) { "stable grace must be non-negative" }
        require(retainedActionLimit > 0) { "retained action limit must be positive" }
    }

    @Synchronized
    fun evaluate(
        metrics: UsbExclusiveRuntimeMetrics,
        activeStreamGeneration: Long?,
        nowMs: Long
    ): UsbExclusiveRecoveryActionDecision {
        updateStableRecoveryBudget(metrics, nowMs)
        if (!metrics.reportValid) return ignore(metrics, null, "invalid_report")
        if (metrics.reportVersion < 2) return ignore(metrics, null, "legacy_report")
        if (metrics.recommendedAction == UsbExclusiveRecoveryAction.None) {
            return ignore(metrics, null, "no_action")
        }
        val actionKey = metrics.recoveryActionKey()
            ?: return ignore(metrics, null, "missing_action_key")
        if (metrics.actionOwner != UsbExclusiveRecoveryActionOwner.Kotlin) {
            return ignore(metrics, actionKey, "native_owned_action")
        }
        if (!metrics.recommendedAction.isKotlinTerminalAction) {
            return ignore(metrics, actionKey, "non_terminal_kotlin_action")
        }
        if (
            activeStreamGeneration != null &&
            actionKey.actionGeneration != activeStreamGeneration
        ) {
            return ignore(metrics, actionKey, "stale_generation")
        }
        if (processedActions.contains(actionKey)) {
            return ignore(metrics, actionKey, "duplicate_action")
        }
        if (pendingActions.containsKey(actionKey)) {
            return ignore(metrics, actionKey, "action_ack_pending")
        }
        val decision = when (metrics.recommendedAction) {
            UsbExclusiveRecoveryAction.FreshOpen -> freshOpenDecision(metrics, actionKey)
            UsbExclusiveRecoveryAction.StopPreserveIntent -> execute(
                metrics = metrics,
                actionKey = actionKey,
                routeAction = UsbExclusiveRecoveryRouteAction.StopPreserveIntent,
                reason = "stop_preserve_intent"
            )
            else -> ignore(metrics, actionKey, "unsupported_kotlin_action")
        }
        if (decision.shouldAcknowledge) {
            rememberPendingAction(actionKey, decision)
        }
        return decision
    }

    @Synchronized
    fun completeAcknowledgement(
        decision: UsbExclusiveRecoveryActionDecision,
        ackStatus: UsbExclusiveRecoveryActionAckStatus
    ): Boolean {
        decision.actionKey?.let(pendingActions::remove)
        if (!shouldExecuteUsbExclusiveRecoveryRouteActionAfterAck(decision, ackStatus)) {
            return false
        }
        val budgetKey = if (decision.routeAction == UsbExclusiveRecoveryRouteAction.FreshOpen) {
            decision.recoveryBudgetKey ?: return false
        } else {
            null
        }
        decision.actionKey?.let(::rememberAction)
        if (budgetKey != null) {
            val committed = freshOpenCounts[budgetKey] ?: 0
            freshOpenCounts[budgetKey] = maxOf(committed, decision.freshOpenBudgetUsed)
        }
        return true
    }

    private fun updateStableRecoveryBudget(
        metrics: UsbExclusiveRuntimeMetrics,
        nowMs: Long
    ) {
        if (!isStableForUsbExclusiveRecoveryReset(metrics)) {
            stablePlaybackSinceMs = null
            return
        }
        val stableSince = stablePlaybackSinceMs
        if (stableSince == null) {
            stablePlaybackSinceMs = nowMs
            return
        }
        if (nowMs - stableSince < stableGraceMs) return
        freshOpenCounts.clear()
        processedActions.clear()
        actionOrder.clear()
        stablePlaybackSinceMs = nowMs
    }

    private fun freshOpenDecision(
        metrics: UsbExclusiveRuntimeMetrics,
        actionKey: UsbExclusiveRecoveryActionKey
    ): UsbExclusiveRecoveryActionDecision {
        val budgetKey = metrics.recoveryBudgetKey()
        val committed = freshOpenCounts[budgetKey] ?: 0
        val reserved = pendingActions.values.count { decision ->
            decision.routeAction == UsbExclusiveRecoveryRouteAction.FreshOpen &&
                decision.recoveryBudgetKey == budgetKey
        }
        val used = committed + reserved
        if (used >= maxFreshOpenActionsPerEpoch) {
            return execute(
                metrics = metrics,
                actionKey = actionKey,
                routeAction = UsbExclusiveRecoveryRouteAction.StopPreserveIntent,
                reason = "fresh_open_budget_exhausted",
                freshOpenBudgetUsed = used,
                recoveryBudgetKey = budgetKey
            )
        }
        val nextUsed = used + 1
        return execute(
            metrics = metrics,
            actionKey = actionKey,
            routeAction = UsbExclusiveRecoveryRouteAction.FreshOpen,
            reason = "fresh_open",
            freshOpenBudgetUsed = nextUsed,
            recoveryBudgetKey = budgetKey
        )
    }

    private fun execute(
        metrics: UsbExclusiveRuntimeMetrics,
        actionKey: UsbExclusiveRecoveryActionKey,
        routeAction: UsbExclusiveRecoveryRouteAction,
        reason: String,
        freshOpenBudgetUsed: Int = 0,
        recoveryBudgetKey: String? = null
    ): UsbExclusiveRecoveryActionDecision {
        return UsbExclusiveRecoveryActionDecision(
            routeAction = routeAction,
            nativeAction = metrics.recommendedAction,
            actionKey = actionKey,
            shouldAcknowledge = true,
            reason = reason,
            freshOpenBudgetUsed = freshOpenBudgetUsed,
            freshOpenBudgetLimit = maxFreshOpenActionsPerEpoch,
            recoveryBudgetKey = recoveryBudgetKey
        )
    }

    private fun ignore(
        metrics: UsbExclusiveRuntimeMetrics,
        actionKey: UsbExclusiveRecoveryActionKey?,
        reason: String
    ): UsbExclusiveRecoveryActionDecision {
        return UsbExclusiveRecoveryActionDecision(
            routeAction = UsbExclusiveRecoveryRouteAction.None,
            nativeAction = metrics.recommendedAction,
            actionKey = actionKey,
            shouldAcknowledge = false,
            reason = reason,
            freshOpenBudgetLimit = maxFreshOpenActionsPerEpoch
        )
    }

    private fun rememberAction(actionKey: UsbExclusiveRecoveryActionKey) {
        if (!processedActions.add(actionKey)) return
        actionOrder.addLast(actionKey)
        while (actionOrder.size > retainedActionLimit) {
            val evicted = actionOrder.removeFirst()
            processedActions.remove(evicted)
        }
    }

    private fun rememberPendingAction(
        actionKey: UsbExclusiveRecoveryActionKey,
        decision: UsbExclusiveRecoveryActionDecision
    ) {
        pendingActions[actionKey] = decision
        while (pendingActions.size > retainedActionLimit) {
            val oldest = pendingActions.keys.firstOrNull() ?: return
            pendingActions.remove(oldest)
        }
    }

    private fun UsbExclusiveRuntimeMetrics.recoveryActionKey(): UsbExclusiveRecoveryActionKey? {
        val epoch = recoveryEpoch ?: return null
        val generation = actionGeneration ?: return null
        val id = actionId ?: return null
        return UsbExclusiveRecoveryActionKey(
            recoveryEpoch = epoch,
            actionGeneration = generation,
            actionId = id
        )
    }

    private fun UsbExclusiveRuntimeMetrics.recoveryBudgetKey(): String {
        val candidate = candidateId?.takeIf(String::isNotBlank) ?: "unknown_candidate"
        val epoch = recoveryEpoch ?: -1L
        return "$candidate|$epoch"
    }

    companion object {
        const val DEFAULT_MAX_FRESH_OPEN_ACTIONS_PER_EPOCH = 3
        const val DEFAULT_STABLE_GRACE_MS = 10_000L
        const val DEFAULT_RETAINED_ACTION_LIMIT = 64
    }
}

internal fun isStableForUsbExclusiveRecoveryReset(
    metrics: UsbExclusiveRuntimeMetrics
): Boolean {
    if (!metrics.reportValid || metrics.reportVersion < 2) return false
    if (metrics.playbackReady != true) return false
    if (metrics.terminalFailure == true || metrics.transportFailed == true) return false
    if (metrics.errorCode != UsbExclusiveErrorCode.None) return false
    if (metrics.feedbackMode == UsbExclusiveFeedbackMode.Disabled) return true
    return metrics.feedbackState == UsbExclusiveFeedbackState.Locked &&
        metrics.feedbackReady == true &&
        metrics.realPcmReleased == true &&
        metrics.canAcceptPcm == true &&
        metrics.feedbackReusable == true
}

internal fun shouldExecuteUsbExclusiveRecoveryRouteActionAfterAck(
    decision: UsbExclusiveRecoveryActionDecision,
    ackStatus: UsbExclusiveRecoveryActionAckStatus
): Boolean {
    if (!decision.shouldExecute) return false
    if (!decision.shouldAcknowledge) return true
    return ackStatus == UsbExclusiveRecoveryActionAckStatus.Acked ||
        ackStatus == UsbExclusiveRecoveryActionAckStatus.AlreadyAcked
}
