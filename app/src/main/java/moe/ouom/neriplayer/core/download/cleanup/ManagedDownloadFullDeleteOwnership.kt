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
    if (!inventory.enumerationComplete || inventory.metadataByReference.values.any {
            it is ManagedMetadataReadResult.Unavailable
        }) return ManagedDownloadFullDeletePlan(emptySet(), false)
    val entries = (inventory.rootEntries + inventory.coverEntries + inventory.lyricEntries + inventory.temporaryEntries)
        .filterNot(StoredEntry::isDirectory).distinctBy(StoredEntry::reference)
    val byReference = entries.associateBy(StoredEntry::reference)
    val referenceIndex = ManagedDownloadDeleteReferenceIndex(byReference.keys)
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
    val targetKeys = targets.mapNotNullTo(hashSetOf()) { it.stableKey }
    val requested = linkedSetOf<String>()
    fun addKnown(reference: String?) {
        referenceIndex.resolve(reference)?.let(requested::add)
    }
    persistedOwnedReferences.forEach(::addKnown)
    targets.forEach { target -> audioByReference[referenceIndex.resolve(target.deletionIdentity)]?.reference?.let(::addKnown) }
    selectedSongs.forEach { song ->
        referenceIndex.resolve(resolveDownloadedSongPlaybackReference(song))?.let { audioByReference[it]?.reference }?.let(::addKnown)
        // catalog 可能保存历史猜测封面，侧载归属只能由下方 receipt 或持久删除凭据证明
    }
    val parsed = metadataEntries.mapNotNull { entry ->
        (inventory.metadataByReference[entry.reference] as? ManagedMetadataReadResult.Found)
            ?.let { ManagedDownloadParsedMetadataEntry(entry, it.metadata) }
    }
    val blockedSidecars = hashSetOf<String>()
    var conflictingIdentity = false
    // 每个逻辑音频只计算一次 receipt 身份，避免逐首重扫 inventory
    val conflicts = metadataByName.mapValues { (_, siblings) ->
        val receipts = siblings.mapNotNull { entry ->
            (inventory.metadataByReference[entry.reference] as? ManagedMetadataReadResult.Found)?.metadata
        }
        listOf<(DownloadedAudioMetadata) -> String?>(
            { it.stableKey }, { it.publicationOwnerId() }, { it.artifactId }, { it.libraryId }
        ).any { field -> receipts.mapNotNull { field(it)?.takeIf(String::isNotBlank) }.distinct().size > 1 }
    }
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
            (audio != null || audioByName[name].isNullOrEmpty()) &&
            (audio == null || audio.logicalName == name)
        val sidecars = listOfNotNull(metadata.coverPath, metadata.lyricPath, metadata.translatedLyricPath, metadata.romanizedLyricPath)
        if (valid) {
            addKnown(owner.entry.reference)
            addKnown(audio?.reference)
            sidecars.forEach(::addKnown)
        } else {
            sidecars.mapNotNullTo(blockedSidecars, referenceIndex::resolve)
            if (identityValid && conflicts[name] == true) conflictingIdentity = true
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
            .flatten().map(StoredEntry::reference).toSet()
    )
    pending.referencesToDelete.forEach(::addKnown)
    requested.removeAll(blockedSidecars)
    val unresolvedPending = coreEntries.filter { entry ->
        (entry.isPendingAudioWrite || ManagedDownloadTreeNaming.metadataAudioName(entry.name)?.let {
            ManagedDownloadTreeNaming.isPendingMetadataName(entry.name, it)
        } == true) && entry.reference !in requested
    }.mapTo(linkedSetOf(), StoredEntry::reference)
    if (conflictingIdentity) return ManagedDownloadFullDeletePlan(emptySet(), false, unresolvedPending)
    val retainedPersistedReference = persistedOwnedReferences.any { reference ->
        referenceIndex.resolve(reference)?.let { it !in requested } == true
    }
    return ManagedDownloadFullDeletePlan(requested, unresolvedPending.isEmpty() && !retainedPersistedReference, unresolvedPending)
}
