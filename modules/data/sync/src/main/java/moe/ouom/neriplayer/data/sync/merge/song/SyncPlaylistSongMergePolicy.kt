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
        return left.zip(right).all { (leftSong, rightSong) ->
            leftSong.copyWithNormalizedMembershipTokens() ==
                rightSong.copyWithNormalizedMembershipTokens()
        }
    }

    private class SongMergeAccumulator(
        private val resolvePayloadDeterministically: Boolean = false
    ) {
        private var mergeIndex = SongMergeIndex()
        private val entries = mutableListOf<SongMergeEntry>()

        fun addIfAbsent(song: SyncSong) {
            val normalizedSong = normalizeSong(song)
            val matchingIndices = mergeIndex.findMatchingIndices(normalizedSong)
            if (matchingIndices.isNotEmpty()) {
                mergeMembershipComponents(matchingIndices, normalizedSong)
                return
            }

            mergeIndex.register(normalizedSong, entries.size)
            entries += SongMergeEntry(
                song = normalizedSong,
                aliases = mutableListOf(normalizedSong)
            )
        }

        fun mergeMatchingMembershipTokens(song: SyncSong) {
            val normalizedSong = normalizeSong(song)
            val matchingIndices = mergeIndex.findMatchingIndices(normalizedSong)
            if (matchingIndices.isEmpty()) return
            mergeMembershipComponents(matchingIndices, normalizedSong)
        }

        fun toList(): List<SyncSong> = entries.map(SongMergeEntry::song)

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
            matchingIndices
                .asSequence()
                .filter { index -> index != primaryIndex }
                .forEach { index ->
                    entries[index].aliases.forEach { alias ->
                        if (alias !in primaryEntry.aliases) {
                            primaryEntry.aliases += alias
                        }
                    }
                }
            if (other !in primaryEntry.aliases) {
                primaryEntry.aliases += other
            }
            if (matchingIndices.size == 1) {
                mergeIndex.register(primaryEntry.song, primaryIndex)
                mergeIndex.register(other, primaryIndex)
                return
            }
            matchingIndices
                .asSequence()
                .filter { index -> index != primaryIndex }
                .sortedDescending()
                .forEach(entries::removeAt)
            rebuildMergeIndex()
        }

        private fun rebuildMergeIndex() {
            mergeIndex = SongMergeIndex()
            entries.forEachIndexed { index, entry ->
                mergeIndex.register(entry.song, index)
                entry.aliases.forEach { alias -> mergeIndex.register(alias, index) }
            }
        }

        private fun normalizeSong(song: SyncSong): SyncSong {
            val normalizedTokens = song.syncMembershipTokens.orEmpty().normalizedSyncCausalTokens()
            return if (normalizedTokens == song.syncMembershipTokens) {
                song
            } else {
                song.copy(syncMembershipTokens = normalizedTokens)
            }
        }

        private data class SongMergeEntry(
            var song: SyncSong,
            val aliases: MutableList<SyncSong>
        )
    }

}
