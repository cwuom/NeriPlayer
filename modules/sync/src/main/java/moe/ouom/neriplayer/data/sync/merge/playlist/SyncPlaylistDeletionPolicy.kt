package moe.ouom.neriplayer.data.sync.merge.playlist

import moe.ouom.neriplayer.data.sync.identity.identity
import moe.ouom.neriplayer.data.sync.identity.stableKey

import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.normalizedSyncCausalTokens

object SyncPlaylistDeletionPolicy {
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
        val merged = LinkedHashMap<String, DeletionAccumulator>()
        (local.asSequence() + remote.asSequence()).forEach { deletion ->
            merged.getOrPut(deletion.stableKey(), ::DeletionAccumulator).add(deletion)
        }
        return merged.values.flatMap(DeletionAccumulator::snapshots)
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

        val relevantDeletions = mergeDeletions(
            deletions.filter { it.playlistId == playlistId }, emptyList()
        )
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
        val legacyKeys = normalizedDeletions.asSequence()
            .filter { it.removedMembershipTokens.orEmpty().isEmpty() }
            .map(SyncPlaylistSongDeletion::stableKey)
            .toHashSet()
        if (legacyKeys.isEmpty()) return normalizedDeletions
        val activeSongsByKey = buildMap {
            playlists.asSequence()
                .filterNot(SyncPlaylist::isDeleted)
                .forEach { playlist ->
                    playlist.songs.forEach { song ->
                        song.identityStableKeys().forEach { identityKey ->
                            val key = "${playlist.id}|$identityKey"
                            if (key in legacyKeys) put(key, song)
                        }
                    }
                }
        }

        return normalizedDeletions
            .filterNot { deletion ->
                isResolvedLegacyDeletion(deletion, activeSongsByKey[deletion.stableKey()])
            }
    }

    private fun isResolvedLegacyDeletion(deletion: SyncPlaylistSongDeletion, activeSong: SyncSong?): Boolean {
        if (deletion.removedMembershipTokens.orEmpty().isNotEmpty()) return false
        if (activeSong == null) return false
        // 迁移合成的 addedAt 不能证明重新添加，只有 membership token 能接管旧墓碑
        if (activeSong.syncMembershipTokens.orEmpty().isEmpty()) return false
        return effectiveAddedAt(activeSong) > deletion.deletedAt
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
            legacyDeletionWasReadded(deletion, readdedKeys)
        }
    }

    private fun legacyDeletionWasReadded(deletion: SyncPlaylistSongDeletion, readdedKeys: Set<String>): Boolean =
        deletion.stableKey() in readdedKeys && deletion.removedMembershipTokens.orEmpty().isEmpty()

    fun shouldKeepPlaylistDeleted(left: SyncPlaylist, right: SyncPlaylist): Boolean {
        if (!left.isDeleted && !right.isDeleted) return false
        if (left.isDeleted && right.isDeleted) return true

        val deleted = if (left.isDeleted) left else right
        val active = if (left.isDeleted) right else left
        return deleted.modifiedAt >= active.modifiedAt
    }

    fun mergeFavoritePlaylists(
        left: SyncFavoritePlaylist,
        right: SyncFavoritePlaylist
    ): SyncFavoritePlaylist {
        return SyncFavoritePlaylistMergePolicy.merge(left, right)
    }

    private class DeletionAccumulator {
        private var legacy: SyncPlaylistSongDeletion? = null
        private var causal: SyncPlaylistSongDeletion? = null
        private val removedTokens = HashSet<SyncCausalToken>()

        fun add(deletion: SyncPlaylistSongDeletion) {
            val tokens = deletion.removedMembershipTokens.orEmpty()
            if (tokens.isEmpty()) {
                legacy = latest(legacy, deletion)
            } else {
                causal = latest(causal, deletion)
                removedTokens.addAll(tokens)
            }
        }

        fun snapshots(): List<SyncPlaylistSongDeletion> = listOfNotNull(
            legacy?.copy(removedMembershipTokens = emptyList()),
            causal?.copy(removedMembershipTokens = removedTokens.toList().normalizedSyncCausalTokens())
        )

        private fun latest(
            current: SyncPlaylistSongDeletion?, incoming: SyncPlaylistSongDeletion
        ): SyncPlaylistSongDeletion = if (
            current == null || deletionMirrorComparator.compare(incoming, current) > 0
        ) incoming else current
    }

    private fun applyDeletionsToSong(
        song: SyncSong,
        causalRemovedTokens: Set<SyncCausalToken>,
        identityDeletions: List<SyncPlaylistSongDeletion>
    ): SyncSong? {
        val songTokens = song.syncMembershipTokens.orEmpty().normalizedSyncCausalTokens()
        if (songTokens.isEmpty()) {
            return applyLegacyIdentityDeletion(song, identityDeletions)
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

    private fun applyLegacyIdentityDeletion(song: SyncSong, identityDeletions: List<SyncPlaylistSongDeletion>): SyncSong? {
        val latestIdentityDeletion = identityDeletions.maxWithOrNull(deletionMirrorComparator)
        return if (latestIdentityDeletion == null) {
            song
        } else {
            song.takeIf { effectiveAddedAt(it) > latestIdentityDeletion.deletedAt }
        }
    }

    private fun effectiveAddedAt(song: SyncSong): Long {
        return (song.legacyAddedAt ?: song.addedAt)
            .takeIf { it > 0L }
            ?: Long.MIN_VALUE
    }

    private fun SyncSong.identityStableKeys(): Set<String> {
        return buildSet {
            add(identity().stableKey())
            add(SongIdentity(id = id, album = album, mediaUri = mediaUri).stableKey())
        }
    }
}
