package moe.ouom.neriplayer.core.download

import android.content.Context
import com.kyant.taglib.PropertyMap
import moe.ouom.neriplayer.core.download.catalog.buildDownloadedSongCatalogIndex as buildDownloadedSongCatalogIndexDelegate
import moe.ouom.neriplayer.core.download.catalog.findDownloadedSongCatalogMatch as findDownloadedSongCatalogMatchDelegate
import moe.ouom.neriplayer.core.download.catalog.matchesDownloadedSong as matchesDownloadedSongDelegate
import moe.ouom.neriplayer.core.download.catalog.matchesDownloadedSongCatalogEntry as matchesDownloadedSongCatalogEntryDelegate
import moe.ouom.neriplayer.core.download.catalog.resolveDownloadedSongPlaybackReference as resolveDownloadedSongPlaybackReferenceDelegate
import moe.ouom.neriplayer.core.download.catalog.shouldPublishDownloadedSongCatalogUpdate as shouldPublishDownloadedSongCatalogUpdateDelegate
import moe.ouom.neriplayer.core.download.catalog.upsertDownloadedSongCatalog as upsertDownloadedSongCatalogDelegate
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadArtifactPlanner as ManagedDownloadArtifactPlannerDelegate
import moe.ouom.neriplayer.core.download.cleanup.groupRemainingManagedReferencesByIdentity as groupRemainingManagedReferencesByIdentityDelegate
import moe.ouom.neriplayer.core.download.cleanup.mergeManagedRequestedReferences as mergeManagedRequestedReferencesDelegate
import moe.ouom.neriplayer.core.download.cleanup.resolveUndeletedManagedReferences as resolveUndeletedManagedReferencesDelegate
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriter as DownloadedAudioTagWriterDelegate
import moe.ouom.neriplayer.core.download.naming.candidateManagedDownloadBaseNames as candidateManagedDownloadBaseNamesDelegate
import moe.ouom.neriplayer.core.download.naming.candidateManagedDownloadFileNameTemplates as candidateManagedDownloadFileNameTemplatesDelegate
import moe.ouom.neriplayer.core.download.naming.normalizeDownloadFileNameTemplate as normalizeDownloadFileNameTemplateDelegate
import moe.ouom.neriplayer.core.download.naming.parseManagedDownloadBaseName as parseManagedDownloadBaseNameDelegate
import moe.ouom.neriplayer.core.download.naming.renderManagedDownloadBaseName as renderManagedDownloadBaseNameDelegate
import moe.ouom.neriplayer.core.download.naming.sanitizeManagedDownloadFileName as sanitizeManagedDownloadFileNameDelegate
import moe.ouom.neriplayer.core.download.policy.buildExpectedDownloadArtists as buildExpectedDownloadArtistsDelegate
import moe.ouom.neriplayer.core.download.policy.buildExpectedDownloadTitles as buildExpectedDownloadTitlesDelegate
import moe.ouom.neriplayer.core.download.policy.isUnfinalizedDownloadedMetadata as isUnfinalizedDownloadedMetadataDelegate
import moe.ouom.neriplayer.core.download.policy.resolveCompletedDownloadFinalizationAction as resolveCompletedDownloadFinalizationActionDelegate
import moe.ouom.neriplayer.core.download.policy.resolveDownloadedLyricOverride as resolveDownloadedLyricOverrideDelegate
import moe.ouom.neriplayer.core.download.policy.resolveDownloadedPlaybackHydrationDelayMs as resolveDownloadedPlaybackHydrationDelayMsDelegate
import moe.ouom.neriplayer.core.download.policy.resolvePreExistingDownloadedAudioAction as resolvePreExistingDownloadedAudioActionDelegate
import moe.ouom.neriplayer.core.download.policy.runNonCancellableDownloadRollback as runNonCancellableDownloadRollbackDelegate
import moe.ouom.neriplayer.core.download.policy.shouldRepairMetadataLessManagedDownload as shouldRepairMetadataLessManagedDownloadDelegate
import moe.ouom.neriplayer.core.download.policy.shouldDeferPendingDownloadRecoveryForNetwork as shouldDeferPendingDownloadRecoveryForNetworkDelegate
import moe.ouom.neriplayer.core.download.policy.shouldDeferPreparedDownloadStartForNetwork as shouldDeferPreparedDownloadStartForNetworkDelegate
import moe.ouom.neriplayer.core.download.policy.shouldDeferStartupManagedCleanup as shouldDeferStartupManagedCleanupDelegate
import moe.ouom.neriplayer.core.download.policy.shouldInspectDownloadedAudioDetails as shouldInspectDownloadedAudioDetailsDelegate
import moe.ouom.neriplayer.core.download.policy.shouldKeepCancellationCleanup as shouldKeepCancellationCleanupDelegate
import moe.ouom.neriplayer.core.download.policy.shouldProbeCompletedAudioAccessDuringPostProcessing as shouldProbeCompletedAudioAccessDuringPostProcessingDelegate
import moe.ouom.neriplayer.core.download.policy.shouldRunInitialDownloadScan as shouldRunInitialDownloadScanDelegate
import moe.ouom.neriplayer.core.download.policy.shouldSkipCancelledArtifactRecovery as shouldSkipCancelledArtifactRecoveryDelegate
import moe.ouom.neriplayer.core.download.policy.shouldTrustFastDownloadedSongCatalogHit as shouldTrustFastDownloadedSongCatalogHitDelegate
import moe.ouom.neriplayer.core.download.policy.shouldUseImmediateDownloadedPlaybackHydration as shouldUseImmediateDownloadedPlaybackHydrationDelegate
import moe.ouom.neriplayer.core.download.policy.shouldUseIndexedSidecarLookup as shouldUseIndexedSidecarLookupDelegate
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal typealias DownloadedSongCatalogIndex = moe.ouom.neriplayer.core.download.catalog.DownloadedSongCatalogIndex
internal typealias DownloadedSongCatalogStore = moe.ouom.neriplayer.core.download.catalog.DownloadedSongCatalogStore
internal typealias DownloadedSongBuilder = moe.ouom.neriplayer.core.download.catalog.DownloadedSongBuilder
internal typealias ManagedDownloadArtifactRemovalResult =
    moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadArtifactRemovalResult
