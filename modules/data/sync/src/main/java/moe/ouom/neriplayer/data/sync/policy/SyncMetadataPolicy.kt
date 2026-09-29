package moe.ouom.neriplayer.data.sync.policy

import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.normalizedSyncCausalTokens

fun mergePositiveTimestamp(left: Long, right: Long): Long {
    return when {
        left <= 0L -> right.coerceAtLeast(0L)
        right <= 0L -> left
        else -> minOf(left, right)
    }
}

fun SyncSong.copyWithNormalizedMembershipTokens(
    mediaUri: String? = this.mediaUri,
    addedAt: Long = this.addedAt,
    legacyAddedAt: Long? = this.legacyAddedAt
): SyncSong {
    return copy(
        mediaUri = mediaUri,
        addedAt = addedAt,
        legacyAddedAt = legacyAddedAt,
        syncMembershipTokens = syncMembershipTokens.normalizedSyncCausalTokens()
    )
}

fun SyncPlaylistSongDeletion.copyWithNormalizedMembershipTokens(
    mediaUri: String? = this.mediaUri
): SyncPlaylistSongDeletion {
    return copy(
        mediaUri = mediaUri,
        removedMembershipTokens = removedMembershipTokens.normalizedSyncCausalTokens()
    )
}

fun SyncSong.hasResolvableSyncIdentity(): Boolean {
    return id != 0L ||
        audioId?.isNotBlank() == true ||
        mediaUri?.isNotBlank() == true
}

fun SyncRecentPlayDeletion.hasResolvableSyncIdentity(): Boolean {
    return songId != 0L || mediaUri?.isNotBlank() == true
}

fun SyncPlaylistSongDeletion.hasResolvableSyncIdentity(): Boolean {
    return songId != 0L || mediaUri?.isNotBlank() == true
}
