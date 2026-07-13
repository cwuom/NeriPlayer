package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.naming.candidateManagedDownloadBaseNames
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadManagedAudioPolicy
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming

internal object ManagedDownloadMigrationEntryCollector {
    fun collect(
        rootEntries: List<ManagedDownloadStorage.StoredEntry>,
        coverEntries: List<ManagedDownloadStorage.StoredEntry>,
        lyricEntries: List<ManagedDownloadStorage.StoredEntry>,
        parsedMetadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>,
        allowMetadataLessAudio: Boolean
    ): List<ManagedMigrationEntry> {
        val audioEntries = rootEntries.filter { entry -> entry.extension in audioExtensions }
        val metadataEntries = rootEntries.filter { entry -> entry.name.endsWith(METADATA_SUFFIX) }
        val metadataEntriesByAudioName = metadataEntries.associateBy { entry ->
            entry.name.removeSuffix(METADATA_SUFFIX)
        }
        val coverEntryNames = coverEntries.mapTo(linkedSetOf(), ManagedDownloadStorage.StoredEntry::name)
        val lyricEntryNames = lyricEntries.mapTo(linkedSetOf(), ManagedDownloadStorage.StoredEntry::name)
        val managedAudioEntries = audioEntries.filter { entry ->
            ManagedDownloadManagedAudioPolicy.shouldTreatAudioAsManaged(
                audioName = entry.name,
                metadataAudioNames = metadataEntriesByAudioName.keys,
                coverEntryNames = coverEntryNames,
                lyricEntryNames = lyricEntryNames,
                allowMetadataLessAudio = allowMetadataLessAudio
            )
        }
        if (managedAudioEntries.isEmpty() && metadataEntriesByAudioName.isEmpty()) {
            return emptyList()
        }

        val managedAudioNames = managedAudioEntries.mapTo(linkedSetOf(), ManagedDownloadStorage.StoredEntry::name)
        val managedCoverNames = managedCoverNames(managedAudioEntries)
        val managedLyricNames = managedLyricNames(
            managedAudioEntries = managedAudioEntries,
            parsedMetadataByAudioName = parsedMetadataByAudioName
        )

        return buildList {
            managedAudioEntries.forEach { entry ->
                add(ManagedMigrationEntry(subdirectory = null, entry = entry))
            }
            metadataEntries.forEach { entry ->
                if (entry.name.removeSuffix(METADATA_SUFFIX) in managedAudioNames) {
                    add(ManagedMigrationEntry(subdirectory = null, entry = entry))
                }
            }
            coverEntries.forEach { entry ->
                if (entry.name in managedCoverNames) {
                    add(ManagedMigrationEntry(subdirectory = COVER_SUBDIRECTORY, entry = entry))
                }
            }
            lyricEntries.forEach { entry ->
                if (entry.name in managedLyricNames) {
                    add(ManagedMigrationEntry(subdirectory = LYRIC_SUBDIRECTORY, entry = entry))
                }
            }
        }.sortedWith(compareBy({ it.subdirectory ?: "" }, { it.entry.name }))
    }

    private fun managedCoverNames(
        managedAudioEntries: List<ManagedDownloadStorage.StoredEntry>
    ): Set<String> {
        return buildSet {
            managedAudioEntries.forEach { entry ->
                val candidateBaseNames = candidateManagedDownloadBaseNames(entry.nameWithoutExtension)
                ManagedDownloadStorageNaming.buildSidecarCandidateNames(candidateBaseNames).forEach(::add)
            }
        }
    }

    private fun managedLyricNames(
        managedAudioEntries: List<ManagedDownloadStorage.StoredEntry>,
        parsedMetadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>
    ): Set<String> {
        return buildSet {
            managedAudioEntries.forEach { entry ->
                val candidateBaseNames = candidateManagedDownloadBaseNames(entry.nameWithoutExtension)
                val songId = parsedMetadataByAudioName[entry.name]?.songId
                ManagedDownloadStorageNaming.buildLyricCandidateNames(
                    songId = songId,
                    candidateBaseNames = candidateBaseNames,
                    translated = false
                ).forEach(::add)
                ManagedDownloadStorageNaming.buildLyricCandidateNames(
                    songId = songId,
                    candidateBaseNames = candidateBaseNames,
                    translated = true
                ).forEach(::add)
            }
        }
    }
}
