package moe.ouom.neriplayer.core.download.cleanup

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.isUnfinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming

internal data class ManagedDownloadParsedMetadataEntry(
    val entry: ManagedDownloadStorage.StoredEntry,
    val metadata: ManagedDownloadStorage.DownloadedAudioMetadata
)

internal object ManagedDownloadUnfinalizedCleanupPlanner {
    fun planReferencesToDelete(
        rootEntries: List<ManagedDownloadStorage.StoredEntry>,
        parsedMetadataEntries: List<ManagedDownloadParsedMetadataEntry>,
        managedSidecarReferences: Set<String>
    ): Set<String> {
        val unfinalizedMetadataEntries = parsedMetadataEntries
            .filter { parsed -> isUnfinalizedDownloadedMetadata(parsed.metadata) }
        if (unfinalizedMetadataEntries.isEmpty()) {
            return emptySet()
        }

        val audioEntriesByLogicalName = rootEntries
            .filter { entry ->
                entry.extension in audioExtensions || entry.isPendingAudioWrite
            }
            .groupBy(ManagedDownloadStorage.StoredEntry::logicalName)
        val protectedReferences = protectedSidecarReferences(
            parsedMetadataEntries = parsedMetadataEntries,
            managedSidecarReferences = managedSidecarReferences
        )

        return linkedSetOf<String>().apply {
            unfinalizedMetadataEntries.forEach { parsed ->
                val audioEntries = ManagedDownloadTreeNaming.metadataAudioName(parsed.entry.name)
                    ?.let(audioEntriesByLogicalName::get)
                    .orEmpty()
                if (audioEntries.any { audio -> audio.sizeBytes > 0L }) {
                    return@forEach
                }

                add(parsed.entry.reference)
                audioEntries.mapTo(this, ManagedDownloadStorage.StoredEntry::reference)
                sidecarReferences(parsed.metadata, managedSidecarReferences)
                    .filterNot(protectedReferences::contains)
                    .forEach(::add)
            }
        }
    }

    private fun protectedSidecarReferences(
        parsedMetadataEntries: List<ManagedDownloadParsedMetadataEntry>,
        managedSidecarReferences: Set<String>
    ): Set<String> {
        return parsedMetadataEntries
            .asSequence()
            .filterNot { parsed -> isUnfinalizedDownloadedMetadata(parsed.metadata) }
            .flatMap { parsed -> sidecarReferences(parsed.metadata, managedSidecarReferences).asSequence() }
            .toSet()
    }

    private fun sidecarReferences(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata,
        managedSidecarReferences: Set<String>
    ): List<String> {
        return listOf(
            metadata.coverPath,
            metadata.lyricPath,
            metadata.translatedLyricPath,
            metadata.romanizedLyricPath
        )
            .mapNotNull { reference -> reference?.takeIf(String::isNotBlank) }
            .filter(managedSidecarReferences::contains)
    }
}
