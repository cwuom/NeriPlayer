package moe.ouom.neriplayer.core.download.cleanup

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.isDurableCoreArtifactState
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import java.util.Locale

/** 描述可删除的引用，以及已经跨过核心提交边界的 pending 引用 */
internal data class ManagedDownloadPendingArtifactCleanupPlan(
    val referencesToDelete: Set<String> = emptySet(),
    val protectedReferences: Set<String> = emptySet()
)

/** 只为元数据已经证明归属的已取消 operation 规划清理 */
internal object ManagedDownloadPendingArtifactCleanupPlanner {
    /**
     * 计划用户明确清空时可以删除的无主 pending 哨兵
     *
     * 根目录旧版本可能留下没有 Room operation 的哨兵文件。只有明确的
     * 应用命名、没有持久核心证据且没有不可读同名元数据时才删除
     * .tmp 中无法证明状态的条目继续保留，等待恢复凭据出现
     */
    fun planUnownedForExplicitClear(
        entries: Collection<ManagedDownloadStorage.StoredEntry>,
        temporaryReferences: Set<String>,
        parsedMetadataEntries: Collection<ManagedDownloadParsedMetadataEntry>,
        unreadableMetadataReferences: Set<String>,
        protectedReferences: Set<String> = emptySet()
    ): ManagedDownloadPendingArtifactCleanupPlan {
        val allEntries = entries
            .asSequence()
            .filterNot(ManagedDownloadStorage.StoredEntry::isDirectory)
            .distinctBy(ManagedDownloadStorage.StoredEntry::reference)
            .toList()
        if (allEntries.isEmpty()) {
            return ManagedDownloadPendingArtifactCleanupPlan()
        }
        val pendingEntries = allEntries.filter(::isPendingArtifact)
        if (pendingEntries.isEmpty()) {
            return ManagedDownloadPendingArtifactCleanupPlan()
        }
        val metadataByReference = parsedMetadataEntries.associateBy {
            it.entry.reference
        }
        val unreadableAudioNames = allEntries
            .asSequence()
            .filter { entry -> entry.reference in unreadableMetadataReferences }
            .mapNotNull { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name)
            }
            .toSet()
        val durableAudioNames = parsedMetadataEntries
            .asSequence()
            .filter { parsed -> isDurableCoreMetadata(parsed.metadata) }
            .mapNotNull { parsed ->
                ManagedDownloadTreeNaming.metadataAudioName(parsed.entry.name)
            }
            .toSet()
        val transientAudioNames = parsedMetadataEntries
            .asSequence()
            .filter { parsed -> isKnownTransientPendingMetadata(parsed.metadata) }
            .mapNotNull { parsed ->
                ManagedDownloadTreeNaming.metadataAudioName(parsed.entry.name)
            }
            .toSet()
        val uncertainAudioNames = parsedMetadataEntries
            .asSequence()
            .filter { parsed ->
                !isDurableCoreMetadata(parsed.metadata) &&
                    !isKnownTransientPendingMetadata(parsed.metadata)
            }
            .mapNotNull { parsed ->
                ManagedDownloadTreeNaming.metadataAudioName(parsed.entry.name)
            }
            .toSet()
        val protected = linkedSetOf<String>().apply {
            addAll(protectedReferences)
            pendingEntries.forEach { entry ->
                if (
                    logicalName(entry) in durableAudioNames ||
                        logicalName(entry) in uncertainAudioNames
                ) {
                    add(entry.reference)
                }
            }
        }
        val referencesToDelete = linkedSetOf<String>()
        pendingEntries.forEach { entry ->
            if (entry.reference in protected) return@forEach
            val logicalName = logicalName(entry)
            if (logicalName == null || logicalName in durableAudioNames) return@forEach
            val parsedMetadata = metadataByReference[entry.reference]?.metadata
            if (parsedMetadata != null && isKnownTransientPendingMetadata(parsedMetadata)) {
                referencesToDelete += entry.reference
                return@forEach
            }
            val isTemporary = entry.reference in temporaryReferences
            if (isTemporary) {
                // 有同名可解析的短暂 metadata 时可以确认是未提交的旧 staging
                if (
                    entry.isPendingAudioWrite &&
                        logicalName in transientAudioNames &&
                        logicalName !in unreadableAudioNames
                ) {
                    referencesToDelete += entry.reference
                }
                // 未知 .tmp 音频可能正处于 core 提交窗口，不能仅凭文件名删除
                return@forEach
            }
            // 根目录 pending 是旧版本明确生成的临时哨兵。不可读的同名
            // metadata 仍代表潜在可恢复 core，保留整对文件等待下一轮恢复
            if (logicalName in unreadableAudioNames) return@forEach
            // 有正式音频时这是碰撞后的旧哨兵，没有正式音频时也仍是用户
            // 明确清空的应用临时文件，不触碰任何普通正式音频
            if (
                entry.isPendingAudioWrite ||
                    ManagedDownloadTreeNaming.isPendingMetadataName(
                        actualName = entry.name,
                        audioName = logicalName
                    )
            ) {
                referencesToDelete += entry.reference
            }
        }
        return ManagedDownloadPendingArtifactCleanupPlan(
            referencesToDelete = referencesToDelete,
            protectedReferences = protected
        )
    }

    /** 规划取消清理，同时保留已经跨过核心提交边界的 pending 文件 */
    fun planCancelledOperation(
        rootEntries: List<ManagedDownloadStorage.StoredEntry>,
        parsedMetadataEntries: List<ManagedDownloadParsedMetadataEntry>,
        stableKey: String,
        operationId: String
    ): ManagedDownloadPendingArtifactCleanupPlan {
        val normalizedStableKey = stableKey.trim().takeIf(String::isNotBlank)
            ?: return ManagedDownloadPendingArtifactCleanupPlan()
        val normalizedOperationId = operationId.trim().takeIf(String::isNotBlank)
            ?: return ManagedDownloadPendingArtifactCleanupPlan()
        val pendingMetadataEntries = parsedMetadataEntries.filter { parsed ->
            val audioName = ManagedDownloadTreeNaming.metadataAudioName(parsed.entry.name)
                ?: return@filter false
            ManagedDownloadTreeNaming.isPendingMetadataName(
                actualName = parsed.entry.name,
                audioName = audioName
            ) &&
                parsed.metadata.stableKey == normalizedStableKey &&
                parsed.metadata.operationId == normalizedOperationId &&
                parsed.metadata.audioFileName == audioName
        }
        if (pendingMetadataEntries.isEmpty()) {
            return ManagedDownloadPendingArtifactCleanupPlan()
        }

        val pendingMetadataEntriesByAudioName = rootEntries
            .asSequence()
            .filterNot(ManagedDownloadStorage.StoredEntry::isDirectory)
            .mapNotNull { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                    ?.takeIf { audioName ->
                        ManagedDownloadTreeNaming.isPendingMetadataName(
                            actualName = entry.name,
                            audioName = audioName
                        )
                    }
                    ?.let { audioName -> audioName to entry }
            }
            .groupBy({ (audioName, _) -> audioName }, { (_, entry) -> entry })
        val pendingAudioByLogicalName = rootEntries
            .asSequence()
            .filterNot(ManagedDownloadStorage.StoredEntry::isDirectory)
            .filter(ManagedDownloadStorage.StoredEntry::isPendingAudioWrite)
            .groupBy(ManagedDownloadStorage.StoredEntry::logicalName)

        val durableMetadataEntries = parsedMetadataEntries.filter { parsed ->
            isDurableCoreMetadata(parsed.metadata)
        }
        val durableAudioNames = pendingMetadataEntries
            .mapNotNull { parsed ->
                val audioName = ManagedDownloadTreeNaming.metadataAudioName(parsed.entry.name)
                    ?: return@mapNotNull null
                if (
                    isDurableCoreMetadata(parsed.metadata) ||
                        durableMetadataEntries.any { durable ->
                            ManagedDownloadTreeNaming.metadataAudioName(durable.entry.name) == audioName &&
                                metadataIdentityCompatible(parsed.metadata, durable.metadata)
                        }
                ) {
                    audioName
                } else {
                    null
                }
            }
            .toSet()
        val protectedReferences = linkedSetOf<String>().apply {
            durableAudioNames.forEach { audioName ->
                pendingMetadataEntriesByAudioName[audioName]
                    .orEmpty()
                    .mapTo(this, ManagedDownloadStorage.StoredEntry::reference)
                pendingAudioByLogicalName[audioName]
                    .orEmpty()
                    .mapTo(this, ManagedDownloadStorage.StoredEntry::reference)
            }
        }
        val metadataEligibleForDeletion = pendingMetadataEntries.filter { parsed ->
            val audioName = ManagedDownloadTreeNaming.metadataAudioName(parsed.entry.name)
            audioName !in durableAudioNames
        }
        if (metadataEligibleForDeletion.isEmpty()) {
            return ManagedDownloadPendingArtifactCleanupPlan(
                protectedReferences = protectedReferences
            )
        }

        return ManagedDownloadPendingArtifactCleanupPlan(
            referencesToDelete = planProvenPendingReferences(
                pendingMetadataEntries = metadataEligibleForDeletion,
                pendingMetadataEntriesByAudioName = pendingMetadataEntriesByAudioName,
                pendingAudioByLogicalName = pendingAudioByLogicalName
            ),
            protectedReferences = protectedReferences
        )
    }

    fun planCancelledOperationReferences(
        rootEntries: List<ManagedDownloadStorage.StoredEntry>,
        parsedMetadataEntries: List<ManagedDownloadParsedMetadataEntry>,
        stableKey: String,
        operationId: String
    ): Set<String> {
        return planCancelledOperation(
            rootEntries = rootEntries,
            parsedMetadataEntries = parsedMetadataEntries,
            stableKey = stableKey,
            operationId = operationId
        ).referencesToDelete
    }

    private fun planProvenPendingReferences(
        pendingMetadataEntries: List<ManagedDownloadParsedMetadataEntry>,
        pendingMetadataEntriesByAudioName: Map<String, List<ManagedDownloadStorage.StoredEntry>>,
        pendingAudioByLogicalName: Map<String, List<ManagedDownloadStorage.StoredEntry>>
    ): Set<String> {
        return linkedSetOf<String>().apply {
            pendingMetadataEntries
                .map(ManagedDownloadParsedMetadataEntry::entry)
                .map(ManagedDownloadStorage.StoredEntry::reference)
                .forEach(::add)
            pendingMetadataEntries
                .mapNotNull { parsed ->
                    ManagedDownloadTreeNaming.metadataAudioName(parsed.entry.name)
                        ?.let { audioName -> audioName to parsed }
                }
                .groupBy({ (audioName, _) -> audioName }, { (_, parsed) -> parsed })
                .forEach { (audioName, ownedMetadata) ->
                    val allPendingMetadata = pendingMetadataEntriesByAudioName[audioName].orEmpty()
                    val pendingAudio = pendingAudioByLogicalName[audioName].orEmpty()
                    val ownedReferences = ownedMetadata.mapTo(linkedSetOf()) { parsed ->
                        parsed.entry.reference
                    }
                    if (
                        allPendingMetadata.size == ownedReferences.size &&
                            allPendingMetadata.all { entry -> entry.reference in ownedReferences } &&
                            pendingAudio.size == 1
                    ) {
                        add(pendingAudio.single().reference)
                    }
                }
        }
    }

    private fun isDurableCoreMetadata(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata
    ): Boolean {
        if (metadata.downloadFinalized == true) {
            return true
        }
        return isDurableCoreArtifactState(
            metadata.artifactState
                ?.trim()
                ?.uppercase(Locale.ROOT)
        )
    }

    private fun isKnownTransientPendingMetadata(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata
    ): Boolean {
        if (metadata.downloadFinalized == true) return false
        val state = metadata.artifactState
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?: return false
        return state in KNOWN_TRANSIENT_PENDING_ARTIFACT_STATES
    }

    private fun isPendingArtifact(
        entry: ManagedDownloadStorage.StoredEntry
    ): Boolean {
        if (entry.isPendingAudioWrite) return true
        val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
            ?: return false
        return ManagedDownloadTreeNaming.isPendingMetadataName(
            actualName = entry.name,
            audioName = audioName
        )
    }

    private fun logicalName(
        entry: ManagedDownloadStorage.StoredEntry
    ): String? {
        return if (entry.isPendingAudioWrite) {
            entry.logicalName.takeIf(String::isNotBlank)
        } else {
            ManagedDownloadTreeNaming.metadataAudioName(entry.name)
        }
    }

    private val KNOWN_TRANSIENT_PENDING_ARTIFACT_STATES = setOf(
        "PENDING_QUEUE",
        "QUEUED",
        "DOWNLOADING",
        "VERIFYING",
        "RETRYABLE",
        "FAILED_RETRYABLE",
        "CANCELLED"
    )

    private fun metadataIdentityCompatible(
        pending: ManagedDownloadStorage.DownloadedAudioMetadata,
        durable: ManagedDownloadStorage.DownloadedAudioMetadata
    ): Boolean {
        val pendingStableKey = pending.stableKey?.trim()?.takeIf(String::isNotBlank)
        val durableStableKey = durable.stableKey?.trim()?.takeIf(String::isNotBlank)
        val pendingOperationId = pending.operationId?.trim()?.takeIf(String::isNotBlank)
        val durableOperationId = durable.operationId?.trim()?.takeIf(String::isNotBlank)
        if (
            pendingStableKey != null && durableStableKey != null &&
                pendingOperationId != null && durableOperationId != null
        ) {
            return pendingStableKey == durableStableKey &&
                pendingOperationId == durableOperationId
        }
        // 缺少 identity 字段不能证明 core 属于其他请求，先保留等待后续恢复
        return true
    }
}
