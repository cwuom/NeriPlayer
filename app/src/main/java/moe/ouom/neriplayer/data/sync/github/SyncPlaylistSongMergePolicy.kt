package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.sync.model.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.model.SyncSong
import moe.ouom.neriplayer.data.sync.model.copyWithNormalizedMembershipTokens
import moe.ouom.neriplayer.data.sync.model.normalizedSyncCausalTokens

internal object SyncPlaylistSongMergePolicy {
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
        val localIsEmpty = localSongs.isEmpty()
        val remoteIsEmpty = remoteSongs.isEmpty()
        val localHasMembershipTokens = hasMembershipTokens(localSongs)
        val remoteHasMembershipTokens = hasMembershipTokens(remoteSongs)
        val preferRemoteFavorites = isFavorites && localIsEmpty && !remoteIsEmpty && lastSyncTime <= 0L

        when {
            preferRemoteFavorites -> return Result(deduplicateSongs(remoteSongs), true)

            localIsEmpty && !remoteIsEmpty -> {
                if (remoteHasMembershipTokens) {
                    return Result(deduplicateSongs(remoteSongs), true)
                }
                val localClearWins = localChangedAfterSync && localModifiedAt >= remoteModifiedAt
                return if (localClearWins) {
                    Result(emptyList(), false)
                } else {
                    Result(deduplicateSongs(remoteSongs), true)
                }
            }

            remoteIsEmpty && !localIsEmpty -> {
                if (localHasMembershipTokens) {
                    return Result(deduplicateSongs(localSongs), false)
                }
                val remoteClearWins = remoteChangedAfterSync && remoteModifiedAt > localModifiedAt
                return if (remoteClearWins) {
                    Result(emptyList(), true)
                } else {
                    Result(deduplicateSongs(localSongs), false)
                }
            }

            remoteChangedAfterSync && !localChangedAfterSync -> {
                return Result(
                    songs = mergeMembershipTokensIntoPrimary(remoteSongs, localSongs),
                    isUpdated = true
                )
            }
            localChangedAfterSync && !remoteChangedAfterSync -> {
                return Result(
                    songs = mergeMembershipTokensIntoPrimary(localSongs, remoteSongs),
                    isUpdated = false
                )
            }
            localChangedAfterSync && localModifiedAt > remoteModifiedAt -> {
                return Result(
                    songs = mergeConcurrentChanges(localSongs, remoteSongs),
                    isUpdated = true
                )
            }
            localChangedAfterSync && remoteModifiedAt > localModifiedAt -> {
                return Result(
                    songs = mergeConcurrentChanges(remoteSongs, localSongs),
                    isUpdated = true
                )
            }
        }

        val uniqueLocalSongs = deduplicateSongs(localSongs)
        val uniqueRemoteSongs = deduplicateSongs(remoteSongs)
        val mergedSongs = mergeSongsWithDeterministicPayload(uniqueLocalSongs, uniqueRemoteSongs)
        return Result(
            songs = mergedSongs,
            isUpdated = !sameSongList(mergedSongs, uniqueLocalSongs) ||
                !sameSongList(mergedSongs, uniqueRemoteSongs)
        )
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

    private fun hasMembershipTokens(songs: List<SyncSong>): Boolean {
        return songs.any { it.syncMembershipTokens.orEmpty().isNotEmpty() }
    }

    private fun sameSongList(left: List<SyncSong>, right: List<SyncSong>): Boolean {
        if (left.size != right.size) return false
        return left.zip(right).all { (leftSong, rightSong) ->
            leftSong.copyWithNormalizedMembershipTokens() ==
                rightSong.copyWithNormalizedMembershipTokens()
        }
    }

    private fun SyncSong.toMergeCandidate(): SongMergeCandidate {
        return SongMergeCandidate(
            id = id,
            identity = identity(),
            channelAudioKey = channelAudioKey(this),
            sourceHint = sourceHint(this),
            normalizedName = name.normalizedText(),
            normalizedArtist = artist.normalizedText()
        )
    }

