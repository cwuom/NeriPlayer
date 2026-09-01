package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.naming.candidateManagedDownloadBaseNames
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadManagedAudioPolicy
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming

internal object ManagedDownloadMigrationEntryCollector {
    internal data class PendingArtifactClassification(
        val blockingNames: List<String>,
        val metadataOnlyNames: List<String>
    ) {
        val allNames: List<String>
            get() = (blockingNames + metadataOnlyNames).distinct().sorted()
    }

    /**
     * 判断目录中是否存在尚未完成提交的下载产物
     *
     * 旧版本把 pending 音频和 pending metadata 直接写在根目录，新版本则
     * 放到 .tmp。迁移不能把这些文件当成正式媒体复制，否则重启后可能把
     * 半成品发布到媒体库；调用方应先让下载恢复流程收敛，再重新迁移
     */
    fun isPendingArtifact(entry: ManagedDownloadStorage.StoredEntry): Boolean {
        if (entry.isDirectory) return false
        if (entry.isPendingAudioWrite) return true
        val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
            ?: return false
        return ManagedDownloadTreeNaming.isPendingMetadataName(
            actualName = entry.name,
            audioName = audioName
        )
    }

    /**
     * pending metadata 只有与同名 pending 音频配对时才会阻断迁移
     *
     * 没有 pending 音频的 metadata 是一次中断留下的恢复证据。它会被迁移
     * 收集器保留，但不能要求仅处理音频的恢复器反复重试
     */
    fun classifyPendingArtifacts(
        rootEntries: Collection<ManagedDownloadStorage.StoredEntry>,
        temporaryEntries: Collection<ManagedDownloadStorage.StoredEntry> = emptyList()
    ): PendingArtifactClassification {
        val pendingEntries = (rootEntries.asSequence() + temporaryEntries.asSequence())
            .filter(::isPendingArtifact)
            .toList()
        val pendingAudioNames = pendingEntries
            .asSequence()
            .filter(ManagedDownloadStorage.StoredEntry::isPendingAudioWrite)
            .map(ManagedDownloadStorage.StoredEntry::logicalName)
            .filter(String::isNotBlank)
            .toSet()
        val blockingNames = pendingEntries
            .asSequence()
            .filter { entry ->
                entry.isPendingAudioWrite ||
                    ManagedDownloadTreeNaming.metadataAudioName(entry.name) in pendingAudioNames
            }
            .map(ManagedDownloadStorage.StoredEntry::name)
            .distinct()
            .sorted()
            .toList()
        val metadataOnlyNames = pendingEntries
            .asSequence()
            .filterNot(ManagedDownloadStorage.StoredEntry::isPendingAudioWrite)
            .filter { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name) !in pendingAudioNames
            }
            .map(ManagedDownloadStorage.StoredEntry::name)
            .distinct()
            .sorted()
            .toList()
        return PendingArtifactClassification(
            blockingNames = blockingNames,
            metadataOnlyNames = metadataOnlyNames
        )
    }

    /**
     * 返回根目录和 .tmp 中的 pending 名称，名称只用于诊断和有界重试日志
     */
    fun pendingArtifactNames(
        rootEntries: Collection<ManagedDownloadStorage.StoredEntry>,
        temporaryEntries: Collection<ManagedDownloadStorage.StoredEntry> = emptyList()
    ): List<String> {
        return classifyPendingArtifacts(rootEntries, temporaryEntries).allNames
    }

    fun blockingPendingArtifactNames(
        rootEntries: Collection<ManagedDownloadStorage.StoredEntry>,
        temporaryEntries: Collection<ManagedDownloadStorage.StoredEntry> = emptyList()
    ): List<String> {
        return classifyPendingArtifacts(rootEntries, temporaryEntries).blockingNames
    }

    fun hasPendingArtifacts(
        rootEntries: Collection<ManagedDownloadStorage.StoredEntry>,
        temporaryEntries: Collection<ManagedDownloadStorage.StoredEntry> = emptyList()
    ): Boolean {
        return classifyPendingArtifacts(rootEntries, temporaryEntries).allNames.isNotEmpty()
    }

    fun hasBlockingPendingArtifacts(
        rootEntries: Collection<ManagedDownloadStorage.StoredEntry>,
        temporaryEntries: Collection<ManagedDownloadStorage.StoredEntry> = emptyList()
    ): Boolean {
        return classifyPendingArtifacts(rootEntries, temporaryEntries).blockingNames.isNotEmpty()
    }

    fun requiresSidecarEvidence(
        rootEntries: List<ManagedDownloadStorage.StoredEntry>,
        allowMetadataLessAudio: Boolean
    ): Boolean {
        if (allowMetadataLessAudio) {
            return false
        }
        var hasAudio = false
        for (entry in rootEntries) {
            if (entry.isDirectory) {
                continue
            }
            if (ManagedDownloadTreeNaming.metadataAudioName(entry.name) != null) {
                return false
            }
            hasAudio = hasAudio || entry.extension in audioExtensions
        }
        return hasAudio
    }

    fun hasAnyManagedEntry(
        rootEntries: List<ManagedDownloadStorage.StoredEntry>,
        coverEntries: List<ManagedDownloadStorage.StoredEntry>,
        lyricEntries: List<ManagedDownloadStorage.StoredEntry>,
        allowMetadataLessAudio: Boolean
    ): Boolean {
        val metadataAudioNames = rootEntries
            .asSequence()
            .filterNot(ManagedDownloadStorage.StoredEntry::isDirectory)
            .mapNotNull { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name)
            }
            .toHashSet()
        if (metadataAudioNames.isNotEmpty()) {
            return true
        }
        val coverEntryNames = coverEntries
            .asSequence()
            .mapTo(linkedSetOf(), ManagedDownloadStorage.StoredEntry::name)
        val lyricEntryNames = lyricEntries
            .asSequence()
            .mapTo(linkedSetOf(), ManagedDownloadStorage.StoredEntry::name)
        return rootEntries.asSequence().any { entry ->
            !entry.isDirectory &&
                entry.extension in audioExtensions &&
                ManagedDownloadManagedAudioPolicy.shouldTreatAudioAsManaged(
                    audioName = entry.name,
                    metadataAudioNames = metadataAudioNames,
                    coverEntryNames = coverEntryNames,
                    lyricEntryNames = lyricEntryNames,
                    allowMetadataLessAudio = allowMetadataLessAudio
                )
        }
    }

    fun collect(
        rootEntries: List<ManagedDownloadStorage.StoredEntry>,
        coverEntries: List<ManagedDownloadStorage.StoredEntry>,
        lyricEntries: List<ManagedDownloadStorage.StoredEntry>,
        parsedMetadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>,
        allowMetadataLessAudio: Boolean
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

        val managedCoverNames = managedCoverNames(
            managedAudioEntries = managedAudioEntries,
            metadataAudioNames = metadataEntriesByAudioName.keys,
            coverEntries = coverEntries,
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
                val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                add(
                    ManagedMigrationEntry(
                        subdirectory = null,
                        entry = entry,
                        metadata = audioName?.let(parsedMetadataByAudioName::get)
                    )
                )
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
        managedAudioEntries: List<ManagedDownloadStorage.StoredEntry>,
        metadataAudioNames: Set<String>,
        coverEntries: List<ManagedDownloadStorage.StoredEntry>,
        parsedMetadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>
    ): Set<String> {
        val managedAudioNames = managedAudioEntries.mapTo(hashSetOf()) {
            it.name
        }
        val coverNamesByReference = linkedMapOf<String, String>()
        coverEntries.forEach { entry ->
            sequenceOf(entry.reference, entry.mediaUri, entry.localFilePath)
                .filterNotNull()
                .filter(String::isNotBlank)
                .forEach { reference ->
                    coverNamesByReference.putIfAbsent(reference, entry.name)
                }
        }
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
                            ManagedDownloadStorageNaming
                                .buildLegacyStableCoverCandidateNames(baseName, key)
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
                .filterNot { audioName -> audioName in managedAudioNames }
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
                        coverNamesByReference[coverPath]
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
        val managedAudioNames = managedAudioEntries.mapTo(hashSetOf()) {
            it.name
        }
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
                .filterNot { audioName -> audioName in managedAudioNames }
                .forEach { audioName ->
                    addLyricCandidates(
                        audioName = audioName,
                        songId = parsedMetadataByAudioName[audioName]?.songId
                    )
                }
        }
    }
}