internal typealias ManagedDownloadSongDeletePlan = moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadSongDeletePlan
internal typealias ManagedDownloadDeletePlanner = moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadDeletePlanner
internal typealias DownloadRequestGenerationTracker =
    moe.ouom.neriplayer.core.download.generation.DownloadRequestGenerationTracker
internal typealias DownloadRequestGenerationSnapshot =
    moe.ouom.neriplayer.core.download.generation.DownloadRequestGenerationSnapshot
internal typealias DownloadedAudioMetadataStore =
    moe.ouom.neriplayer.core.download.metadata.DownloadedAudioMetadataStore
internal typealias ParsedManagedDownloadFileName =
    moe.ouom.neriplayer.core.download.naming.ParsedManagedDownloadFileName
internal typealias CompletedDownloadFinalizationAction =
    moe.ouom.neriplayer.core.download.policy.CompletedDownloadFinalizationAction
internal typealias PreExistingDownloadedAudioAction =
    moe.ouom.neriplayer.core.download.policy.PreExistingDownloadedAudioAction
internal typealias PendingDownloadRecoveryCandidate =
    moe.ouom.neriplayer.core.download.policy.PendingDownloadRecoveryCandidate
internal typealias DownloadTaskStore = moe.ouom.neriplayer.core.download.task.DownloadTaskStore

internal const val DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE =
    moe.ouom.neriplayer.core.download.naming.DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
internal const val LEGACY_DOWNLOAD_FILE_NAME_TEMPLATE =
    moe.ouom.neriplayer.core.download.naming.LEGACY_DOWNLOAD_FILE_NAME_TEMPLATE

internal fun sanitizeManagedDownloadFileName(name: String): String =
    sanitizeManagedDownloadFileNameDelegate(name)

