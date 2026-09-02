package moe.ouom.neriplayer.core.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DownloadAdmissionGate {
    internal data class ClearToken(
        internal val completion: CompletableDeferred<Unit>,
        internal val generation: Long,
        internal val ownsClear: Boolean
    )

    private data class GateSnapshot(
        val completion: CompletableDeferred<Unit>?,
        val generation: Long
    )

    private sealed interface AdmissionDecision {
        data class Wait(val completion: CompletableDeferred<Unit>) : AdmissionDecision
        data object Reject : AdmissionDecision
        data object Entered : AdmissionDecision
    }

    private val stateLock = Any()
    private var activeClear: CompletableDeferred<Unit>? = null
    private var generation = 0L
    private var activeAdmissions = 0
    private var idleCompletion = completedDeferred()

    fun ticket(): Long = synchronized(stateLock) { generation }

    fun openTicketOrNull(): Long? = synchronized(stateLock) {
        generation.takeIf { activeClear == null }
    }

    fun beginClear(): ClearToken = synchronized(stateLock) {
        activeClear?.let { completion ->
            return@synchronized ClearToken(
                completion = completion,
                generation = generation,
                ownsClear = false
            )
        }
        generation++
        val completion = CompletableDeferred<Unit>()
        activeClear = completion
        ClearToken(
            completion = completion,
            generation = generation,
            ownsClear = true
        )
    }

    suspend fun awaitOpen() {
        while (true) {
            val completion = snapshot().completion ?: return
            completion.await()
        }
    }

    suspend fun awaitClear(token: ClearToken) {
        token.completion.await()
    }

    /** 等待已登记的 mutation 完成，不持有准入状态锁 */
    suspend fun awaitIdle() {
        val completion = synchronized(stateLock) { idleCompletion }
        completion.await()
    }

    /** 只在内存状态中登记准入，挂起工作始终在状态锁外执行 */
    suspend fun admit(
        ticket: Long,
        block: suspend () -> Unit
    ): Boolean {
        while (true) {
            when (val decision = tryEnter(ticket)) {
                AdmissionDecision.Reject -> return false
                is AdmissionDecision.Wait -> decision.completion.await()
                AdmissionDecision.Entered -> {
                    try {
                        block()
                        return true
                    } finally {
                        leave()
                    }
                }
            }
        }
    }

    /** 清空工作不持有状态锁，generation 负责阻止旧票据重新发布 */
    suspend fun runClear(
        token: ClearToken,
        block: suspend () -> Unit
    ) {
        require(token.ownsClear) { "clear token does not own the active clear" }
        try {
            block()
        } finally {
            synchronized(stateLock) {
                if (activeClear === token.completion && generation == token.generation) {
                    activeClear = null
                }
            }
            token.completion.complete(Unit)
        }
    }

    private fun tryEnter(ticket: Long): AdmissionDecision = synchronized(stateLock) {
        if (generation != ticket) {
            return@synchronized AdmissionDecision.Reject
        }
        activeClear?.let { completion ->
            return@synchronized AdmissionDecision.Wait(completion)
        }
        if (activeAdmissions == 0) {
            idleCompletion = CompletableDeferred()
        }
        activeAdmissions++
        AdmissionDecision.Entered
    }

    private fun leave() {
        val completion = synchronized(stateLock) {
            check(activeAdmissions > 0) { "download admission underflow" }
            activeAdmissions--
            if (activeAdmissions == 0) idleCompletion else null
        }
        completion?.complete(Unit)
    }

    /**
     * 持久栅栏和 owner 快照已经覆盖旧任务后，后台 I/O 不应继续占用全局内存闸门
     *
     * 旧票据已在 beginClear 时失效；新的不同 stableKey 仍会在持久栅栏处做 owner
     * 复核，因此这里仅解除本进程的等待，不会让旧 generation 重新发布
     */
    fun detachClearForDurableRecovery(token: ClearToken): Boolean {
        require(token.ownsClear) { "clear token does not own the active clear" }
        val released = synchronized(stateLock) {
            if (activeClear === token.completion && generation == token.generation) {
                activeClear = null
                true
            } else {
                false
            }
        }
        if (released) {
            token.completion.complete(Unit)
        }
        return released
    }

    /** 持久栅栏落盘失败时也要释放本进程 gate，兼容旧调用方 */
    fun releaseFailedClear(token: ClearToken): Boolean =
        detachClearForDurableRecovery(token)

    private fun completedDeferred(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { it.complete(Unit) }

    private fun snapshot(): GateSnapshot = synchronized(stateLock) {
        GateSnapshot(
            completion = activeClear,
            generation = generation
        )
    }
}

internal class DownloadClearVisibility {
    internal enum class ClearPhase {
        PREPARING,
        CANCELLING,
        CLEANING,
        PURGING
    }

    internal data class ClearProgress(
        val phase: ClearPhase,
        val completedSteps: Int,
        val totalSteps: Int,
        val affectedItemCount: Int,
        val failedItemCount: Int = 0,
        val completedItemCount: Int = 0,
        val totalItemCount: Int = 0
    ) {
        val percentage: Int
            get() = if (totalSteps <= 0) {
                0
            } else {
                ((completedSteps.coerceIn(0, totalSteps) * 100L) / totalSteps)
                    .toInt()
                    .coerceIn(0, 100)
            }

        val fraction: Float
            get() = percentage / 100f

        val itemFraction: Float
            get() = if (totalItemCount <= 0) {
                0f
            } else {
                (completedItemCount.toFloat() / totalItemCount.toFloat())
                    .coerceIn(0f, 1f)
            }

        val displayPercentage: Int
            get() {
                if (phase != ClearPhase.CLEANING || totalItemCount <= 0) {
                    return percentage
                }
                val units = completedSteps.coerceIn(0, totalSteps) + itemFraction
                return ((units / totalSteps.coerceAtLeast(1)) * 100f)
                    .toInt()
                    .coerceIn(0, 100)
            }

        val displayFraction: Float
            get() = displayPercentage / 100f
    }

    /** 扫描结果中的条目仍是待处理残留，不能同时计为已完成 */
    internal data class ArtifactProgress(
        val completedItemCount: Int,
        val totalItemCount: Int,
        val failedItemCount: Int
    )

    internal fun resolveArtifactProgress(
        artifactCount: Int,
        scanComplete: Boolean,
        cleanupFailed: Boolean = false
    ): ArtifactProgress {
        val normalizedCount = artifactCount.coerceAtLeast(0)
        if (!scanComplete || cleanupFailed && normalizedCount == 0) {
            val unknownCount = normalizedCount.coerceAtLeast(1)
            return ArtifactProgress(
                completedItemCount = 0,
                totalItemCount = unknownCount,
                failedItemCount = unknownCount
            )
        }
        return ArtifactProgress(
            completedItemCount = 0,
            totalItemCount = normalizedCount,
            failedItemCount = normalizedCount
        )
    }

    private val stateLock = Any()
    private val _isClearing = MutableStateFlow(false)
    val isClearing: StateFlow<Boolean> = _isClearing.asStateFlow()
    private val _isTaskPresentationCleared = MutableStateFlow(false)
    val isTaskPresentationCleared: StateFlow<Boolean> =
        _isTaskPresentationCleared.asStateFlow()
    private val _progress = MutableStateFlow<ClearProgress?>(null)
    val progress: StateFlow<ClearProgress?> = _progress.asStateFlow()
    private var activeGeneration: Long? = null

    fun begin(
        token: DownloadAdmissionGate.ClearToken,
        affectedItemCount: Int = 0,
        totalItemCount: Int = 0
    ) {
        synchronized(stateLock) {
            val isNewGeneration = activeGeneration != token.generation
            activeGeneration = token.generation
            _isClearing.value = true
            if (isNewGeneration) {
                val normalizedAffectedItemCount = affectedItemCount.coerceAtLeast(0)
                val normalizedTotalItemCount = totalItemCount.coerceAtLeast(0)
                _isTaskPresentationCleared.value = false
                _progress.value = ClearProgress(
                    phase = ClearPhase.PREPARING,
                    completedSteps = 0,
                    totalSteps = CLEAR_PHASE_COUNT,
                    affectedItemCount = normalizedAffectedItemCount,
                    totalItemCount = normalizedTotalItemCount
                )
            }
        }
    }

    fun markFencePersisted(token: DownloadAdmissionGate.ClearToken) {
        synchronized(stateLock) {
            if (activeGeneration == token.generation) {
                _isTaskPresentationCleared.value = true
                updateProgressLocked(
                    phase = ClearPhase.CANCELLING,
                    completedSteps = 1,
                    affectedItemCount = _progress.value?.affectedItemCount ?: 0
                )
            }
        }
    }

    fun restore(token: DownloadAdmissionGate.ClearToken, progress: ClearProgress) {
        synchronized(stateLock) {
            if (activeGeneration != token.generation) return
            _isClearing.value = true
            _isTaskPresentationCleared.value = progress.completedSteps > 0
            val previous = _progress.value
            _progress.value = progress.copy(
                completedSteps = progress.completedSteps.coerceIn(0, CLEAR_PHASE_COUNT),
                totalSteps = CLEAR_PHASE_COUNT,
                affectedItemCount = maxOf(
                    previous?.affectedItemCount ?: 0,
                    progress.affectedItemCount.coerceAtLeast(0)
                ),
                failedItemCount = progress.failedItemCount.coerceAtLeast(0),
                completedItemCount = progress.completedItemCount.coerceAtLeast(0),
                totalItemCount = maxOf(
                    previous?.totalItemCount ?: 0,
                    progress.totalItemCount.coerceAtLeast(0)
                )
            )
        }
    }

    fun update(
        token: DownloadAdmissionGate.ClearToken,
        phase: ClearPhase,
        completedSteps: Int,
        affectedItemCount: Int,
        failedItemCount: Int = 0,
        completedItemCount: Int? = null,
        totalItemCount: Int? = null,
        resetItemWatermark: Boolean = false
    ) {
        synchronized(stateLock) {
            if (activeGeneration != token.generation) return
            updateProgressLocked(
                phase = phase,
                completedSteps = completedSteps,
                affectedItemCount = affectedItemCount,
                failedItemCount = failedItemCount,
                completedItemCount = completedItemCount,
                totalItemCount = totalItemCount,
                resetItemWatermark = resetItemWatermark
            )
        }
    }

    fun finish(token: DownloadAdmissionGate.ClearToken) {
        finishGeneration(token.generation)
    }

    fun finishGeneration(generation: Long) {
        synchronized(stateLock) {
            if (activeGeneration == generation) {
                activeGeneration = null
                _isTaskPresentationCleared.value = false
                _isClearing.value = false
                _progress.value = null
            }
        }
    }

    private fun updateProgressLocked(
        phase: ClearPhase,
        completedSteps: Int,
        affectedItemCount: Int,
        failedItemCount: Int = 0,
        completedItemCount: Int? = null,
        totalItemCount: Int? = null,
        resetItemWatermark: Boolean = false
    ) {
        val previous = _progress.value
        val requestedTotalItems = (totalItemCount ?: previous?.totalItemCount ?: 0)
            .coerceAtLeast(0)
        // 不完整的 Provider 扫描沿用旧分母，完整扫描才可以把分母收敛到残留数量
        val keepItemWatermark = !resetItemWatermark && previous?.phase == phase
        val previousTotalItems = previous?.totalItemCount ?: 0
        val effectiveTotalItems = if (keepItemWatermark) {
            maxOf(previousTotalItems, requestedTotalItems)
        } else {
            requestedTotalItems
        }
        val requestedCompletedItems = (completedItemCount
            ?: previous?.completedItemCount
            ?: 0)
            .coerceAtLeast(0)
        val previousCompletedItems = previous?.completedItemCount ?: 0
        val effectiveCompletedItems = (if (keepItemWatermark) {
            maxOf(previousCompletedItems, requestedCompletedItems)
        } else {
            requestedCompletedItems
        })
            .let { completed ->
                if (effectiveTotalItems > 0) {
                    completed.coerceAtMost(effectiveTotalItems)
                } else {
                    completed
                }
            }
        val effectiveAffectedItemCount = maxOf(
            previous?.affectedItemCount ?: 0,
            affectedItemCount.coerceAtLeast(0)
        )
        _progress.value = ClearProgress(
            phase = phase,
            completedSteps = completedSteps.coerceIn(0, CLEAR_PHASE_COUNT),
            totalSteps = CLEAR_PHASE_COUNT,
            affectedItemCount = effectiveAffectedItemCount,
            failedItemCount = failedItemCount.coerceAtLeast(0),
            completedItemCount = effectiveCompletedItems,
            totalItemCount = effectiveTotalItems
        )
    }

    private companion object {
        const val CLEAR_PHASE_COUNT = 4
    }
}
