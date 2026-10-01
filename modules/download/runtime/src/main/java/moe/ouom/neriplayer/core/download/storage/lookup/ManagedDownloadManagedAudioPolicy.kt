package moe.ouom.neriplayer.core.download.storage.lookup

import moe.ouom.neriplayer.core.download.candidateManagedDownloadBaseNames
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming

internal object ManagedDownloadManagedAudioPolicy {
    internal data class NameIndex(
        val metadataAudioNames: Set<String>,
        val coverEntryNames: Set<String>,
        val lyricEntryNames: Set<String>,
        val allowMetadataLessAudio: Boolean
    )

    fun buildNameIndex(
        metadataAudioNames: Collection<String>,
        coverEntryNames: Collection<String>,
        lyricEntryNames: Collection<String>,
        allowMetadataLessAudio: Boolean
    ): NameIndex {
        return NameIndex(
            metadataAudioNames = canonicalStoredNames(metadataAudioNames),
            coverEntryNames = indexedStoredNames(coverEntryNames),
            lyricEntryNames = indexedStoredNames(lyricEntryNames),
            allowMetadataLessAudio = allowMetadataLessAudio
        )
    }

    fun shouldTreatAudioAsManaged(
        audioName: String,
        metadataAudioNames: Set<String>,
        coverEntryNames: Set<String>,
        lyricEntryNames: Set<String>,
        allowMetadataLessAudio: Boolean
    ): Boolean {
        return shouldTreatAudioAsManaged(
            audioName = audioName,
            nameIndex = buildNameIndex(
                metadataAudioNames = metadataAudioNames,
                coverEntryNames = coverEntryNames,
                lyricEntryNames = lyricEntryNames,
                allowMetadataLessAudio = allowMetadataLessAudio
            )
        )
    }

    fun shouldTreatAudioAsManaged(
        audioName: String,
        nameIndex: NameIndex
    ): Boolean {
        if (lookupAliases(audioName).any(nameIndex.metadataAudioNames::contains)) {
            return true
        }
        if (nameIndex.allowMetadataLessAudio) {
            return true
        }
        val candidateBaseNames = candidateManagedDownloadBaseNames(
            audioName.substringBeforeLast('.', audioName)
        )
        val hasManagedCover = ManagedDownloadStorageNaming
            .buildSidecarCandidateNames(candidateBaseNames)
            .any { candidate ->
                ManagedDownloadTreeNaming.canonicalLookupName(candidate) in
                    nameIndex.coverEntryNames
            }
        if (hasManagedCover) {
            return true
        }
        return ManagedDownloadStorageNaming.LyricKind.entries.any { kind ->
            ManagedDownloadStorageNaming.buildLyricCandidateNames(
                songId = null,
                candidateBaseNames = candidateBaseNames,
                kind = kind
            ).any { candidate ->
                ManagedDownloadTreeNaming.canonicalLookupName(candidate) in
                    nameIndex.lyricEntryNames
            }
        }
    }

    private fun indexedStoredNames(names: Collection<String>): Set<String> {
        return buildSet(names.size * 2) {
            names.forEach { name -> addAll(lookupAliases(name)) }
        }
    }

    private fun canonicalStoredNames(names: Collection<String>): Set<String> {
        return names.mapTo(linkedSetOf(), ManagedDownloadTreeNaming::canonicalLookupName)
    }

    private fun lookupAliases(name: String): Set<String> {
        val canonicalName = ManagedDownloadTreeNaming.canonicalLookupName(name)
        return buildSet(3) {
            add(canonicalName)
            providerUnnumberedName(canonicalName)?.let(::add)
            providerUnnumberedStemName(canonicalName)?.let(::add)
        }
    }

    private fun providerUnnumberedName(name: String): String? {
        val markerIndex = name.lastIndexOf(" (")
        if (markerIndex <= 0 || !name.endsWith(')')) return null
        val ordinal = name.substring(markerIndex + 2, name.length - 1).toIntOrNull()
            ?: return null
        val candidate = name.substring(0, markerIndex)
        return candidate.takeIf {
            ManagedDownloadTreeNaming.providerNumberedNameOrdinal(name, candidate) == ordinal
        }
    }

    private fun providerUnnumberedStemName(name: String): String? {
        val extensionIndex = name.lastIndexOf('.')
        if (extensionIndex <= 0 || extensionIndex == name.lastIndex) return null
        val stem = name.substring(0, extensionIndex)
        val markerIndex = stem.lastIndexOf(" (")
        if (markerIndex <= 0 || !stem.endsWith(')')) return null
        val ordinal = stem.substring(markerIndex + 2, stem.length - 1).toIntOrNull()
            ?: return null
        val candidate = stem.substring(0, markerIndex) + name.substring(extensionIndex)
        return candidate.takeIf {
            ManagedDownloadTreeNaming.providerNumberedNameOrdinal(name, candidate) == ordinal
        }
    }
}
