package moe.ouom.neriplayer.core.download

import kotlin.math.abs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal fun shouldRunInitialDownloadScan(
    catalogReady: Boolean,
    hasRecoveredEntries: Boolean = false
): Boolean {
    return hasRecoveredEntries || !catalogReady
}

internal fun shouldDeferStartupManagedCleanup(
    configuredDirectoryUri: String?,
    treeRootAvailable: Boolean
): Boolean {
    return !configuredDirectoryUri.isNullOrBlank() && treeRootAvailable
}

internal fun shouldKeepCancellationCleanup(
    currentGeneration: Long?,
    cancellationGeneration: Long?,
    cancelled: Boolean
): Boolean {
    if (currentGeneration == cancellationGeneration) {
        return true
    }
    return currentGeneration == null && cancelled
}

internal enum class CompletedDownloadFinalizationAction {
    COMPLETE,
    COMPLETE_WITHOUT_STORED_AUDIO,
    ROLLBACK_CANCELLED
}

internal fun resolveCompletedDownloadFinalizationAction(
    hasStoredAudio: Boolean,
    cancelled: Boolean
): CompletedDownloadFinalizationAction {
    return when {
        cancelled -> CompletedDownloadFinalizationAction.ROLLBACK_CANCELLED
        !hasStoredAudio -> CompletedDownloadFinalizationAction.COMPLETE_WITHOUT_STORED_AUDIO
        else -> CompletedDownloadFinalizationAction.COMPLETE
    }
}

internal fun shouldUseImmediateDownloadedPlaybackHydration(
    originalSong: SongItem,
    hydratedSong: SongItem
): Boolean {
    return originalSong.name != hydratedSong.name ||
        originalSong.artist != hydratedSong.artist ||
        originalSong.durationMs != hydratedSong.durationMs ||
        originalSong.coverUrl != hydratedSong.coverUrl ||
        originalSong.customCoverUrl != hydratedSong.customCoverUrl ||
        originalSong.customName != hydratedSong.customName ||
        originalSong.customArtist != hydratedSong.customArtist ||
        originalSong.mediaUri != hydratedSong.mediaUri ||
        originalSong.localFilePath != hydratedSong.localFilePath ||
        originalSong.localFileName != hydratedSong.localFileName
}

internal fun resolveDownloadedPlaybackHydrationDelayMs(
    originalSong: SongItem,
    hydratedSong: SongItem
): Long {
    return if (shouldUseImmediateDownloadedPlaybackHydration(originalSong, hydratedSong)) {
        GlobalDownloadManager.PLAYBACK_METADATA_HYDRATION_DELAY_MS
    } else {
        GlobalDownloadManager.LOCAL_PLAYBACK_METADATA_HYDRATION_DELAY_MS
    }
}

internal suspend fun <T> runNonCancellableDownloadRollback(
    block: suspend () -> T
): T = withContext(NonCancellable) {
    block()
}

internal fun shouldInspectDownloadedAudioDetails(
    allowSlowLocalInspection: Boolean,
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
    coverReference: String?,
    needsLocalLyricFallback: Boolean
): Boolean {
    if (!allowSlowLocalInspection) return false
    if (needsLocalLyricFallback) return true
    return metadata == null ||
        metadata.name.isNullOrBlank() ||
        metadata.artist.isNullOrBlank() ||
        metadata.originalName.isNullOrBlank() ||
        metadata.originalArtist.isNullOrBlank() ||
        metadata.durationMs <= 0L ||
        coverReference.isNullOrBlank()
}

internal fun isUnfinalizedDownloadedMetadata(
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
): Boolean {
    return metadata?.downloadFinalized == false
}

internal fun resolveDownloadedLyricContent(
    fileLyric: String?,
    embeddedMatchedLyric: String?,
    embeddedOriginalLyric: String?,
    localLyricContent: String?,
    indexedLyricContent: String?
): String? {
    return fileLyric?.takeIf(String::isNotBlank)
        ?: embeddedMatchedLyric?.takeIf(String::isNotBlank)
        ?: embeddedOriginalLyric?.takeIf(String::isNotBlank)
        ?: localLyricContent?.takeIf(String::isNotBlank)
        ?: indexedLyricContent?.takeIf(String::isNotBlank)
}

internal fun resolveDownloadedLyricOverride(
    fileLyric: String?,
    embeddedMatchedLyric: String?,
    embeddedOriginalLyric: String?,
    localLyricContent: String?,
    indexedLyricContent: String?
): String? {
    if (!fileLyric.isNullOrBlank()) {
        return fileLyric
    }
    if (embeddedMatchedLyric != null) {
        return embeddedMatchedLyric
    }
    if (embeddedOriginalLyric != null) {
        return embeddedOriginalLyric
    }
    return resolveDownloadedLyricContent(
        fileLyric = null,
        embeddedMatchedLyric = null,
        embeddedOriginalLyric = null,
        localLyricContent = localLyricContent,
        indexedLyricContent = indexedLyricContent
    )
}

internal fun shouldRepairMetadataLessManagedDownload(
    expectedTitles: Collection<String>,
    expectedArtists: Collection<String>,
    expectedDurationMs: Long,
    actualTitle: String?,
    actualArtist: String?,
    actualDurationMs: Long
): Boolean {
    val normalizedExpectedTitles = expectedTitles
        .map(::normalizeManagedDownloadText)
        .filter(String::isNotBlank)
        .toSet()
    val normalizedExpectedArtists = expectedArtists
        .map(::normalizeManagedDownloadText)
        .filter(String::isNotBlank)
        .toSet()
    val normalizedActualTitle = normalizeManagedDownloadText(actualTitle)
    val normalizedActualArtist = normalizeManagedDownloadText(actualArtist)

    if (
        normalizedExpectedTitles.isEmpty() ||
        normalizedExpectedArtists.isEmpty() ||
        normalizedActualTitle.isBlank() ||
        normalizedActualArtist.isBlank()
    ) {
        return true
    }
    if (actualDurationMs <= 0L) {
        return true
    }
    if (
        expectedDurationMs > 0L &&
        abs(expectedDurationMs - actualDurationMs) > 5_000L
    ) {
        return true
    }
    return normalizedActualTitle !in normalizedExpectedTitles ||
        normalizedActualArtist !in normalizedExpectedArtists
}

internal fun shouldTrustFastDownloadedSongCatalogHit(
    reference: String?,
    cachedKnownReferences: Set<String>?
): Boolean {
    val normalizedReference = reference?.takeIf(String::isNotBlank) ?: return false
    return cachedKnownReferences == null || normalizedReference in cachedKnownReferences
}

internal fun shouldSkipCancelledArtifactRecovery(
    downloadActive: Boolean,
    taskStatus: DownloadStatus?
): Boolean {
    if (downloadActive) {
        return true
    }
    return taskStatus == DownloadStatus.QUEUED ||
        taskStatus == DownloadStatus.DOWNLOADING ||
        taskStatus == DownloadStatus.WAITING_NETWORK
}

internal fun buildExpectedDownloadTitles(song: SongItem): Set<String> {
    return linkedSetOf<String>().apply {
        add(song.customName ?: song.name)
        add(song.name)
        song.originalName?.takeIf(String::isNotBlank)?.let(::add)
    }
}

internal fun buildExpectedDownloadArtists(song: SongItem): Set<String> {
    return linkedSetOf<String>().apply {
        add(song.customArtist ?: song.artist)
        add(song.artist)
        song.originalArtist?.takeIf(String::isNotBlank)?.let(::add)
    }
}

private fun normalizeManagedDownloadText(value: String?): String {
    return value
        ?.trim()
        ?.lowercase()
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()
}
