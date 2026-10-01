package moe.ouom.neriplayer.core.download.artifact

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage

internal fun artifactReconciliationAudioEntries(
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
): List<ManagedDownloadStorage.StoredEntry> {
    return (
        snapshot.audioEntries +
            snapshot.audioEntriesWithoutMetadata +
            snapshot.pendingAudioEntries
        )
        .distinctBy(ManagedDownloadStorage.StoredEntry::reference)
}

internal fun resolveDiscoveredManagedArtifactState(
    finalized: Boolean,
    metadataArtifactState: String?
): ManagedDownloadArtifactState {
    if (finalized) {
        return ManagedDownloadArtifactState.FINALIZED
    }
    val persistedState = metadataArtifactState
        ?.trim()
        ?.let { raw ->
            ManagedDownloadArtifactState.entries.firstOrNull { state ->
                state.name.equals(raw, ignoreCase = true)
            }
        }
    return when (persistedState) {
        ManagedDownloadArtifactState.CORE_COMMITTED ->
            ManagedDownloadArtifactState.CORE_COMMITTED
        ManagedDownloadArtifactState.ASSETS_ENRICHING ->
            ManagedDownloadArtifactState.ASSETS_ENRICHING
        ManagedDownloadArtifactState.DEGRADED_COMPLETE ->
            ManagedDownloadArtifactState.DEGRADED_COMPLETE
        else -> ManagedDownloadArtifactState.REPAIR_REQUIRED
    }
}

internal fun resolveCatalogArtifactState(
    currentState: ManagedDownloadArtifactState?,
    hasAudioReference: Boolean
): ManagedDownloadArtifactState {
    if (currentState == null) {
        return if (hasAudioReference) {
            ManagedDownloadArtifactState.FINALIZED
        } else {
            ManagedDownloadArtifactState.MISSING_CONFIRMED
        }
    }
    return if (
        currentState == ManagedDownloadArtifactState.FINALIZED &&
            !hasAudioReference
    ) {
        ManagedDownloadArtifactState.MISSING_CONFIRMED
    } else {
        currentState
    }
}
