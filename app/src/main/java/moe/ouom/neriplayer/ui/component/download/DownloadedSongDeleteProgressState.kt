package moe.ouom.neriplayer.ui.component.download

import moe.ouom.neriplayer.core.download.model.DownloadedSongDeletePhase
import moe.ouom.neriplayer.core.download.model.DownloadedSongDeleteProgress

internal fun isDownloadedSongDeletionRunning(progress: DownloadedSongDeleteProgress?): Boolean {
    return progress != null &&
        progress.phase != DownloadedSongDeletePhase.COMPLETED &&
        progress.phase != DownloadedSongDeletePhase.FAILED
}

internal fun shouldShowDownloadedSongDeleteProgress(
    progress: DownloadedSongDeleteProgress?,
    requestedSongCount: Int
): Boolean {
    return requestedSongCount > 0 ||
        isDownloadedSongDeletionRunning(progress) ||
        progress?.phase == DownloadedSongDeletePhase.FAILED
}

internal fun downloadedSongDeleteProgressFraction(progress: DownloadedSongDeleteProgress): Float? {
    if (progress.phase != DownloadedSongDeletePhase.DELETING_REFERENCES) return null
    val total = progress.totalReferenceCount?.takeIf { it > 0 } ?: return null
    return (progress.completedReferenceCount.toFloat() / total).coerceIn(0f, 1f)
}
