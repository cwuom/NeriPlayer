package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming

internal object ManagedDownloadMigrationTargetIndexBuilder {
    fun build(
        rootEntries: List<ManagedDownloadStorage.StoredEntry>,
        coverEntries: List<ManagedDownloadStorage.StoredEntry>,
        lyricEntries: List<ManagedDownloadStorage.StoredEntry>,
        readText: ((ManagedDownloadStorage.StoredEntry) -> String?)? = null,
        parseMetadata: ((String) -> ManagedDownloadStorage.DownloadedAudioMetadata?)? = null
    ): ManagedMigrationTargetIndex {
        val rootEntriesByName = rootEntries
            .associateBy(ManagedDownloadStorage.StoredEntry::name)
        val coverEntriesByName = coverEntries
            .associateBy(ManagedDownloadStorage.StoredEntry::name)
        val lyricEntriesByName = lyricEntries
            .associateBy(ManagedDownloadStorage.StoredEntry::name)
        val metadataByAudioName = if (readText != null && parseMetadata != null) {
            rootEntriesByName.values
                .asSequence()
                .filter { entry -> ManagedDownloadTreeNaming.isMetadataName(entry.name) }
                .mapNotNull { entry ->
                    readText(entry)?.let(parseMetadata)?.let { metadata ->
                        ManagedDownloadTreeNaming.metadataAudioName(entry.name)?.let { audioName ->
                            audioName to metadata
                        }
                    }
                }
                .toMap()
        } else {
            emptyMap()
        }
        return ManagedMigrationTargetIndex(
            rootEntriesByName = rootEntriesByName,
            coverEntriesByName = coverEntriesByName,
            lyricEntriesByName = lyricEntriesByName,
            metadataByAudioName = metadataByAudioName
        )
    }
}
