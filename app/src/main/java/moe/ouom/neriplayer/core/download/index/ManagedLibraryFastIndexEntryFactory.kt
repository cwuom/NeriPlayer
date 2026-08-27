package moe.ouom.neriplayer.core.download.index

import moe.ouom.neriplayer.core.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey

internal object ManagedLibraryFastIndexEntryFactory {
    fun fromCompletedDownload(
        libraryId: String,
        song: SongItem,
        audio: ManagedDownloadStorage.StoredEntry,
        state: String,
        coverPath: String? = null,
        metadataEmbeddingState: DownloadedAudioEmbeddingState? = null,
        updatedAtMs: Long = System.currentTimeMillis()
    ): ManagedLibraryIndexEntry {
        val identity = song.identity()
        val stableKey = identity.stableKey()
        return ManagedLibraryIndexEntry(
            stableKey = stableKey,
            artifactId = "managed:$libraryId:$stableKey",
            audioName = audio.logicalName,
            audioReference = audio.reference,
            metadataName = "${audio.logicalName}$METADATA_SUFFIX",
            state = state,
            metadataEmbeddingState = metadataEmbeddingState,
            downloadTimeMs = audio.lastModifiedMs.takeIf { it > 0L },
            updatedAtMs = updatedAtMs,
            songId = song.id.takeIf { it > 0L },
            title = song.name,
            artist = song.artist,
            album = song.album,
            mediaUri = identity.mediaUri ?: song.mediaUri,
            channelId = song.channelId,
            audioId = song.audioId,
            subAudioId = song.subAudioId,
            playlistContextId = song.playlistContextId,
            durationMs = song.durationMs.takeIf { it > 0L },
            coverPath = coverPath
        )
    }
}
