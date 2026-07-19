package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.model.SyncPlaylist
import moe.ouom.neriplayer.data.sync.model.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.model.SyncSong
import moe.ouom.neriplayer.data.sync.model.normalizedSyncCausalTokens

internal object SyncPlaylistDeletionPolicy {
    private val deletionMirrorComparator =
        compareBy<SyncPlaylistSongDeletion> { it.deletedAt }
            .thenBy { it.deviceId }

    private val deletionOrderComparator =
        compareByDescending<SyncPlaylistSongDeletion> { it.deletedAt }
            .thenByDescending { it.deviceId }
            .thenBy(SyncPlaylistSongDeletion::stableKey)
            .thenBy { deletion -> deletion.removedMembershipTokens.orEmpty().isNotEmpty() }

    fun mergeDeletions(
        local: List<SyncPlaylistSongDeletion>,
        remote: List<SyncPlaylistSongDeletion>
    ): List<SyncPlaylistSongDeletion> {
        return (local + remote)
            .groupBy(SyncPlaylistSongDeletion::stableKey)
            .flatMap { (_, snapshots) -> mergeDeletionSnapshots(snapshots) }
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

        val relevantDeletions = mergeDeletions(deletions, emptyList())
            .asSequence()
            .filter { it.playlistId == playlistId }
            .toList()
        if (relevantDeletions.isEmpty()) {
            return songs
        }

        val causalRemovedTokens = relevantDeletions
            .asSequence()
            .flatMap { deletion -> deletion.removedMembershipTokens.orEmpty().asSequence() }
            .toHashSet()
        val deletionsByIdentity = relevantDeletions.groupBy { deletion ->
            deletion.identity().stableKey()
        }

        return songs.mapNotNull { song ->
            val identityDeletions = song.identityStableKeys()
                .flatMap { stableKey -> deletionsByIdentity[stableKey].orEmpty() }
                .distinct()
            applyDeletionsToSong(
                song = song,
                causalRemovedTokens = causalRemovedTokens,
                identityDeletions = identityDeletions
            )
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
        val activeSongsByKey = buildMap {
            playlists.asSequence()
                .filterNot(SyncPlaylist::isDeleted)
                .forEach { playlist ->
                    playlist.songs.forEach { song ->
                        song.identityStableKeys().forEach { identityKey ->
                            put("${playlist.id}|$identityKey", song)
                        }
                    }
                }
        }

        return normalizedDeletions
            .filterNot { deletion ->
                deletion.removedMembershipTokens.orEmpty().isEmpty() &&
                    activeSongsByKey[deletion.stableKey()]?.let(::effectiveAddedAt)
                        ?.let { addedAt -> addedAt > deletion.deletedAt } == true
            }
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
    ): List<SyncPlaylistSongDeletion> {
        val causalSnapshots = snapshots.filter { deletion ->
            deletion.removedMembershipTokens.orEmpty().isNotEmpty()
        }
        val causalMirror = causalSnapshots.maxWithOrNull(deletionMirrorComparator)
        val removedTokens = causalSnapshots
            .flatMap { it.removedMembershipTokens.orEmpty() }
            .normalizedSyncCausalTokens()
        val mergedCausal = causalMirror?.let { mirror ->
            if (removedTokens == mirror.removedMembershipTokens) {
                mirror
            } else {
                mirror.copy(removedMembershipTokens = removedTokens)
            }
        }
        val latestLegacy = snapshots
            .asSequence()
            .filter { deletion -> deletion.removedMembershipTokens.orEmpty().isEmpty() }
            .maxWithOrNull(deletionMirrorComparator)
            ?.copy(removedMembershipTokens = emptyList())
        return listOfNotNull(latestLegacy, mergedCausal)
    }

    private fun applyDeletionsToSong(
        song: SyncSong,
        causalRemovedTokens: Set<SyncCausalToken>,
        identityDeletions: List<SyncPlaylistSongDeletion>
    ): SyncSong? {
        val songTokens = song.syncMembershipTokens.orEmpty().normalizedSyncCausalTokens()
        if (songTokens.isEmpty()) {
            val latestIdentityDeletion = identityDeletions.maxWithOrNull(deletionMirrorComparator)
            return if (latestIdentityDeletion == null) {
                song
            } else {
                song.takeIf { effectiveAddedAt(it) > latestIdentityDeletion.deletedAt }
            }
        }

        val remainingTokens = songTokens
            .filterNot(causalRemovedTokens::contains)
            .normalizedSyncCausalTokens()
        if (remainingTokens.isEmpty()) return null
        val survivingSong = if (remainingTokens == song.syncMembershipTokens.orEmpty()) {
            song
        } else {
            song.copy(syncMembershipTokens = remainingTokens)
        }
        return survivingSong
    }

    private fun effectiveAddedAt(song: SyncSong): Long {
        return song.addedAt.takeIf { it > 0L } ?: Long.MIN_VALUE
    }

    private fun SyncSong.identityStableKeys(): Set<String> {
        return buildSet {
            add(identity().stableKey())
            add(SongIdentity(id = id, album = album, mediaUri = mediaUri).stableKey())
        }
    }
}
