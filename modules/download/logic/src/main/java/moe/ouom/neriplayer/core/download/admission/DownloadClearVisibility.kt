package moe.ouom.neriplayer.core.download.admission

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.data.model.download.execution.DownloadClearPurpose

class DownloadClearVisibility {
    enum class ClearPhase {
        PREPARING,
        CANCELLING,
        CLEANING,
        PURGING
    }

    data class ClearProgress(
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
            get() = DownloadClearProgressPresentation.percentage(this)

        val displayFraction: Float
            get() = displayPercentage / 100f
    }

    /** 扫描结果中的条目仍是待处理残留，不能同时计为已完成 */
    data class ArtifactProgress(
        val completedItemCount: Int,
        val totalItemCount: Int,
        val failedItemCount: Int
    )

    fun resolveArtifactProgress(
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
    private val _isTaskProgressClearing = MutableStateFlow(false)
    val isTaskProgressClearing: StateFlow<Boolean> =
        _isTaskProgressClearing.asStateFlow()
    private val _isTaskPresentationCleared = MutableStateFlow(false)
    val isTaskPresentationCleared: StateFlow<Boolean> =
        _isTaskPresentationCleared.asStateFlow()
    private val _progress = MutableStateFlow<ClearProgress?>(null)
    val progress: StateFlow<ClearProgress?> = _progress.asStateFlow()
    private var activeGeneration: Long? = null
    private var activePurpose = DownloadClearPurpose.TASK_PROGRESS

    fun begin(
        token: DownloadAdmissionGate.ClearToken,
        affectedItemCount: Int = 0,
        totalItemCount: Int = 0,
        purpose: DownloadClearPurpose = DownloadClearPurpose.TASK_PROGRESS
    ) {
        synchronized(stateLock) {
            val isNewGeneration = activeGeneration != token.generation
            activeGeneration = token.generation
            _isClearing.value = true
            if (isNewGeneration || purpose == DownloadClearPurpose.FULL_LIBRARY_DELETE) {
                activePurpose = purpose
            }
            _isTaskProgressClearing.value =
                activePurpose == DownloadClearPurpose.TASK_PROGRESS
            if (isNewGeneration) {
                val normalizedAffectedItemCount = affectedItemCount.coerceAtLeast(0)
                val normalizedTotalItemCount = totalItemCount.coerceAtLeast(0)
                _isTaskPresentationCleared.value = false
                _progress.value = ClearProgress(
                    phase = ClearPhase.PREPARING,
                    completedSteps = 0,
                    totalSteps = DOWNLOAD_CLEAR_PHASE_COUNT,
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
            updateProgressLocked(
                phase = progress.phase,
                completedSteps = progress.completedSteps,
                affectedItemCount = progress.affectedItemCount,
                failedItemCount = progress.failedItemCount,
                completedItemCount = progress.completedItemCount,
                totalItemCount = progress.totalItemCount
            )
            _isTaskPresentationCleared.value =
                (_progress.value?.completedSteps ?: 0) > 0
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
                activePurpose = DownloadClearPurpose.TASK_PROGRESS
                _isTaskPresentationCleared.value = false
                _isClearing.value = false
                _isTaskProgressClearing.value = false
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
        val next = DownloadClearProgressPolicy.resolve(
            previous = _progress.value,
            phase = phase,
            completedSteps = completedSteps,
            affectedItemCount = affectedItemCount,
            failedItemCount = failedItemCount,
            completedItemCount = completedItemCount,
            totalItemCount = totalItemCount,
            resetItemWatermark = resetItemWatermark
        ) ?: return
        _progress.value = next
    }
}
