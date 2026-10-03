package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.sync.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.policy.copyWithNormalizedMembershipTokens
import moe.ouom.neriplayer.data.model.sync.normalizedSyncCausalTokens

object SyncPlaylistSongMergePolicy {
    data class Result(
        val songs: List<SyncSong>,
        val isUpdated: Boolean
    )

    fun mergeSongs(
        localSongs: List<SyncSong>,
        remoteSongs: List<SyncSong>,
        localModifiedAt: Long,
        remoteModifiedAt: Long,
        localChangedAfterSync: Boolean,
        remoteChangedAfterSync: Boolean,
        lastSyncTime: Long,
        isFavorites: Boolean
    ): Result {
        val strategy = SyncSongMergeSelection.resolve(
            localSongs, remoteSongs, localModifiedAt, remoteModifiedAt,
            localChangedAfterSync, remoteChangedAfterSync, lastSyncTime, isFavorites
        )
        return when (strategy) {
            SyncSongMergeStrategy.REMOTE_ONLY -> Result(deduplicateSongs(remoteSongs), true)
            SyncSongMergeStrategy.LOCAL_ONLY -> Result(deduplicateSongs(localSongs), false)
            SyncSongMergeStrategy.LOCAL_CLEAR -> Result(emptyList(), false)
            SyncSongMergeStrategy.REMOTE_CLEAR -> Result(emptyList(), true)
            SyncSongMergeStrategy.REMOTE_PRIMARY -> Result(mergeMembershipTokensIntoPrimary(remoteSongs, localSongs), true)
            SyncSongMergeStrategy.LOCAL_PRIMARY -> Result(mergeMembershipTokensIntoPrimary(localSongs, remoteSongs), false)
            SyncSongMergeStrategy.LOCAL_CONCURRENT -> Result(mergeConcurrentChanges(localSongs, remoteSongs), true)
            SyncSongMergeStrategy.REMOTE_CONCURRENT -> Result(mergeConcurrentChanges(remoteSongs, localSongs), true)
            SyncSongMergeStrategy.DETERMINISTIC -> mergeDeterministically(localSongs, remoteSongs)
        }
    }

    private fun mergeDeterministically(local: List<SyncSong>, remote: List<SyncSong>): Result {
        val uniqueLocal = deduplicateSongs(local)
        val uniqueRemote = deduplicateSongs(remote)
        val merged = mergeSongsWithDeterministicPayload(uniqueLocal, uniqueRemote)
        return Result(merged, !sameSongList(merged, uniqueLocal) || !sameSongList(merged, uniqueRemote))
    }

    fun deduplicateSongs(songs: List<SyncSong>): List<SyncSong> {
        if (songs.isEmpty()) return songs

        return SongMergeAccumulator()
            .apply { songs.forEach(::addIfAbsent) }
            .toList()
    }

    private fun mergeSongsWithDeterministicPayload(
        localSongs: List<SyncSong>,
        remoteSongs: List<SyncSong>
    ): List<SyncSong> {
        if (remoteSongs.isEmpty()) return localSongs

        return SongMergeAccumulator(
            resolvePayloadDeterministically = true
        )
            .apply {
                localSongs.forEach(::addIfAbsent)
                remoteSongs.forEach(::addIfAbsent)
            }
            .toList()
    }

    private fun mergeMembershipTokensIntoPrimary(
        primarySongs: List<SyncSong>,
        secondarySongs: List<SyncSong>
    ): List<SyncSong> {
        val accumulator = SongMergeAccumulator()
        primarySongs.forEach(accumulator::addIfAbsent)
        secondarySongs.forEach(accumulator::mergeMatchingMembershipTokens)
        return accumulator.toList()
    }

    private fun mergeConcurrentChanges(
        primarySongs: List<SyncSong>,
        secondarySongs: List<SyncSong>
    ): List<SyncSong> {
        return SongMergeAccumulator()
            .apply {
                primarySongs.forEach(::addIfAbsent)
                secondarySongs.forEach(::addIfAbsent)
            }
            .toList()
    }

