package moe.ouom.neriplayer.core.download.bootstrap

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.model.isFinalizedDownloadedAudioEntry

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
            if (metadata?.audioPublicationPending == true) {
                return@mapNotNull null
            }
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
            if (!hasKnownFinalizedSidecars(snapshot, metadata)) {
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

    /** 侧载目录完整时，缺失的受要求资源不能继续作为已下载成品展示 */
    private fun hasKnownFinalizedSidecars(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
    ): Boolean {
        if (metadata == null || !snapshot.sidecarEntriesComplete) {
            return true
        }
        return hasRequiredReference(
            snapshot = snapshot,
            reference = metadata.coverPath,
            required = hasText(
                metadata.coverUrl,
                metadata.customCoverUrl,
                metadata.originalCoverUrl
            )
        ) && hasRequiredReference(
            snapshot = snapshot,
            reference = metadata.lyricPath,
            required = hasText(metadata.matchedLyric, metadata.originalLyric)
        ) && hasRequiredReference(
            snapshot = snapshot,
            reference = metadata.translatedLyricPath,
            required = hasText(
                metadata.matchedTranslatedLyric,
                metadata.originalTranslatedLyric
            )
        ) && hasRequiredReference(
            snapshot = snapshot,
            reference = metadata.romanizedLyricPath,
            required = hasText(
                metadata.matchedRomanizedLyric,
                metadata.originalRomanizedLyric
            )
        )
    }

    private fun hasRequiredReference(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        reference: String?,
        required: Boolean
    ): Boolean {
        val normalizedReference = reference?.trim()?.takeIf(String::isNotBlank)
        if (normalizedReference == null) {
            return !required
        }
        return normalizedReference in snapshot.knownReferences ||
            snapshot.coverEntriesByName.values.any { entry ->
                entry.reference == normalizedReference || entry.mediaUri == normalizedReference
            } || snapshot.lyricEntriesByName.values.any { entry ->
                entry.reference == normalizedReference || entry.mediaUri == normalizedReference
            }
    }

    private fun hasText(vararg values: String?): Boolean {
        return values.any { value -> !value.isNullOrBlank() }
    }
}
