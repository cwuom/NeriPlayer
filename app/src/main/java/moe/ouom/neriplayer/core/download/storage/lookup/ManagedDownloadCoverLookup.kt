package moe.ouom.neriplayer.core.download.storage.lookup

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.candidateManagedDownloadBaseNames
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming

internal object ManagedDownloadCoverLookup {
    fun findCoverReference(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        audio: ManagedDownloadStorage.StoredEntry
    ): String? {
        ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)?.let { metadata ->
            return resolveMetadataCoverReference(
                snapshot = snapshot,
                audioName = audio.name,
                metadata = metadata
            )
        }
        return findCoverByAudioBaseName(snapshot, audio.nameWithoutExtension)
    }

    fun resolveMetadataCoverReference(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        audioName: String,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata
    ): String? {
        metadata.coverPath
            ?.takeIf(snapshot.knownReferences::contains)
            ?.let { return it }
        val baseName = audioName.substringBeforeLast('.', audioName)
        val stableKey = metadata.stableKey
            ?.takeIf(String::isNotBlank)
        if (stableKey != null) {
            findIndexedEntryByNames(
                names = ManagedDownloadStorageNaming.buildStableCoverCandidateNames(baseName, stableKey),
                entriesByName = snapshot.coverEntriesByName
            )?.reference?.let { return it }
            return findCoverByAudioBaseName(
                snapshot = snapshot,
                baseName = baseName,
                allowNumberedNameFallback = false
            )
        }
        return findCoverByAudioBaseName(snapshot, baseName)
    }

    private fun findCoverByAudioBaseName(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        baseName: String,
        allowNumberedNameFallback: Boolean = true
    ): String? {
        val candidateBaseNames = if (allowNumberedNameFallback) {
            candidateManagedDownloadBaseNames(baseName)
        } else {
            listOf(baseName)
        }
        return findIndexedEntryByNames(
            names = ManagedDownloadStorageNaming.buildSidecarCandidateNames(
                candidateBaseNames
            ),
            entriesByName = snapshot.coverEntriesByName
        )?.reference
    }

    private fun findIndexedEntryByNames(
        names: List<String>,
        entriesByName: Map<String, ManagedDownloadStorage.StoredEntry>
    ): ManagedDownloadStorage.StoredEntry? {
        return ManagedDownloadStorageLookup.findIndexedEntryByNames(names, entriesByName)
    }
}
