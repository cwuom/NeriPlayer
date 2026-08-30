package moe.ouom.neriplayer.core.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private val admissionMutex = Mutex()
    private val stateLock = Any()
    private var activeClear: CompletableDeferred<Unit>? = null
    private var generation = 0L

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

    suspend fun admit(
        ticket: Long,
        block: suspend () -> Unit
    ): Boolean {
        while (true) {
            val beforeLock = snapshot()
            if (beforeLock.generation != ticket) {
                return false
            }
            beforeLock.completion?.await()

            var retryAfterClear = false
            val admitted = admissionMutex.withLock {
                val insideLock = snapshot()
                when {
                    insideLock.generation != ticket -> false
                    insideLock.completion != null -> {
                        retryAfterClear = true
                        false
                    }
                    else -> {
                        block()
                        true
                    }
                }
            }
            if (admitted || !retryAfterClear) {
                return admitted
            }
        }
    }

    suspend fun runClear(
        token: ClearToken,
        block: suspend () -> Unit
    ) {
        require(token.ownsClear) { "clear token does not own the active clear" }
        try {
            admissionMutex.withLock {
                block()
            }
        } finally {
            synchronized(stateLock) {
                if (activeClear === token.completion && generation == token.generation) {
                    activeClear = null
                }
            }
            token.completion.complete(Unit)
        }
    }

    /** 调用方确认持久栅栏未激活后，只释放内存闸门让下一次重试取得新的所有者令牌 */
    fun releaseFailedClear(token: ClearToken): Boolean {
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

    /** 扫描结果中的条目都已检查，仍存在的条目计为待重试失败项 */
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
        val resultComplete = scanComplete && !(cleanupFailed && normalizedCount == 0)
        if (!resultComplete) {
            val unknownCount = normalizedCount.coerceAtLeast(1)
            return ArtifactProgress(
                completedItemCount = 0,
                totalItemCount = unknownCount,
                failedItemCount = unknownCount
            )
        }
        return ArtifactProgress(
            completedItemCount = normalizedCount,
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

    fun begin(token: DownloadAdmissionGate.ClearToken) {
        synchronized(stateLock) {
            val isNewGeneration = activeGeneration != token.generation
            activeGeneration = token.generation
            _isClearing.value = true
            if (isNewGeneration) {
                _isTaskPresentationCleared.value = false
                _progress.value = ClearProgress(
                    phase = ClearPhase.PREPARING,
                    completedSteps = 0,
                    totalSteps = CLEAR_PHASE_COUNT,
                    affectedItemCount = 0
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
            _progress.value = progress.copy(
                completedSteps = progress.completedSteps.coerceIn(0, CLEAR_PHASE_COUNT),
                totalSteps = CLEAR_PHASE_COUNT,
                affectedItemCount = progress.affectedItemCount.coerceAtLeast(0),
                failedItemCount = progress.failedItemCount.coerceAtLeast(0),
                completedItemCount = progress.completedItemCount.coerceAtLeast(0),
                totalItemCount = progress.totalItemCount.coerceAtLeast(0)
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
        synchronized(stateLock) {
            if (activeGeneration == token.generation) {
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
        _progress.value = ClearProgress(
            phase = phase,
            completedSteps = completedSteps.coerceIn(0, CLEAR_PHASE_COUNT),
            totalSteps = CLEAR_PHASE_COUNT,
            affectedItemCount = affectedItemCount.coerceAtLeast(0),
            failedItemCount = failedItemCount.coerceAtLeast(0),
            completedItemCount = effectiveCompletedItems,
            totalItemCount = effectiveTotalItems
        )
    }

    private companion object {
        const val CLEAR_PHASE_COUNT = 4
    }
}
