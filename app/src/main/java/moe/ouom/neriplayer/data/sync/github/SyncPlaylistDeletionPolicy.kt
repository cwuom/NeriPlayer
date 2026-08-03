package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.model.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.sync.model.SyncPlaylist
import moe.ouom.neriplayer.data.sync.model.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.model.SyncSong
import moe.ouom.neriplayer.data.sync.model.normalizedSyncCausalTokens

internal object SyncPlaylistDeletionPolicy {
    internal const val MAX_PLAYLIST_SONG_DELETIONS = 5_000

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

    /** 在有限容量内保留两类墓碑，避免 causal 墓碑无限增长或挤掉全部 legacy 墓碑 */
    fun limitDeletions(
        deletions: List<SyncPlaylistSongDeletion>,
        maxCount: Int = MAX_PLAYLIST_SONG_DELETIONS
    ): List<SyncPlaylistSongDeletion> {
        require(maxCount >= 0) { "Deletion capacity must not be negative" }
        if (deletions.isEmpty() || maxCount == 0) {
            return emptyList()
        }

        val merged = mergeDeletions(deletions, emptyList())
        if (merged.size <= maxCount) {
            return merged
        }

        val causal = merged.filter { it.removedMembershipTokens.orEmpty().isNotEmpty() }
        val legacy = merged.filter { it.removedMembershipTokens.orEmpty().isEmpty() }
        val preferredLegacyCapacity = when {
            causal.isEmpty() -> maxCount
            legacy.isEmpty() -> 0
            else -> maxCount / 2
        }
        var legacyCapacity = minOf(legacy.size, preferredLegacyCapacity)
        var causalCapacity = minOf(causal.size, maxCount - legacyCapacity)

        var remainingCapacity = maxCount - legacyCapacity - causalCapacity
        if (remainingCapacity > 0) {
            val extraLegacy = minOf(legacy.size - legacyCapacity, remainingCapacity)
            legacyCapacity += extraLegacy
            remainingCapacity -= extraLegacy
        }
        if (remainingCapacity > 0) {
            causalCapacity += minOf(causal.size - causalCapacity, remainingCapacity)
        }

        return (causal.take(causalCapacity) + legacy.take(legacyCapacity))
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

        return limitDeletions(
            normalizedDeletions
            .filterNot { deletion ->
                deletion.removedMembershipTokens.orEmpty().isEmpty() &&
                    activeSongsByKey[deletion.stableKey()]?.let { activeSong ->
                        // 仅当活跃歌曲带 membership token (在新版本被真正重新添加, 该 identity
                        // 已由 causal token 接管) 时才裁 legacy 墓碑; 无 token 歌曲的 addedAt
                        // 可能来自 legacy 迁移合成, 不可据此判定重新添加, 否则误裁墓碑使已删歌复活 (P1-1)
                        activeSong.syncMembershipTokens.orEmpty().isNotEmpty() &&
                            effectiveAddedAt(activeSong) > deletion.deletedAt
                    } == true
            }
        )
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
        val newer = if (right.modifiedAt > left.modifiedAt) right else left
        val older = if (newer === left) right else left

        if (left.isDeleted != right.isDeleted) {
            if (left.modifiedAt == right.modifiedAt) {
                val deleted = if (left.isDeleted) left else right
                return deleted.copy(
                    songs = emptyList(),
                    trackCount = 0,
                    addedTime = maxOf(left.addedTime, right.addedTime),
                    modifiedAt = maxOf(left.modifiedAt, right.modifiedAt),
                    sortOrder = maxOf(left.sortOrder, right.sortOrder)
                )
            }
            return if (newer.isDeleted) {
                newer.copy(
                    songs = emptyList(),
                    trackCount = 0,
                    sortOrder = maxOf(left.sortOrder, right.sortOrder)
                )
            } else {
                newer.copy(
                    songs = (left.songs + right.songs).distinctBy { it.identity() },
                    trackCount = maxOf(left.trackCount, right.trackCount, left.songs.size, right.songs.size),
                    sortOrder = newer.sortOrder.takeIf { it > 0L } ?: older.sortOrder
                )
            }
        }

        if (newer.isDeleted) {
            return newer.copy(
                songs = emptyList(),
                trackCount = 0,
                addedTime = maxOf(left.addedTime, right.addedTime),
                sortOrder = maxOf(left.sortOrder, right.sortOrder)
            )
        }

        val mergedSongs = (left.songs + right.songs).distinctBy { it.identity() }
        return newer.copy(
            coverUrl = newer.coverUrl ?: older.coverUrl,
            songs = mergedSongs,
            trackCount = maxOf(left.trackCount, right.trackCount, mergedSongs.size),
            addedTime = maxOf(left.addedTime, right.addedTime),
            modifiedAt = maxOf(left.modifiedAt, right.modifiedAt),
            sortOrder = newer.sortOrder.takeIf { it > 0L } ?: older.sortOrder,
            isDeleted = false
        )
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
