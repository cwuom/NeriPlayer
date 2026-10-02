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
    val tokens = if (syncMembershipTokens.isNullOrEmpty()) emptyList() else syncMembershipTokens.normalizedSyncCausalTokens()
    if (mediaUri == this.mediaUri && addedAt == this.addedAt && legacyAddedAt == this.legacyAddedAt &&
        tokens == syncMembershipTokens
    ) return this
    return copy(
        mediaUri = mediaUri,
        addedAt = addedAt,
        legacyAddedAt = legacyAddedAt,
        syncMembershipTokens = tokens
    )
}

fun SyncPlaylistSongDeletion.copyWithNormalizedMembershipTokens(
    mediaUri: String? = this.mediaUri
): SyncPlaylistSongDeletion {
    val tokens = if (removedMembershipTokens.isNullOrEmpty()) emptyList() else removedMembershipTokens.normalizedSyncCausalTokens()
    if (mediaUri == this.mediaUri && tokens == removedMembershipTokens) return this
    return copy(
        mediaUri = mediaUri,
        removedMembershipTokens = tokens
    )
}

fun SyncSong.hasResolvableSyncIdentity(): Boolean {
    return id != 0L ||
        hasSyncIdentityText(audioId) ||
        hasSyncIdentityText(mediaUri)
}

fun SyncRecentPlayDeletion.hasResolvableSyncIdentity(): Boolean {
    return songId != 0L || hasSyncIdentityText(mediaUri)
}

fun SyncPlaylistSongDeletion.hasResolvableSyncIdentity(): Boolean {
    return songId != 0L || hasSyncIdentityText(mediaUri)
}

private fun hasSyncIdentityText(value: String?): Boolean = !value.isNullOrBlank()
