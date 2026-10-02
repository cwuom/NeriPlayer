package moe.ouom.neriplayer.data.sync.mapping

import android.content.Context
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.history.toSongItem
import moe.ouom.neriplayer.data.model.history.PlayedEntry

internal fun buildRecentPlaySyncSnapshots(history: List<PlayedEntry>, readDeviceId: () -> String, localizedContext: Context, optimizeLegacyLyrics: Boolean = false): List<SyncRecentPlay> {
    val syncRecentPlays = history
        .filterNot {
            !it.localFilePath.isNullOrBlank() ||
                LocalSongSupport.isLocalSong(it.album, it.mediaUri, it.albumId, localizedContext)
        }
        .map { playedEntry ->
            SyncRecentPlay(
                songId = playedEntry.id,
                song = SyncSong.fromSongItem(playedEntry.toSongItem(), localizedContext, optimizeLegacyLyrics),
                playedAt = playedEntry.playedAt,
                deviceId = readDeviceId(),
                resumePositionMs = playedEntry.resumePositionMs
            )
        }
    return syncRecentPlays
}
