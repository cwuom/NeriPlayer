package moe.ouom.neriplayer.core.download.storage.lookup

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.candidateManagedDownloadBaseNames
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal object ManagedDownloadCoverLookup {
    fun findCoverReference(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        audio: ManagedDownloadStorage.StoredEntry
    ): String? {
        snapshot.metadataByAudioName[audio.name]?.let { metadata ->
            resolveMetadataCoverReference(
                snapshot = snapshot,
                audioName = audio.name,
                metadata = metadata
            )?.let { return it }
        }
        return findCoverByAudioBaseName(snapshot, audio.nameWithoutExtension)
    }

    fun findReusableCoverReference(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        song: SongItem,
        excludedAudioName: String? = null
    ): String? {
        val remoteCoverKeys = linkedSetOf<String>().apply {
            song.customCoverUrl?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            song.coverUrl?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            song.originalCoverUrl?.trim()?.takeIf(String::isNotBlank)?.let(::add)
        }
        val identityAlbum = song.identity().album.takeIf(String::isNotBlank)
        val allowAlbumFallback = remoteCoverKeys.isEmpty()

        return snapshot.metadataByAudioName.asSequence()
            .filter { (audioName, _) -> audioName != excludedAudioName }
            .mapNotNull { (audioName, metadata) ->
                val resolvedCoverReference = resolveMetadataCoverReference(
                    snapshot = snapshot,
                    audioName = audioName,
                    metadata = metadata
                ) ?: return@mapNotNull null
                val remoteMatch = remoteCoverKeys.isNotEmpty() &&
                    listOfNotNull(
                        metadata.customCoverUrl?.takeIf(String::isNotBlank),
                        metadata.coverUrl?.takeIf(String::isNotBlank),
                        metadata.originalCoverUrl?.takeIf(String::isNotBlank)
                    ).any(remoteCoverKeys::contains)
                val albumMatch = allowAlbumFallback &&
                    !identityAlbum.isNullOrBlank() &&
                    metadata.customCoverUrl.isNullOrBlank() &&
                    metadata.identityAlbum == identityAlbum
                when {
                    remoteMatch -> 2 to resolvedCoverReference
                    albumMatch -> 1 to resolvedCoverReference
                    else -> null
                }
            }
            .maxByOrNull { it.first }
            ?.second
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
        metadata.stableKey
            ?.takeIf(String::isNotBlank)
            ?.let { stableKey ->
                findIndexedEntryByNames(
                    names = ManagedDownloadStorageNaming.buildStableCoverCandidateNames(baseName, stableKey),
                    entriesByName = snapshot.coverEntriesByName
                )?.reference
            }
            ?.let { return it }
        return findCoverByAudioBaseName(snapshot, baseName)
    }

    private fun findCoverByAudioBaseName(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        baseName: String
    ): String? {
        return findIndexedEntryByNames(
            names = ManagedDownloadStorageNaming.buildSidecarCandidateNames(
                candidateManagedDownloadBaseNames(baseName)
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