internal fun normalizeDownloadFileNameTemplate(template: String?): String? =
    normalizeDownloadFileNameTemplateDelegate(template)

internal fun candidateManagedDownloadFileNameTemplates(activeTemplate: String? = null): List<String> =
    candidateManagedDownloadFileNameTemplatesDelegate(activeTemplate)

internal fun renderManagedDownloadBaseName(
    title: String,
    artist: String,
    album: String,
    source: String = "",
    songId: String = "",
    audioId: String = "",
    subAudioId: String = "",
    template: String? = DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
): String = renderManagedDownloadBaseNameDelegate(title, artist, album, source, songId, audioId, subAudioId, template)

internal fun renderManagedDownloadBaseName(
    song: SongItem,
    template: String? = DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
): String = renderManagedDownloadBaseNameDelegate(song, template)

internal fun parseManagedDownloadBaseName(
    baseName: String,
    template: String? = DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
): ParsedManagedDownloadFileName? = parseManagedDownloadBaseNameDelegate(baseName, template)

internal fun candidateManagedDownloadBaseNames(
    song: SongItem,
    activeTemplate: String? = null
): List<String> = candidateManagedDownloadBaseNamesDelegate(song, activeTemplate)

internal fun candidateManagedDownloadBaseNames(fileNameWithoutExtension: String): List<String> =
    candidateManagedDownloadBaseNamesDelegate(fileNameWithoutExtension)

internal fun shouldRunInitialDownloadScan(catalogReady: Boolean, hasRecoveredEntries: Boolean = false): Boolean =
    shouldRunInitialDownloadScanDelegate(catalogReady, hasRecoveredEntries)

internal fun shouldDeferStartupManagedCleanup(configuredDirectoryUri: String?, treeRootAvailable: Boolean): Boolean =
    shouldDeferStartupManagedCleanupDelegate(configuredDirectoryUri, treeRootAvailable)

internal fun shouldDeferPendingDownloadRecoveryForNetwork(
    networkType: TrafficNetworkType,
    mobileDataOverrideAllowed: Boolean
): Boolean = shouldDeferPendingDownloadRecoveryForNetworkDelegate(networkType, mobileDataOverrideAllowed)

internal fun shouldDeferPreparedDownloadStartForNetwork(
    networkType: TrafficNetworkType,
    mobileDataOverrideAllowed: Boolean,
    deferForNetworkPolicy: Boolean
): Boolean = shouldDeferPreparedDownloadStartForNetworkDelegate(
    networkType,
    mobileDataOverrideAllowed,
    deferForNetworkPolicy
)

internal fun shouldKeepCancellationCleanup(
    currentGeneration: Long?,
    cancellationGeneration: Long?,
    cancelled: Boolean
): Boolean = shouldKeepCancellationCleanupDelegate(currentGeneration, cancellationGeneration, cancelled)

internal fun resolveCompletedDownloadFinalizationAction(
    hasStoredAudio: Boolean,
    cancelled: Boolean
): CompletedDownloadFinalizationAction =
    resolveCompletedDownloadFinalizationActionDelegate(hasStoredAudio, cancelled)

internal fun resolvePreExistingDownloadedAudioAction(hasExistingAudio: Boolean): PreExistingDownloadedAudioAction =
    resolvePreExistingDownloadedAudioActionDelegate(hasExistingAudio)

internal fun shouldUseImmediateDownloadedPlaybackHydration(
    originalSong: SongItem,
    hydratedSong: SongItem
): Boolean = shouldUseImmediateDownloadedPlaybackHydrationDelegate(originalSong, hydratedSong)

internal fun resolveDownloadedPlaybackHydrationDelayMs(
    originalSong: SongItem,
    hydratedSong: SongItem
): Long = resolveDownloadedPlaybackHydrationDelayMsDelegate(originalSong, hydratedSong)

internal suspend fun <T> runNonCancellableDownloadRollback(block: suspend () -> T): T =
    runNonCancellableDownloadRollbackDelegate(block)

