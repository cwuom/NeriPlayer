package moe.ouom.neriplayer.core.download.cleanup

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.isUnfinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.audioExtensions

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

        val audioEntriesByName = rootEntries
            .filter { entry -> entry.extension in audioExtensions }
            .associateBy(ManagedDownloadStorage.StoredEntry::name)
        val protectedReferences = protectedSidecarReferences(
            parsedMetadataEntries = parsedMetadataEntries,
            managedSidecarReferences = managedSidecarReferences
        )

        return linkedSetOf<String>().apply {
            unfinalizedMetadataEntries.forEach { parsed ->
                val audio = audioEntriesByName[parsed.entry.name.removeSuffix(METADATA_SUFFIX)]
                if (audio?.sizeBytes?.let { it > 0L } == true) {
                    return@forEach
                }

                add(parsed.entry.reference)
                audio?.reference?.let(::add)
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
        return listOf(metadata.coverPath, metadata.lyricPath, metadata.translatedLyricPath)
            .mapNotNull { reference -> reference?.takeIf(String::isNotBlank) }
            .filter(managedSidecarReferences::contains)
    }
}