    private data class SongMergeCandidate(
        val id: Long,
        val identity: SongIdentity,
        val channelAudioKey: String?,
        val sourceHint: String?,
        val normalizedName: String,
        val normalizedArtist: String
    ) {
        val fallbackKey: FallbackKey? =
            if (id != 0L && normalizedName.isNotEmpty()) {
                FallbackKey(id, normalizedName, normalizedArtist)
            } else {
                null
            }
    }

    private data class FallbackKey(
        val id: Long,
        val normalizedName: String,
        val normalizedArtist: String
    )

    private class SongMergeIndex {
        private val membershipTokenIndices = mutableMapOf<SyncCausalToken, Int>()
        private val identityIndices = mutableMapOf<SongIdentity, Int>()
        private val channelAudioIndices = mutableMapOf<String, Int>()
        private val fallbackSourcesByKey = mutableMapOf<FallbackKey, SourceBucket>()

        fun findMatchingIndices(song: SyncSong): Set<Int> {
            val candidate = song.toMergeCandidate()
            return buildSet {
                song.syncMembershipTokens.orEmpty().forEach { token ->
                    membershipTokenIndices[token]?.let(::add)
                }
                identityIndices[candidate.identity]?.let(::add)

                val channelAudioKey = candidate.channelAudioKey
                if (channelAudioKey != null) {
                    channelAudioIndices[channelAudioKey]?.let(::add)
                }

                candidate.fallbackKey?.let { fallbackKey ->
                    fallbackSourcesByKey[fallbackKey]
                        ?.findAll(candidate.sourceHint)
                        ?.let(::addAll)
                }
            }
        }

        fun register(song: SyncSong, index: Int) {
            val candidate = song.toMergeCandidate()
            song.syncMembershipTokens.orEmpty().forEach { token ->
                membershipTokenIndices.putIfAbsent(token, index)
            }
            identityIndices.putIfAbsent(candidate.identity, index)
            candidate.channelAudioKey?.let { channelAudioKey ->
                channelAudioIndices.putIfAbsent(channelAudioKey, index)
            }

            val fallbackKey = candidate.fallbackKey ?: return
            fallbackSourcesByKey
                .getOrPut(fallbackKey) { SourceBucket() }
                .add(candidate.sourceHint, index)
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

    private class SourceBucket {
        private var unknownSourceIndex: Int? = null
        private val sourceIndices = mutableMapOf<String, Int>()

        fun findAll(source: String?): Set<Int> {
            return buildSet {
                if (source == null) {
                    unknownSourceIndex?.let(::add)
                    addAll(sourceIndices.values)
                } else {
                    unknownSourceIndex?.let(::add)
                    sourceIndices[source]?.let(::add)
                }
            }
        }

        fun add(source: String?, index: Int) {
            if (source == null) {
                if (unknownSourceIndex == null) {
                    unknownSourceIndex = index
                }
            } else {
                sourceIndices.putIfAbsent(source, index)
            }
        }
    }

    private fun channelAudioKey(song: SyncSong): String? {
        val channel = song.channelId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val audio = song.audioId?.trim()?.takeIf { it.isNotEmpty() }
        if (channel == null || audio == null) return null
        val subAudio = song.subAudioId?.trim().orEmpty()
        return "$channel|$audio|$subAudio"
    }

    private fun sourceHint(song: SyncSong): String? {
        val channel = song.channelId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (channel != null) return channel

        val album = song.album.trim().lowercase()
        return when {
            album.startsWith("netease") -> "netease"
            album.startsWith("bilibili") -> "bilibili"
            song.mediaUri?.contains("youtube", ignoreCase = true) == true -> "youtube"
            else -> null
        }
    }

    private fun String.normalizedText(): String {
        return trim().lowercase()
    }
}