    private fun sameSongList(left: List<SyncSong>, right: List<SyncSong>): Boolean {
        if (left.size != right.size) return false
        return left.indices.all { index ->
            left[index].copyWithNormalizedMembershipTokens() ==
                right[index].copyWithNormalizedMembershipTokens()
        }
    }

    private class SongMergeAccumulator(
        private val resolvePayloadDeterministically: Boolean = false
    ) {
        private val mergeIndex = SongMergeIndex()
        private val entries = mutableListOf<SongMergeEntry>()

        fun addIfAbsent(song: SyncSong) {
            val normalizedSong = normalizeSong(song)
            val matchingIndices = matchingRoots(normalizedSong)
            if (matchingIndices.isNotEmpty()) {
                mergeMembershipComponents(matchingIndices, normalizedSong)
                return
            }

            mergeIndex.register(normalizedSong, entries.size)
            entries += SongMergeEntry(
                song = normalizedSong,
                parent = entries.size
            )
        }

        fun mergeMatchingMembershipTokens(song: SyncSong) {
            val normalizedSong = normalizeSong(song)
            val matchingIndices = matchingRoots(normalizedSong)
            if (matchingIndices.isEmpty()) return
            mergeMembershipComponents(matchingIndices, normalizedSong)
        }

        fun toList(): List<SyncSong> = entries.mapIndexedNotNull { index, entry ->
            entry.song.takeIf { entry.parent == index }?.let(SyncSongLyricMergePolicy::normalize)
        }

        private fun matchingRoots(song: SyncSong): Set<Int> =
            mergeIndex.findMatchingIndices(song).mapTo(mutableSetOf(), ::root)

        private fun root(index: Int): Int {
            var current = index
            while (entries[current].parent != current) {
                val parent = entries[current].parent
                entries[current].parent = entries[parent].parent
                current = parent
            }
            return current
        }

        private fun mergeMembershipComponents(
            matchingIndices: Set<Int>,
            other: SyncSong
        ) {
            val primaryIndex = matchingIndices.min()
            val primaryEntry = entries[primaryIndex]
            val mergedTokens = matchingIndices
                .asSequence()
                .flatMap { index -> entries[index].song.syncMembershipTokens.orEmpty().asSequence() }
                .plus(other.syncMembershipTokens.orEmpty().asSequence())
                .toList()
                .normalizedSyncCausalTokens()
            val payloadCandidates = matchingIndices
                .asSequence()
                .map { index -> entries[index].song }
                .plus(other)
                .toList()
            val selectedPayload = if (resolvePayloadDeterministically) {
                SyncSongMetadataMergePolicy.selectDeterministicPayload(payloadCandidates)
            } else {
                primaryEntry.song
            }
            val resolvedPayload = SyncSongMetadataMergePolicy.resolveSelectedPayload(
                selected = selectedPayload,
                candidates = payloadCandidates
            )
            primaryEntry.song = resolvedPayload.copy(
                addedAt = SyncSongMetadataMergePolicy.resolveAddedAt(
                    selectedAddedAt = selectedPayload.addedAt,
                    candidates = payloadCandidates
                ),
                syncMembershipTokens = mergedTokens,
                syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION
            )
            // 保留稳定槽位，旧身份索引经根节点找到合并结果，避免每次桥接重建整个歌单
            matchingIndices.forEach { index ->
                entries[index].parent = primaryIndex
            }
            mergeIndex.register(primaryEntry.song, primaryIndex)
            mergeIndex.register(other, primaryIndex)
        }

        private fun normalizeSong(song: SyncSong): SyncSong {
            val normalizedTokens = song.syncMembershipTokens.orEmpty().normalizedSyncCausalTokens()
            val normalized = if (normalizedTokens == song.syncMembershipTokens) {
                song
            } else {
                song.copy(syncMembershipTokens = normalizedTokens)
            }
            return normalized
        }

        private data class SongMergeEntry(
            var song: SyncSong,
            var parent: Int
        )
    }

}
