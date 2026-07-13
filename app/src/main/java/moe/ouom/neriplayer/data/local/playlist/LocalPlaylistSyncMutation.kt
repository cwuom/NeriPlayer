package moe.ouom.neriplayer.data.local.playlist

import androidx.annotation.Keep
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.github.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import java.util.concurrent.atomic.AtomicLong

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
    val clearedPlaylistDeletionIds: List<Long> = emptyList(),
    val restoredPlaylistIds: List<Long> = emptyList()
) {
    val isEmpty: Boolean
        get() = addedSongDeletions.isEmpty() &&
            removedSongDeletions.isEmpty() &&
            deletedPlaylistIds.isEmpty() &&
            clearedPlaylistDeletionIds.isEmpty() &&
            restoredPlaylistIds.orEmpty().isEmpty()

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
                (clearedPlaylistDeletionIds + other.clearedPlaylistDeletionIds).distinct(),
            restoredPlaylistIds = (
                restoredPlaylistIds.orEmpty() + other.restoredPlaylistIds.orEmpty()
            ).distinct()
        )
    }
}

@Keep
internal data class LocalPlaylistSyncMutationOutbox(
    val mutations: List<LocalPlaylistSyncMutation> = emptyList()
)

internal interface LocalPlaylistSyncMutationStore {
    fun getOrCreateDeviceId(): String

    fun nextSyncCausalTokens(count: Int): List<SyncCausalToken>

    fun getSyncMutationVersion(): Long

    fun markSyncMutation(): Long

    fun apply(mutation: LocalPlaylistSyncMutation)
}

internal class SecureLocalPlaylistSyncMutationStore(
    private val storage: SecureTokenStorage
) : LocalPlaylistSyncMutationStore {
    override fun getOrCreateDeviceId(): String = storage.getOrCreateDeviceId()

    override fun nextSyncCausalTokens(count: Int): List<SyncCausalToken> {
        return storage.nextSyncCausalTokens(count)
    }

    override fun getSyncMutationVersion(): Long = storage.getSyncMutationVersion()

    override fun markSyncMutation(): Long = storage.markSyncMutation()

    override fun apply(mutation: LocalPlaylistSyncMutation) {
        if (mutation.addedSongDeletions.isNotEmpty()) {
            storage.addPlaylistSongDeletions(mutation.addedSongDeletions)
        }
        mutation.removedSongDeletions.forEach { removal ->
            storage.removePlaylistSongDeletions(removal.playlistId, removal.identities)
        }
        mutation.deletedPlaylistIds.forEach(storage::addDeletedPlaylistId)
        mutation.clearedPlaylistDeletionIds.forEach(storage::removePlaylistSongDeletionsForPlaylist)
        storage.removeDeletedPlaylistIds(mutation.restoredPlaylistIds.orEmpty().toSet())
    }
}

internal class InMemoryLocalPlaylistSyncMutationStore : LocalPlaylistSyncMutationStore {
    private val nextCounter = AtomicLong(1L)
    private val mutationVersion = AtomicLong(0L)

    override fun getOrCreateDeviceId(): String = "in-memory-sync-device"

    override fun nextSyncCausalTokens(count: Int): List<SyncCausalToken> {
        require(count >= 0)
        return List(count) {
            SyncCausalToken(
                deviceId = getOrCreateDeviceId(),
                counter = nextCounter.getAndIncrement()
            )
        }
    }

    override fun getSyncMutationVersion(): Long = mutationVersion.get()

    override fun markSyncMutation(): Long = mutationVersion.incrementAndGet()

    override fun apply(mutation: LocalPlaylistSyncMutation) = Unit
}
