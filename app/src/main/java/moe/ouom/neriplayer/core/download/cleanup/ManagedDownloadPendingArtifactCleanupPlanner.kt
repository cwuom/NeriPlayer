package moe.ouom.neriplayer.core.download.cleanup

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming

/**
 * plans removal only for a cancelled operation whose pending metadata proves ownership
 */
internal object ManagedDownloadPendingArtifactCleanupPlanner {
    fun planCancelledOperationReferences(
        rootEntries: List<ManagedDownloadStorage.StoredEntry>,
        parsedMetadataEntries: List<ManagedDownloadParsedMetadataEntry>,
        stableKey: String,
        operationId: String
    ): Set<String> {
        val normalizedStableKey = stableKey.trim().takeIf(String::isNotBlank)
            ?: return emptySet()
        val normalizedOperationId = operationId.trim().takeIf(String::isNotBlank)
            ?: return emptySet()
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
            return emptySet()
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
}
