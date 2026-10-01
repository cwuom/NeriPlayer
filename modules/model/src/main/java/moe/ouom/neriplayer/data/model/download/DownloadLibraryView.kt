package moe.ouom.neriplayer.data.model.download

interface DownloadLibraryEntry {
    val name: String
    val reference: String
    val mediaUri: String
    val localFilePath: String?
    val sizeBytes: Long
    val lastModifiedMs: Long
    val sizeKnown: Boolean
    val isDirectory: Boolean
    val isPendingAudioWrite: Boolean
    val logicalName: String
    val extension: String
    val nameWithoutExtension: String
    val playbackUri: String
    val displayName: String
}

interface DownloadLibrarySnapshot {
    val audioEntries: List<DownloadLibraryEntry>
    val audioEntriesByLookupKey: Map<String, DownloadLibraryEntry>
    val metadataEntriesByAudioName: Map<String, DownloadLibraryEntry>
    val metadataByAudioName: Map<String, DownloadedAudioMetadata>
    val coverEntriesByName: Map<String, DownloadLibraryEntry>
    val lyricEntriesByName: Map<String, DownloadLibraryEntry>
    val knownReferences: Set<String>
    val rootEntriesComplete: Boolean
    val sidecarEntriesComplete: Boolean
    val pendingAudioEntries: List<DownloadLibraryEntry>
    val pendingMetadataByAudioName: Map<String, DownloadedAudioMetadata>
    val metadataEntriesByCanonicalAudioName: Map<String, DownloadLibraryEntry>
    val metadataByCanonicalAudioName: Map<String, DownloadedAudioMetadata>
    val pendingMetadataByCanonicalAudioName: Map<String, DownloadedAudioMetadata>
    val metadataByDeclaredAudioName: Map<String, DownloadedAudioMetadata>

    fun metadataForAudioEntry(audio: DownloadLibraryEntry): DownloadedAudioMetadata?
}
