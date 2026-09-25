package moe.ouom.neriplayer.core.download.cleanup

import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.catalog.DownloadedSongDeleteTarget
import moe.ouom.neriplayer.core.download.catalog.resolveDownloadedSongPlaybackReference
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.publicationOwnerId
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedMetadataReadResult
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming

internal data class ManagedFullDeleteInventory(
    val rootEntries: List<StoredEntry>,
    val coverEntries: List<StoredEntry>,
    val lyricEntries: List<StoredEntry>,
    val temporaryEntries: List<StoredEntry>,
    val metadataByReference: Map<String, ManagedMetadataReadResult>,
    val enumerationComplete: Boolean
)

internal fun planOwnedFullLibraryDeletion(
    inventory: ManagedFullDeleteInventory,
    targets: Collection<DownloadedSongDeleteTarget> = emptyList(),
    selectedSongs: Collection<DownloadedSong> = emptyList(),
    persistedOwnedReferences: Set<String> = emptySet()
): ManagedDownloadFullDeletePlan {
    if (!inventory.enumerationComplete) {
        return ManagedDownloadFullDeletePlan(emptySet(), false,
            blockingReasonCounts = mapOf(ManagedDownloadFullDeleteBlockReason.INCOMPLETE_ENUMERATION to 1))
    }
    val entries = (inventory.rootEntries + inventory.coverEntries + inventory.lyricEntries + inventory.temporaryEntries)
        .filterNot(StoredEntry::isDirectory).distinctBy(StoredEntry::reference)
    val byReference = entries.associateBy(StoredEntry::reference)
    val referenceIndex = ManagedDownloadDeleteReferenceIndex(byReference.keys)
    val persistedReferenceIndex = ManagedDownloadDeleteReferenceIndex(persistedOwnedReferences)
    val coreEntries = (inventory.rootEntries + inventory.temporaryEntries).filterNot(StoredEntry::isDirectory)
    val audioEntries = coreEntries.filter { it.isPendingAudioWrite || it.extension in audioExtensions }
    val audioByName = audioEntries.groupBy(StoredEntry::logicalName)
    val audioByReference = buildMap {
        audioEntries.forEach { audio ->
            put(audio.reference, audio)
            put(audio.mediaUri, audio)
        }
    }
    val metadataEntries = coreEntries.filter { ManagedDownloadTreeNaming.isMetadataName(it.name) }
    val metadataByName = metadataEntries.groupBy { ManagedDownloadTreeNaming.metadataAudioName(it.name) }
    val targetKeys = targets.mapNotNullTo(hashSetOf()) { it.stableKey }.apply {
        selectedSongs.mapNotNullTo(this) { it.stableKey }
    }
    val requested = linkedSetOf<String>()
    fun addKnown(reference: String?) {
        referenceIndex.resolve(reference)?.let(requested::add)
    }
    targets.forEach { target -> audioByReference[referenceIndex.resolve(target.deletionIdentity)]?.reference?.let(::addKnown) }
    selectedSongs.forEach { song ->
        referenceIndex.resolve(resolveDownloadedSongPlaybackReference(song))?.let { audioByReference[it]?.reference }?.let(::addKnown)
        // catalog 可能保存历史猜测封面，侧载归属只能由下方 receipt 或持久删除凭据证明
    }
    val selectedAudioReferences = requested.toSet()
    persistedOwnedReferences.forEach(::addKnown)
    val unavailableMetadataCount = inventory.metadataByReference.values.count {
        it is ManagedMetadataReadResult.Unavailable
    }
    if (unavailableMetadataCount > 0) {
        // 不可读 receipt 可能仍引用共享侧载，保留没有精确归属凭据的文件
        // 已持久化的精确引用不受其它文件读取失败阻塞
        return ManagedDownloadFullDeletePlan(
            requestedReferences = requested,
            snapshotComplete = false,
            blockingReasonCounts = mapOf(
                ManagedDownloadFullDeleteBlockReason.METADATA_UNAVAILABLE to unavailableMetadataCount
            )
        )
    }
    val parsed = metadataEntries.mapNotNull { entry ->
        (inventory.metadataByReference[entry.reference] as? ManagedMetadataReadResult.Found)
            ?.let { ManagedDownloadParsedMetadataEntry(entry, it.metadata) }
    }
    val blockedSidecars = hashSetOf<String>()
    val exactSelectedAttemptNames = hashSetOf<String>()
    // 每个逻辑音频只计算一次 receipt 身份，避免逐首重扫 inventory
    val conflicts = metadataByName.mapValues { (name, siblings) ->
        val receipts = siblings.mapNotNull { entry ->
            (inventory.metadataByReference[entry.reference] as? ManagedMetadataReadResult.Found)?.metadata
        }
        val identityConflict = listOf<(DownloadedAudioMetadata) -> String?>(
            { it.stableKey }, { it.publicationOwnerId() }, { it.artifactId }, { it.libraryId }
        ).any { field -> receipts.mapNotNull { field(it)?.takeIf(String::isNotBlank) }.distinct().size > 1 }
        val stableKeys = receipts.map { it.stableKey?.takeIf(String::isNotBlank) }.distinct()
        val libraryIds = receipts.map { it.libraryId?.takeIf(String::isNotBlank) }.distinct()
        val exactReceiptReferences = receipts.mapNotNull { it.mediaUri?.takeIf(String::isNotBlank) }
        val completeSelectedIdentity = !name.isNullOrBlank() && receipts.size == siblings.size &&
            stableKeys.size == 1 && stableKeys.single() in targetKeys &&
            libraryIds.size == 1 && libraryIds.single() != null &&
            receipts.all { it.audioFileName == name } && exactReceiptReferences.size == receipts.size &&
            siblings.all { entry ->
                !ManagedDownloadTreeNaming.isPendingMetadataName(entry.name, name) ||
                    (inventory.metadataByReference[entry.reference] as? ManagedMetadataReadResult.Found)
                        ?.metadata?.operationId?.isNotBlank() == true
            }
        if (!completeSelectedIdentity) return@mapValues identityConflict
        val allReferencesProven = exactReceiptReferences.all { reference ->
            referenceIndex.resolve(reference) != null || persistedReferenceIndex.resolve(reference) != null
        }
        val exactAudio = exactReceiptReferences.mapNotNull(referenceIndex::resolve)
        val exactAudioIsCompatible = exactAudio.all { audioByReference[it]?.logicalName == name }
        val coveredAudio = exactAudio.toSet()
        val allKnownAudioCovered = audioByName[name].orEmpty().all {
            it.reference in coveredAudio || it.reference in selectedAudioReferences
        }
        // 全选同时授权同一歌曲的多次下载，只按各自精确 URI 清理，owner 换代不是另一首歌
        // 已消失的 pending 不扩充目标，同名但没有任何凭据指向的音频继续保留
        val exactSelectedAttempts = allReferencesProven && exactAudioIsCompatible && allKnownAudioCovered
        if (exactSelectedAttempts) exactSelectedAttemptNames += requireNotNull(name)
        identityConflict && !exactSelectedAttempts
    }
    val conflictingAudioNames = conflicts.filterValues { it }.keys
    val conflictingCoreReferences = coreEntries.filter { entry ->
        entry.logicalName in conflictingAudioNames ||
            ManagedDownloadTreeNaming.metadataAudioName(entry.name) in conflictingAudioNames
    }.mapTo(hashSetOf(), StoredEntry::reference)
    parsed.forEach { owner ->
        val metadata = owner.metadata
        val name = ManagedDownloadTreeNaming.metadataAudioName(owner.entry.name)
        val pendingReceipt = name != null && ManagedDownloadTreeNaming.isPendingMetadataName(owner.entry.name, name)
        val identityValid = !metadata.stableKey.isNullOrBlank() && name != null &&
            (!pendingReceipt || !metadata.operationId.isNullOrBlank()) &&
            (metadata.audioFileName == name ||
                (metadata.audioFileName.isNullOrBlank() &&
                    (metadata.stableKey in targetKeys || metadata.downloadFinalized == true)))
        val exactAudio = referenceIndex.resolve(metadata.mediaUri)?.let(audioByReference::get)
        val audio = if (!metadata.mediaUri.isNullOrBlank()) exactAudio else audioByName[name]?.singleOrNull()
        val valid = identityValid && conflicts[name] != true &&
            (audio != null || audioByName[name].isNullOrEmpty() || name in exactSelectedAttemptNames) &&
            (audio == null || audio.logicalName == name)
        val sidecars = listOfNotNull(metadata.coverPath, metadata.lyricPath, metadata.translatedLyricPath, metadata.romanizedLyricPath)
        if (valid) {
            addKnown(owner.entry.reference)
            addKnown(audio?.reference)
            sidecars.forEach(::addKnown)
        } else {
            sidecars.mapNotNullTo(blockedSidecars, referenceIndex::resolve)
        }
    }
    val unreadable = metadataEntries.filter {
        inventory.metadataByReference[it.reference] !is ManagedMetadataReadResult.Found
    }.mapTo(hashSetOf(), StoredEntry::reference)
    val pending = ManagedDownloadPendingArtifactCleanupPlanner.planUnownedForExplicitClear(
        entries = coreEntries,
        temporaryReferences = inventory.temporaryEntries.mapTo(hashSetOf(), StoredEntry::reference),
        parsedMetadataEntries = parsed,
        unreadableMetadataReferences = unreadable,
        protectedReferences = audioByName.values.asSequence().filter { it.size > 1 }
            .flatten().map(StoredEntry::reference).toSet() + conflictingCoreReferences
    )
    pending.referencesToDelete.forEach(::addKnown)
    requested.removeAll(blockedSidecars)
    requested.removeAll(conflictingCoreReferences - selectedAudioReferences)
    val unresolvedPending = coreEntries.filter { entry ->
        (entry.isPendingAudioWrite || ManagedDownloadTreeNaming.metadataAudioName(entry.name)?.let {
            ManagedDownloadTreeNaming.isPendingMetadataName(entry.name, it)
        } == true) && entry.reference !in requested
    }.mapTo(linkedSetOf(), StoredEntry::reference)
    val retainedPersistedReferenceCount = persistedOwnedReferences.count { reference ->
        referenceIndex.resolve(reference)?.let { it !in requested } == true
    }
    val blockingReasons = buildMap {
        if (conflictingAudioNames.isNotEmpty()) {
            put(ManagedDownloadFullDeleteBlockReason.CONFLICTING_RECEIPT, conflictingAudioNames.size)
        }
        if (unresolvedPending.isNotEmpty()) {
            put(ManagedDownloadFullDeleteBlockReason.UNRESOLVED_PENDING, unresolvedPending.size)
        }
        if (retainedPersistedReferenceCount > 0) {
            put(ManagedDownloadFullDeleteBlockReason.RETAINED_OWNED_REFERENCE, retainedPersistedReferenceCount)
        }
    }
    return ManagedDownloadFullDeletePlan(requested, blockingReasons.isEmpty(), unresolvedPending, blockingReasons)
}
