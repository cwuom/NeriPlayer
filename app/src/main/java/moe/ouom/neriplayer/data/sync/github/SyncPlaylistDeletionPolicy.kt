package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.sync.model.normalizedSyncCausalTokens

internal object SyncPlaylistDeletionPolicy {
    private val deletionMirrorComparator =
        compareBy<SyncPlaylistSongDeletion> { it.deletedAt }
            .thenBy { it.deviceId }

    private val deletionOrderComparator =
        compareByDescending<SyncPlaylistSongDeletion> { it.deletedAt }
            .thenByDescending { it.deviceId }
            .thenBy(SyncPlaylistSongDeletion::stableKey)

    fun mergeDeletions(
        local: List<SyncPlaylistSongDeletion>,
        remote: List<SyncPlaylistSongDeletion>
    ): List<SyncPlaylistSongDeletion> {
        return (local + remote)
            .groupBy(SyncPlaylistSongDeletion::stableKey)
            .mapNotNull { (_, snapshots) -> mergeDeletionSnapshots(snapshots) }
            .sortedWith(deletionOrderComparator)
    }

    fun applyDeletions(
        playlistId: Long,
        songs: List<SyncSong>,
        deletions: List<SyncPlaylistSongDeletion>
    ): List<SyncSong> {
        if (songs.isEmpty() || deletions.isEmpty()) {
            return songs
        }

        val deletionsBySong = mergeDeletions(deletions, emptyList())
            .asSequence()
            .filter { it.playlistId == playlistId }
            .associateBy { it.identity().stableKey() }
        if (deletionsBySong.isEmpty()) {
            return songs
        }

        return songs.mapNotNull { song ->
            val deletion = deletionsBySong[song.identity().stableKey()]
            if (deletion == null) song else applyDeletion(song, deletion)
        }
    }

    fun pruneResolvedDeletions(
        deletions: List<SyncPlaylistSongDeletion>,
        playlists: List<SyncPlaylist>
    ): List<SyncPlaylistSongDeletion> {
        if (deletions.isEmpty()) {
            return emptyList()
        }

        val normalizedDeletions = mergeDeletions(deletions, emptyList())
        val deletionByKey = normalizedDeletions.associateBy(SyncPlaylistSongDeletion::stableKey)
        val resolvedKeys = buildSet {
            playlists.asSequence()
                .filterNot(SyncPlaylist::isDeleted)
                .forEach { playlist ->
                    playlist.songs.forEach { song ->
                        val key = "${playlist.id}|${song.identity().stableKey()}"
                        val deletion = deletionByKey[key] ?: return@forEach
                        if (
                            deletion.removedMembershipTokens.orEmpty().isEmpty() &&
                            effectiveAddedAt(song) > deletion.deletedAt
                        ) {
                            add(key)
                        }
                    }
                }
        }

        return normalizedDeletions
            .filterNot { it.stableKey() in resolvedKeys }
            .sortedWith(deletionOrderComparator)
    }

    fun clearLegacyDeletionsForReaddedSongs(
        deletions: List<SyncPlaylistSongDeletion>,
        playlistId: Long,
        identities: Collection<SongIdentity>
    ): List<SyncPlaylistSongDeletion> {
        if (deletions.isEmpty() || identities.isEmpty()) return deletions

        val readdedKeys = identities.mapTo(mutableSetOf()) { identity ->
            "$playlistId|${identity.stableKey()}"
        }
        return mergeDeletions(deletions, emptyList()).filterNot { deletion ->
            deletion.stableKey() in readdedKeys &&
                deletion.removedMembershipTokens.orEmpty().isEmpty()
        }
    }

    private fun mergeDeletionSnapshots(
        snapshots: List<SyncPlaylistSongDeletion>
    ): SyncPlaylistSongDeletion? {
        val mirror = snapshots.maxWithOrNull(deletionMirrorComparator) ?: return null
        val removedTokens = snapshots
            .flatMap { it.removedMembershipTokens.orEmpty() }
            .normalizedSyncCausalTokens()
        return if (removedTokens == mirror.removedMembershipTokens) {
            mirror
        } else {
            mirror.copy(removedMembershipTokens = removedTokens)
        }
    }

    private fun applyDeletion(
        song: SyncSong,
        deletion: SyncPlaylistSongDeletion
    ): SyncSong? {
        val songTokens = song.syncMembershipTokens.orEmpty().normalizedSyncCausalTokens()
        val removedTokens = deletion.removedMembershipTokens.orEmpty().normalizedSyncCausalTokens()
        if (songTokens.isEmpty() || removedTokens.isEmpty()) {
            return song.takeIf { effectiveAddedAt(it) > deletion.deletedAt }
        }

        val removedTokenSet = removedTokens.toHashSet()
        val remainingTokens = songTokens
            .filterNot(removedTokenSet::contains)
            .normalizedSyncCausalTokens()
        if (remainingTokens.isEmpty()) return null
        return if (remainingTokens == song.syncMembershipTokens.orEmpty()) {
            song
        } else {
            song.copy(syncMembershipTokens = remainingTokens)
        }
    }

    private fun effectiveAddedAt(song: SyncSong): Long {
        return song.addedAt.takeIf { it > 0L } ?: Long.MIN_VALUE
    }
}