internal fun shouldInspectDownloadedAudioDetails(
    allowSlowLocalInspection: Boolean,
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
    coverReference: String?,
    needsLocalLyricFallback: Boolean
): Boolean = shouldInspectDownloadedAudioDetailsDelegate(
    allowSlowLocalInspection,
    metadata,
    coverReference,
    needsLocalLyricFallback
)

internal fun isUnfinalizedDownloadedMetadata(metadata: ManagedDownloadStorage.DownloadedAudioMetadata?): Boolean =
    isUnfinalizedDownloadedMetadataDelegate(metadata)

internal fun resolveDownloadedLyricOverride(
    fileLyric: String?,
    embeddedMatchedLyric: String?,
    embeddedOriginalLyric: String?,
    localLyricContent: String?,
    indexedLyricContent: String?
): String? = resolveDownloadedLyricOverrideDelegate(
    fileLyric,
    embeddedMatchedLyric,
    embeddedOriginalLyric,
    localLyricContent,
    indexedLyricContent
)

internal fun shouldTrustFastDownloadedSongCatalogHit(reference: String?, knownReferences: Set<String>?): Boolean =
    shouldTrustFastDownloadedSongCatalogHitDelegate(reference, knownReferences)

internal fun shouldProbeCompletedAudioAccessDuringPostProcessing(
    reference: String?,
    fastPathTrusted: Boolean
): Boolean = shouldProbeCompletedAudioAccessDuringPostProcessingDelegate(reference, fastPathTrusted)

internal fun shouldUseIndexedSidecarLookup(
    usesDocumentTree: Boolean,
    allowSlowLookup: Boolean
): Boolean = shouldUseIndexedSidecarLookupDelegate(usesDocumentTree, allowSlowLookup)

internal fun shouldSkipCancelledArtifactRecovery(
    downloadActive: Boolean,
    taskStatus: DownloadStatus?
): Boolean = shouldSkipCancelledArtifactRecoveryDelegate(downloadActive, taskStatus)

internal fun shouldRepairMetadataLessManagedDownload(
    expectedTitles: Collection<String>,
    expectedArtists: Collection<String>,
    expectedDurationMs: Long,
    actualTitle: String?,
    actualArtist: String?,
    actualDurationMs: Long
): Boolean = shouldRepairMetadataLessManagedDownloadDelegate(
    expectedTitles,
    expectedArtists,
    expectedDurationMs,
    actualTitle,
    actualArtist,
    actualDurationMs
)

internal fun buildExpectedDownloadTitles(song: SongItem): Set<String> = buildExpectedDownloadTitlesDelegate(song)

internal fun buildExpectedDownloadArtists(song: SongItem): Set<String> = buildExpectedDownloadArtistsDelegate(song)

internal fun buildDownloadedSongCatalogIndex(songs: List<DownloadedSong>): DownloadedSongCatalogIndex =
    buildDownloadedSongCatalogIndexDelegate(songs)

internal fun matchesDownloadedSongCatalogEntry(existing: DownloadedSong, target: DownloadedSong): Boolean =
    matchesDownloadedSongCatalogEntryDelegate(existing, target)

internal fun matchesDownloadedSong(song: SongItem, downloadedSong: DownloadedSong): Boolean =
    matchesDownloadedSongDelegate(song, downloadedSong)

internal fun findDownloadedSongCatalogMatch(
    song: SongItem,
    downloadedSongs: List<DownloadedSong>
): DownloadedSong? = findDownloadedSongCatalogMatchDelegate(song, downloadedSongs)

internal fun resolveDownloadedSongPlaybackReference(song: DownloadedSong): String? =
    resolveDownloadedSongPlaybackReferenceDelegate(song)

internal fun shouldPublishDownloadedSongCatalogUpdate(
    currentSong: DownloadedSong,
    updatedSong: DownloadedSong
): Boolean = shouldPublishDownloadedSongCatalogUpdateDelegate(currentSong, updatedSong)

