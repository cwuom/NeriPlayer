package moe.ouom.neriplayer.data.sync.change

import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.identity.identity

internal object SyncHistoryChangeDetector {
    fun changed(remote: SyncData, merged: SyncData): Boolean =
        SyncCollectionComparison.orderedChanged(remote.recentPlays, merged.recentPlays, ::samePlay) ||
            SyncCollectionComparison.orderedChanged(remote.recentPlayDeletions, merged.recentPlayDeletions, ::sameRecentDeletion) ||
            SyncCollectionComparison.orderedChanged(remote.playlistSongDeletions, merged.playlistSongDeletions, ::sameSongDeletion)

    private fun samePlay(a: SyncRecentPlay, b: SyncRecentPlay): Boolean =
        a.song.identity() == b.song.identity() && SyncSongMetadataComparison.same(a.song, b.song) &&
            a.playedAt == b.playedAt && a.resumePositionMs == b.resumePositionMs

    private fun sameRecentDeletion(a: SyncRecentPlayDeletion, b: SyncRecentPlayDeletion): Boolean =
        a.identity() == b.identity() && a.deletedAt == b.deletedAt && a.deviceId == b.deviceId

    private fun sameSongDeletion(a: SyncPlaylistSongDeletion, b: SyncPlaylistSongDeletion): Boolean =
        a.playlistId == b.playlistId && a.identity() == b.identity() && a.deletedAt == b.deletedAt &&
            a.deviceId == b.deviceId && a.removedMembershipTokens.orEmpty().toSet() == b.removedMembershipTokens.orEmpty().toSet()
}
