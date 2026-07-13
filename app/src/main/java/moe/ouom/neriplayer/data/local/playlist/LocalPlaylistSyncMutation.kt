package moe.ouom.neriplayer.data.local.playlist

import androidx.annotation.Keep
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.github.SyncPlaylistSongDeletion

@Keep
internal data class PlaylistSongDeletionRemoval(
    val playlistId: Long,
    val identities: List<SongIdentity>
)

@Keep
internal data class LocalPlaylistSyncMutation(
    val expectedPrimaryDigest: String = "",
    val addedSongDeletions: List<SyncPlaylistSongDeletion> = emptyList(),
    val removedSongDeletions: List<PlaylistSongDeletionRemoval> = emptyList(),
    val deletedPlaylistIds: List<Long> = emptyList(),
    val clearedPlaylistDeletionIds: List<Long> = emptyList()
) {
    val isEmpty: Boolean
        get() = addedSongDeletions.isEmpty() &&
            removedSongDeletions.isEmpty() &&
            deletedPlaylistIds.isEmpty() &&
            clearedPlaylistDeletionIds.isEmpty()

    fun withExpectedPrimaryDigest(digest: String): LocalPlaylistSyncMutation {
        return copy(expectedPrimaryDigest = digest)
    }

    operator fun plus(other: LocalPlaylistSyncMutation): LocalPlaylistSyncMutation {
        if (isEmpty) return other
        if (other.isEmpty) return this
        return LocalPlaylistSyncMutation(
            addedSongDeletions = addedSongDeletions + other.addedSongDeletions,
            removedSongDeletions = removedSongDeletions + other.removedSongDeletions,
            deletedPlaylistIds = (deletedPlaylistIds + other.deletedPlaylistIds).distinct(),
            clearedPlaylistDeletionIds =
                (clearedPlaylistDeletionIds + other.clearedPlaylistDeletionIds).distinct()
        )
    }
}

@Keep
internal data class LocalPlaylistSyncMutationOutbox(
    val mutations: List<LocalPlaylistSyncMutation> = emptyList()
)

internal interface LocalPlaylistSyncMutationStore {
    fun getOrCreateDeviceId(): String

    fun apply(mutation: LocalPlaylistSyncMutation)
}

internal class SecureLocalPlaylistSyncMutationStore(
    private val storage: SecureTokenStorage
) : LocalPlaylistSyncMutationStore {
    override fun getOrCreateDeviceId(): String = storage.getOrCreateDeviceId()

    override fun apply(mutation: LocalPlaylistSyncMutation) {
        if (mutation.addedSongDeletions.isNotEmpty()) {
            storage.addPlaylistSongDeletions(mutation.addedSongDeletions)
        }
        mutation.removedSongDeletions.forEach { removal ->
            storage.removePlaylistSongDeletions(removal.playlistId, removal.identities)
        }
        mutation.deletedPlaylistIds.forEach(storage::addDeletedPlaylistId)
        mutation.clearedPlaylistDeletionIds.forEach(storage::removePlaylistSongDeletionsForPlaylist)
    }
}
