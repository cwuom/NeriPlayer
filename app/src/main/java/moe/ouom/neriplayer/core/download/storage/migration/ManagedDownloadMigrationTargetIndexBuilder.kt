package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming

internal object ManagedDownloadMigrationTargetIndexBuilder {
    fun build(
        rootEntries: List<ManagedDownloadStorage.StoredEntry>,
        coverEntries: List<ManagedDownloadStorage.StoredEntry>,
        lyricEntries: List<ManagedDownloadStorage.StoredEntry>,
        readText: ((ManagedDownloadStorage.StoredEntry) -> String?)? = null,
        parseMetadata: ((String) -> ManagedDownloadStorage.DownloadedAudioMetadata?)? = null
    ): ManagedMigrationTargetIndex {
        val (rootEntriesByName, ambiguousRootNames) = buildNameIndex(rootEntries)
        val (coverEntriesByName, ambiguousCoverNames) = buildNameIndex(coverEntries)
        val (lyricEntriesByName, ambiguousLyricNames) = buildNameIndex(lyricEntries)
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
            metadataByAudioName = metadataByAudioName,
            ambiguousNamesBySubdirectory = buildMap<String?, Set<String>> {
                if (ambiguousRootNames.isNotEmpty()) put(null, ambiguousRootNames)
                if (ambiguousCoverNames.isNotEmpty()) {
                    put(COVER_SUBDIRECTORY, ambiguousCoverNames)
                }
                if (ambiguousLyricNames.isNotEmpty()) {
                    put(LYRIC_SUBDIRECTORY, ambiguousLyricNames)
                }
            }
        )
    }

    private fun buildNameIndex(
        entries: List<ManagedDownloadStorage.StoredEntry>
    ): Pair<
        Map<String, ManagedDownloadStorage.StoredEntry>,
        Set<String>
    > {
        val unique = linkedMapOf<String, ManagedDownloadStorage.StoredEntry>()
        val ambiguous = linkedSetOf<String>()
        entries.forEach { entry ->
            val name = entry.name
            if (name in ambiguous) return@forEach
            if (unique.remove(name) != null) {
                ambiguous += name
            } else {
                unique[name] = entry
            }
        }
        return unique to ambiguous
    }
}
