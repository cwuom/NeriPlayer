package moe.ouom.neriplayer.data.testing

import moe.ouom.neriplayer.data.model.download.DownloadLibraryEntry
import moe.ouom.neriplayer.data.model.download.DownloadLibrarySnapshot
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata

data class FakeLibraryEntry(
    override val name: String,
    override val reference: String = "content://tree/document/$name",
    override val mediaUri: String = reference,
    override val localFilePath: String? = null,
    override val sizeBytes: Long = 1L,
    override val lastModifiedMs: Long = 1L,
    override val sizeKnown: Boolean = true,
    override val isDirectory: Boolean = false,
    override val isPendingAudioWrite: Boolean = false,
    override val logicalName: String = name,
    override val extension: String = name.substringAfterLast('.', ""),
    override val nameWithoutExtension: String = name.substringBeforeLast('.'),
    override val playbackUri: String = mediaUri,
    override val displayName: String = name
) : DownloadLibraryEntry

data class FakeLibrarySnapshot(
    override val audioEntries: List<DownloadLibraryEntry> = emptyList(),
    override val audioEntriesByLookupKey: Map<String, DownloadLibraryEntry> = emptyMap(),
    override val metadataEntriesByAudioName: Map<String, DownloadLibraryEntry> = emptyMap(),
    override val metadataByAudioName: Map<String, DownloadedAudioMetadata> = emptyMap(),
    override val coverEntriesByName: Map<String, DownloadLibraryEntry> = emptyMap(),
    override val lyricEntriesByName: Map<String, DownloadLibraryEntry> = emptyMap(),
    override val knownReferences: Set<String> = emptySet(),
    override val rootEntriesComplete: Boolean = true,
    override val sidecarEntriesComplete: Boolean = true,
    override val pendingAudioEntries: List<DownloadLibraryEntry> = emptyList(),
    override val pendingMetadataByAudioName: Map<String, DownloadedAudioMetadata> = emptyMap(),
    override val metadataEntriesByCanonicalAudioName: Map<String, DownloadLibraryEntry> = emptyMap(),
    override val metadataByCanonicalAudioName: Map<String, DownloadedAudioMetadata> = emptyMap(),
    override val pendingMetadataByCanonicalAudioName: Map<String, DownloadedAudioMetadata> = emptyMap(),
    override val metadataByDeclaredAudioName: Map<String, DownloadedAudioMetadata> = emptyMap()
) : DownloadLibrarySnapshot {
    override fun metadataForAudioEntry(audio: DownloadLibraryEntry): DownloadedAudioMetadata? = null
}