fun upsertDownloadedSongCatalog(currentSongs: List<DownloadedSong>, updatedSong: DownloadedSong): List<DownloadedSong> =
    upsertDownloadedSongCatalogDelegate(currentSongs, updatedSong)

internal fun mergePendingDownloadRecoveryCandidates(
    queuedDownloads: List<ManagedDownloadStorage.PendingDownloadQueueEntry>,
    resumableDownloads: List<ManagedDownloadStorage.PendingResumableDownload>,
    cancelledKeys: Set<String> = emptySet()
): List<PendingDownloadRecoveryCandidate> =
    moe.ouom.neriplayer.core.download.policy.mergePendingDownloadRecoveryCandidates(
        queuedDownloads,
        resumableDownloads,
        cancelledKeys
    )

internal fun mergeManagedRequestedReferences(
    requestedReferenceGroups: Collection<Set<String>>
): Set<String> = mergeManagedRequestedReferencesDelegate(requestedReferenceGroups)

internal fun groupRemainingManagedReferencesByIdentity(
    requestedReferencesByIdentity: Map<String, Set<String>>,
    remainingReferences: Set<String>
): Map<String, Set<String>> =
    groupRemainingManagedReferencesByIdentityDelegate(requestedReferencesByIdentity, remainingReferences)

internal suspend fun resolveUndeletedManagedReferences(
    requestedReferences: Set<String>,
    deletedReferences: Set<String>,
    exists: suspend (String) -> Boolean
): Set<String> = resolveUndeletedManagedReferencesDelegate(requestedReferences, deletedReferences, exists)

internal object ManagedDownloadArtifactPlanner {
    fun collectArtifactReferences(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        storedAudio: ManagedDownloadStorage.StoredEntry?,
        songId: Long,
        candidateBaseNames: List<String>,
        explicitReferences: List<String> = emptyList(),
        deletingAudioNames: Set<String> = emptySet()
    ): Set<String> = ManagedDownloadArtifactPlannerDelegate.collectArtifactReferences(
        snapshot,
        storedAudio,
        songId,
        candidateBaseNames,
        explicitReferences,
        deletingAudioNames
    )

    fun trustedMetadataReference(
        reference: String?,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? = ManagedDownloadArtifactPlannerDelegate.trustedMetadataReference(reference, snapshot)

    fun indexedLyricReference(
        audio: ManagedDownloadStorage.StoredEntry,
        songId: Long?,
        translated: Boolean,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? = ManagedDownloadArtifactPlannerDelegate.indexedLyricReference(audio, songId, translated, snapshot)

    suspend fun indexedLyricText(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        songId: Long?,
        translated: Boolean,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? = ManagedDownloadArtifactPlannerDelegate.indexedLyricText(context, audio, songId, translated, snapshot)

    fun indexedCoverReference(
        audio: ManagedDownloadStorage.StoredEntry,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? = ManagedDownloadArtifactPlannerDelegate.indexedCoverReference(audio, snapshot)

    fun indexedCoverReference(
        candidateBaseNames: List<String>,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? = ManagedDownloadArtifactPlannerDelegate.indexedCoverReference(candidateBaseNames, snapshot)
}

internal object DownloadedAudioTagWriter {
    suspend fun write(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        standardizedLyricEmbeddingEnabled: Boolean
    ): Boolean = DownloadedAudioTagWriterDelegate.write(
        context,
        audio,
        song,
        sidecarReferences,
        standardizedLyricEmbeddingEnabled
    )

    fun normalizeEmbeddedAlbumName(album: String): String? =
        DownloadedAudioTagWriterDelegate.normalizeEmbeddedAlbumName(album)

    fun normalizeLyricForEmbedding(lyric: String?, enabled: Boolean): String? =
        DownloadedAudioTagWriterDelegate.normalizeLyricForEmbedding(lyric, enabled)

    fun hasRequiredEmbeddedMetadata(propertyMap: PropertyMap, song: SongItem): Boolean =
        DownloadedAudioTagWriterDelegate.hasRequiredEmbeddedMetadata(propertyMap, song)
}
