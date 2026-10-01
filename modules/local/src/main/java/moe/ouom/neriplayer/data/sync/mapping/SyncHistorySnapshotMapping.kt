package moe.ouom.neriplayer.data.sync.mapping

import android.content.Context
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.sync.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.history.PlayedEntry

internal fun buildRecentPlaySyncSnapshots(history: List<PlayedEntry>, readDeviceId: () -> String, localizedContext: Context): List<SyncRecentPlay> {
    val syncRecentPlays = history
        .filterNot {
            !it.localFilePath.isNullOrBlank() ||
                LocalSongSupport.isLocalSong(it.album, it.mediaUri, it.albumId, localizedContext)
        }
        .take(500)
        .map { playedEntry ->
            SyncRecentPlay(
                songId = playedEntry.id,
                song = SyncSong(
                    id = playedEntry.id,
                    name = playedEntry.name,
                    artist = playedEntry.artist,
                    album = playedEntry.album,
                    albumId = playedEntry.albumId,
                    durationMs = playedEntry.durationMs,
                    coverUrl = playedEntry.coverUrl,
                    mediaUri = LocalSongSupport.sanitizeMediaUriForSync(playedEntry.mediaUri),
                    matchedLyric = playedEntry.matchedLyric,
                    matchedTranslatedLyric = playedEntry.matchedTranslatedLyric,
                    customCoverUrl = playedEntry.customCoverUrl,
                    customName = playedEntry.customName,
                    customArtist = playedEntry.customArtist,
                    originalName = playedEntry.originalName,
                    originalArtist = playedEntry.originalArtist,
                    originalCoverUrl = playedEntry.originalCoverUrl,
                    originalLyric = playedEntry.originalLyric,
                    originalTranslatedLyric = playedEntry.originalTranslatedLyric,
                    syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION
                ),
                playedAt = playedEntry.playedAt,
                deviceId = readDeviceId(),
                resumePositionMs = playedEntry.resumePositionMs
            )
        }
    return syncRecentPlays
}
