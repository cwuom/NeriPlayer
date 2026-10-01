package moe.ouom.neriplayer.core.download.admission

import moe.ouom.neriplayer.core.download.admission.DownloadClearVisibility.ClearPhase
import moe.ouom.neriplayer.core.download.admission.DownloadClearVisibility.ClearProgress

internal const val DOWNLOAD_CLEAR_PHASE_COUNT = 4

internal object DownloadClearProgressPolicy {
    fun resolve(
        previous: ClearProgress?,
        phase: ClearPhase,
        completedSteps: Int,
        affectedItemCount: Int,
        failedItemCount: Int,
        completedItemCount: Int?,
        totalItemCount: Int?,
        resetItemWatermark: Boolean
    ): ClearProgress? {
        if (isStalePhase(previous, phase)) return null
        val keepWatermark = keepsItemWatermark(previous, phase, resetItemWatermark)
        val totalItems = totalItems(previous, totalItemCount, keepWatermark)
        return ClearProgress(
            phase = phase,
            completedSteps = completedSteps(previous, completedSteps),
            totalSteps = DOWNLOAD_CLEAR_PHASE_COUNT,
            affectedItemCount = affectedItems(previous, affectedItemCount),
            failedItemCount = failedItemCount.coerceAtLeast(0),
            completedItemCount = completedItems(previous, completedItemCount, keepWatermark, totalItems),
            totalItemCount = totalItems
        )
    }

    private fun isStalePhase(previous: ClearProgress?, phase: ClearPhase): Boolean =
        previous != null && phase.ordinal < previous.phase.ordinal

    private fun keepsItemWatermark(
        previous: ClearProgress?,
        phase: ClearPhase,
        reset: Boolean
    ): Boolean = !reset && previous?.phase == phase

    private fun completedSteps(previous: ClearProgress?, requested: Int): Int =
        maxOf(previous?.completedSteps ?: 0, requested.coerceIn(0, DOWNLOAD_CLEAR_PHASE_COUNT))

    private fun affectedItems(previous: ClearProgress?, requested: Int): Int =
        maxOf(previous?.affectedItemCount ?: 0, requested.coerceAtLeast(0))

    private fun totalItems(previous: ClearProgress?, requested: Int?, keep: Boolean): Int {
        val previousCount = previous?.totalItemCount ?: 0
        val requestedCount = (requested ?: previousCount).coerceAtLeast(0)
        return watermark(previousCount, requestedCount, keep)
    }

    private fun completedItems(previous: ClearProgress?, requested: Int?, keep: Boolean, total: Int): Int {
        val previousCount = previous?.completedItemCount ?: 0
        val requestedCount = (requested ?: previousCount).coerceAtLeast(0)
        val completed = watermark(previousCount, requestedCount, keep)
        return if (total > 0) completed.coerceAtMost(total) else completed
    }

    // 不完整的 Provider 扫描保留旧分母，完整扫描可以收敛到真实残留
    private fun watermark(previous: Int, requested: Int, keep: Boolean): Int =
        if (keep) maxOf(previous, requested) else requested
}
