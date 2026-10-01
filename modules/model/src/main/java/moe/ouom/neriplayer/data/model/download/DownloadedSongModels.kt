package moe.ouom.neriplayer.data.model.download

data class DownloadedSong(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val filePath: String,
    val fileSize: Long,
    val downloadTime: Long,
    val coverPath: String? = null,
    val coverUrl: String? = null,
    val matchedLyric: String? = null,
    val matchedTranslatedLyric: String? = null,
    val matchedRomanizedLyric: String? = null,
    val matchedLyricSource: String? = null,
    val matchedSongId: String? = null,
    val userLyricOffsetMs: Long = 0L,
    val customCoverUrl: String? = null,
    val customName: String? = null,
    val customArtist: String? = null,
    val originalName: String? = null,
    val originalArtist: String? = null,
    val originalCoverUrl: String? = null,
    val originalLyric: String? = null,
    val originalTranslatedLyric: String? = null,
    val originalRomanizedLyric: String? = null,
    val mediaUri: String? = null,
    val durationMs: Long = 0L,
    val stableKey: String? = null,
    val sourceIdentityAlbum: String? = null,
    val sourceMediaUri: String? = null,
    val sourceChannelId: String? = null,
    val sourceAudioId: String? = null,
    val sourceSubAudioId: String? = null,
    val sourcePlaylistContextId: String? = null,
    val localFileName: String? = null
) {
    fun displayName(): String = customName ?: name
    fun displayArtist(): String = customArtist ?: artist

    fun deletionIdentity(): String {
        return mediaUri
            ?.takeIf(String::isNotBlank)
            ?: filePath
    }
}

data class DownloadedSongDeleteResult(
    val deletedSongs: List<DownloadedSong>,
    val failedSongs: List<DownloadedSong>,
    val physicalCleanupPending: Boolean = false
) {
    companion object {
        fun empty(): DownloadedSongDeleteResult {
            return DownloadedSongDeleteResult(
                deletedSongs = emptyList(),
                failedSongs = emptyList()
            )
        }
    }
}

enum class DownloadedSongDeletePhase {
    PREPARING,
    WAITING_FOR_DIRECTORY,
    STOPPING_DOWNLOADS,
    WAITING_FOR_DOWNLOADS,
    READING_DELETE_PLAN,
    DELETING_REFERENCES,
    VERIFYING_REFERENCES,
    FINALIZING,
    COMPLETED,
    FAILED
}

data class DownloadedSongDeleteProgress(
    val deleteId: Long,
    val phase: DownloadedSongDeletePhase,
    val requestedSongCount: Int,
    val totalReferenceCount: Int? = null,
    val completedReferenceCount: Int = 0,
    val failedReferenceCount: Int = 0,
    val fullLibraryDelete: Boolean = false
)
