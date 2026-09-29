package moe.ouom.neriplayer.data.model.local

import moe.ouom.neriplayer.data.model.SongItem

enum class LocalAudioScanPhase {
    PREPARING,
    READING_DOWNLOAD_INDEX,
    QUERYING_MEDIA_STORE,
    TRAVERSING,
    BUILDING_ENTRIES,
    HYDRATING_METADATA,
    COMPLETED
}

data class LocalAudioScanProgress(
    val scanId: Long = 0L,
    val phase: LocalAudioScanPhase = LocalAudioScanPhase.PREPARING,
    val processed: Int = 0,
    val total: Int = 0,
    val discoveredSongs: Int = 0,
    val visitedDirectories: Int = 0,
    val elapsedMs: Long = 0L,
    val phaseElapsedMs: Long = 0L,
    val waitingForProvider: Boolean = false
) {
    val fraction: Float?
        get() = total.takeIf { it > 0 }?.let {
            (processed.toFloat() / it).coerceIn(0f, 1f)
        }
}

data class LocalAudioImportResult(
    val songs: List<SongItem>,
    val failedCount: Int,
    val completed: Boolean = true,
    val metadataDeferred: Boolean = false
)
