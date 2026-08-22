package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.naming.candidateManagedDownloadBaseNames
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.REMOTE_COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadManagedAudioPolicy
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming

internal object ManagedDownloadMigrationEntryCollector {
    fun collect(
        rootEntries: List<ManagedDownloadStorage.StoredEntry>,
        coverEntries: List<ManagedDownloadStorage.StoredEntry>,
        lyricEntries: List<ManagedDownloadStorage.StoredEntry>,
        parsedMetadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>,
        allowMetadataLessAudio: Boolean,
        remoteCoverEntries: List<ManagedDownloadStorage.StoredEntry> = emptyList()
    ): List<ManagedMigrationEntry> {
        val audioEntries = rootEntries.filter { entry -> entry.extension in audioExtensions }
        val metadataEntries = rootEntries.filter { entry -> ManagedDownloadTreeNaming.isMetadataName(entry.name) }
        val metadataEntriesByAudioName = metadataEntries
            .mapNotNull { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name)?.let { audioName ->
                    audioName to entry
                }
            }
            .groupBy { it.first }
            .mapValues { (audioName, entries) ->
                entries.minWithOrNull(
                    compareBy<Pair<String, ManagedDownloadStorage.StoredEntry>>(
                        { ManagedDownloadTreeNaming.metadataNameOrdinal(it.second.name, audioName) ?: Int.MAX_VALUE },
                        { it.second.name }
                    )
                )!!.second
            }
        val allCoverEntries = coverEntries + remoteCoverEntries
        val coverEntryNames = allCoverEntries.mapTo(linkedSetOf(), ManagedDownloadStorage.StoredEntry::name)
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

        val managedCoverNames = managedCoverNames(
            managedAudioEntries = managedAudioEntries,
            metadataAudioNames = metadataEntriesByAudioName.keys,
            coverEntries = allCoverEntries,
            parsedMetadataByAudioName = parsedMetadataByAudioName
        )
        val managedLyricNames = managedLyricNames(
            managedAudioEntries = managedAudioEntries,
            parsedMetadataByAudioName = parsedMetadataByAudioName,
            metadataAudioNames = metadataEntriesByAudioName.keys
        )

        return buildList {
            managedAudioEntries.forEach { entry ->
                add(
                    ManagedMigrationEntry(
                        subdirectory = null,
                        entry = entry,
                        metadata = parsedMetadataByAudioName[entry.name]
                    )
                )
            }
            metadataEntries.forEach { entry ->
                // metadata 可能是上次迁移留下的唯一残留, 不能依赖音频文件仍然存在
                add(ManagedMigrationEntry(subdirectory = null, entry = entry))
            }
            coverEntries.forEach { entry ->
                if (entry.name in managedCoverNames) {
                    add(ManagedMigrationEntry(subdirectory = COVER_SUBDIRECTORY, entry = entry))
                }
            }
            remoteCoverEntries.forEach { entry ->
                if (entry.name in managedCoverNames) {
                    add(ManagedMigrationEntry(subdirectory = REMOTE_COVER_SUBDIRECTORY, entry = entry))
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
        managedAudioEntries: List<ManagedDownloadStorage.StoredEntry>,
        metadataAudioNames: Set<String>,
        coverEntries: List<ManagedDownloadStorage.StoredEntry>,
        parsedMetadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>
    ): Set<String> {
        return buildSet {
            fun addStableCoverCandidates(baseNames: List<String>, stableKey: String?) {
                stableKey
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { key ->
                        baseNames.forEach { baseName ->
                            ManagedDownloadStorageNaming
                                .buildStableCoverCandidateNames(baseName, key)
                                .forEach(::add)
                        }
                    }
            }

            managedAudioEntries.forEach { entry ->
                val candidateBaseNames = candidateManagedDownloadBaseNames(entry.nameWithoutExtension)
                ManagedDownloadStorageNaming.buildSidecarCandidateNames(candidateBaseNames).forEach(::add)
                addStableCoverCandidates(
                    baseNames = candidateBaseNames,
                    stableKey = parsedMetadataByAudioName[entry.name]?.stableKey
                )
            }
            metadataAudioNames
                .asSequence()
                .filterNot { audioName -> managedAudioEntries.any { it.name == audioName } }
                .flatMap { audioName ->
                    candidateManagedDownloadBaseNames(
                        audioName.substringBeforeLast('.', audioName)
                    ).asSequence()
                }
                .flatMap { candidateBaseName ->
                    ManagedDownloadStorageNaming
                        .buildSidecarCandidateNames(listOf(candidateBaseName))
                        .asSequence()
                }
                .forEach(::add)

            parsedMetadataByAudioName.forEach { (audioName, metadata) ->
                val baseNames = candidateManagedDownloadBaseNames(
                    audioName.substringBeforeLast('.', audioName)
                )
                addStableCoverCandidates(baseNames, metadata.stableKey)
                metadata.coverPath
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { coverPath ->
                        coverEntries
                            .firstOrNull { entry ->
                                entry.reference == coverPath ||
                                    entry.mediaUri == coverPath ||
                                    entry.localFilePath == coverPath
                            }
                            ?.name
                            ?.let(::add)
                    }
            }
        }
    }

    private fun managedLyricNames(
        managedAudioEntries: List<ManagedDownloadStorage.StoredEntry>,
        parsedMetadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>,
        metadataAudioNames: Set<String>
    ): Set<String> {
        return buildSet {
            fun addLyricCandidates(audioName: String, songId: Long?) {
                val candidateBaseNames = candidateManagedDownloadBaseNames(
                    audioName.substringBeforeLast('.', audioName)
                )
                ManagedDownloadStorageNaming.buildLyricCandidateNames(
                    songId = songId,
                    candidateBaseNames = candidateBaseNames,
                    kind = ManagedDownloadStorageNaming.LyricKind.ORIGINAL
                ).forEach(::add)
                ManagedDownloadStorageNaming.buildLyricCandidateNames(
                    songId = songId,
                    candidateBaseNames = candidateBaseNames,
                    kind = ManagedDownloadStorageNaming.LyricKind.TRANSLATED
                ).forEach(::add)
                ManagedDownloadStorageNaming.buildLyricCandidateNames(
                    songId = songId,
                    candidateBaseNames = candidateBaseNames,
                    kind = ManagedDownloadStorageNaming.LyricKind.ROMANIZED
                ).forEach(::add)
            }
            managedAudioEntries.forEach { entry ->
                addLyricCandidates(
                    audioName = entry.name,
                    songId = parsedMetadataByAudioName[entry.name]?.songId
                )
            }
            metadataAudioNames
                .asSequence()
                .filterNot { audioName -> managedAudioEntries.any { it.name == audioName } }
                .forEach { audioName ->
                    addLyricCandidates(
                        audioName = audioName,
                        songId = parsedMetadataByAudioName[audioName]?.songId
                    )
                }
        }
    }
}
