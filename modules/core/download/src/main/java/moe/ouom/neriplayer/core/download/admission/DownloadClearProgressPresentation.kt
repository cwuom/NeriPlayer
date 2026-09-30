package moe.ouom.neriplayer.core.download.admission

import moe.ouom.neriplayer.core.download.admission.DownloadClearVisibility.ClearPhase
import moe.ouom.neriplayer.core.download.admission.DownloadClearVisibility.ClearProgress

internal object DownloadClearProgressPresentation {
    private const val CANCELLING_PERCENT = 5
    private const val CLEANING_BASE_PERCENT = 10
    private const val CLEANING_SPAN_PERCENT = 80
    private const val PURGING_PERCENT = 95

    fun percentage(progress: ClearProgress): Int = when (progress.phase) {
        ClearPhase.PREPARING -> 0
        ClearPhase.CANCELLING -> CANCELLING_PERCENT
        ClearPhase.CLEANING -> cleaningPercentage(progress)
        ClearPhase.PURGING -> purgingPercentage(progress)
    }

    private fun cleaningPercentage(progress: ClearProgress): Int {
        val itemPercent = if (progress.totalItemCount <= 0) 0
            else (progress.itemFraction * CLEANING_SPAN_PERCENT).toInt()
        return (CLEANING_BASE_PERCENT + itemPercent).coerceAtMost(PURGING_PERCENT - 1)
    }

    private fun purgingPercentage(progress: ClearProgress): Int {
        val itemsComplete = progress.totalItemCount <= 0 ||
            progress.completedItemCount >= progress.totalItemCount
        return if (progress.completedSteps >= progress.totalSteps && itemsComplete) 100 else PURGING_PERCENT
    }
}
