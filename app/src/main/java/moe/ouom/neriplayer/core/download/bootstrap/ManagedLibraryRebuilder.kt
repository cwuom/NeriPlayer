package moe.ouom.neriplayer.core.download.bootstrap

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.isFinalizedDownloadedAudioEntry

internal data class ManagedLibraryRebuildItem(
    val audio: ManagedDownloadStorage.StoredEntry,
    val metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
    val stableKey: String?,
    val artifactId: String?,
    val logicalTimeMs: Long?
)

internal object ManagedLibraryRebuilder {
    fun plan(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        allowIncompleteRootPreview: Boolean = false
    ): List<ManagedLibraryRebuildItem> {
        return snapshot.audioEntries.mapNotNull { audio ->
            val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)
            if (
                !isFinalizedDownloadedAudioEntry(
                    rootEntriesComplete = snapshot.rootEntriesComplete ||
                        allowIncompleteRootPreview,
                    isPendingAudioWrite = audio.isPendingAudioWrite,
                    metadata = metadata
                )
            ) {
                return@mapNotNull null
            }
            ManagedLibraryRebuildItem(
                audio = audio,
                metadata = metadata,
                stableKey = metadata?.stableKey?.takeIf(String::isNotBlank),
                artifactId = metadata?.artifactId?.takeIf(String::isNotBlank),
                logicalTimeMs = logicalTimeMs(metadata, audio)
            )
        }
    }

    fun logicalTimeMs(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        audio: ManagedDownloadStorage.StoredEntry
    ): Long? {
        return metadata?.downloadTimeMs?.takeIf { it > 0L }
            ?: metadata?.createdAtMs?.takeIf { it > 0L }
            ?: metadata?.libraryAddedAtMs?.takeIf { it > 0L }
            ?: audio.lastModifiedMs.takeIf { it > 0L }
    }
}
