package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.identity
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
        }

        val uniqueLocalSongs = deduplicateSongs(localSongs)
        val uniqueRemoteSongs = deduplicateSongs(remoteSongs)
        val mergedSongs = mergeSongsPreservingLocal(uniqueLocalSongs, uniqueRemoteSongs)
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

    private fun mergeSongsPreservingLocal(
        localSongs: List<SyncSong>,
        remoteSongs: List<SyncSong>
    ): List<SyncSong> {
        if (remoteSongs.isEmpty()) return localSongs

        return SongMergeAccumulator()
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
        secondarySongs.forEach(accumulator::mergeExactMembershipTokens)
        return accumulator.toList()
    }

    private fun hasMembershipTokens(songs: List<SyncSong>): Boolean {
        return songs.any { it.syncMembershipTokens.orEmpty().isNotEmpty() }
    }

    private fun sameSongList(left: List<SyncSong>, right: List<SyncSong>): Boolean {
        if (left.size != right.size) return false
        return left.zip(right).all { (leftSong, rightSong) -> sameSongForMerge(leftSong, rightSong) }
    }

    private fun sameSongForMerge(left: SyncSong, right: SyncSong): Boolean {
        return sameSongForMerge(left.toMergeCandidate(), right.toMergeCandidate()) &&
            left.syncMembershipTokens.orEmpty().normalizedSyncCausalTokens() ==
            right.syncMembershipTokens.orEmpty().normalizedSyncCausalTokens()
    }

    private fun sameSongForMerge(left: SongMergeCandidate, right: SongMergeCandidate): Boolean {
        if (left.identity == right.identity) return true

        val leftChannelKey = left.channelAudioKey
        val rightChannelKey = right.channelAudioKey
        if (leftChannelKey != null && leftChannelKey == rightChannelKey) return true

        if (left.id == 0L || left.id != right.id) return false
        val leftSource = left.sourceHint
        val rightSource = right.sourceHint
        if (leftSource != null && rightSource != null && leftSource != rightSource) return false

        return left.normalizedName.isNotEmpty() &&
            left.normalizedName == right.normalizedName &&
            left.normalizedArtist == right.normalizedArtist
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
        private val identityKeys = mutableSetOf<SongIdentity>()
        private val channelAudioKeys = mutableSetOf<String>()
        private val fallbackSourcesByKey = mutableMapOf<FallbackKey, SourceBucket>()

        fun addIfAbsent(song: SyncSong): Boolean {
            val candidate = song.toMergeCandidate()
            if (contains(candidate)) return false

            add(candidate)
            return true
        }

        private fun contains(candidate: SongMergeCandidate): Boolean {
            if (candidate.identity in identityKeys) return true

            val channelAudioKey = candidate.channelAudioKey
            if (channelAudioKey != null && channelAudioKey in channelAudioKeys) return true

            val fallbackKey = candidate.fallbackKey ?: return false
            return fallbackSourcesByKey[fallbackKey]?.matches(candidate.sourceHint) == true
        }

        private fun add(candidate: SongMergeCandidate) {
            identityKeys += candidate.identity
            candidate.channelAudioKey?.let { channelAudioKeys += it }

            val fallbackKey = candidate.fallbackKey ?: return
            fallbackSourcesByKey
                .getOrPut(fallbackKey) { SourceBucket() }
                .add(candidate.sourceHint)
        }
    }

    private class SongMergeAccumulator {
        private val mergeIndex = SongMergeIndex()
        private val exactIndices = mutableMapOf<SongIdentity, Int>()
        private val songs = mutableListOf<SyncSong>()

        fun addIfAbsent(song: SyncSong) {
            val normalizedSong = normalizeMembershipTokens(song)
            val identity = normalizedSong.identity()
            val exactIndex = exactIndices[identity]
            if (exactIndex != null) {
                mergeMembershipTokensAt(exactIndex, normalizedSong)
                return
            }
            if (!mergeIndex.addIfAbsent(normalizedSong)) return

            exactIndices[identity] = songs.size
            songs += normalizedSong
        }

        fun mergeExactMembershipTokens(song: SyncSong) {
            val exactIndex = exactIndices[song.identity()] ?: return
            mergeMembershipTokensAt(exactIndex, song)
        }

        fun toList(): List<SyncSong> = songs

        private fun mergeMembershipTokensAt(index: Int, other: SyncSong) {
            val current = songs[index]
            val mergedTokens = (
                current.syncMembershipTokens.orEmpty() +
                    other.syncMembershipTokens.orEmpty()
            )
                .normalizedSyncCausalTokens()
            if (mergedTokens != current.syncMembershipTokens.orEmpty()) {
                songs[index] = current.copy(syncMembershipTokens = mergedTokens)
            }
        }

        private fun normalizeMembershipTokens(song: SyncSong): SyncSong {
            val normalizedTokens = song.syncMembershipTokens.orEmpty().normalizedSyncCausalTokens()
            return if (normalizedTokens == song.syncMembershipTokens) {
                song
            } else {
                song.copy(syncMembershipTokens = normalizedTokens)
            }
        }
    }

    private class SourceBucket {
        private var hasUnknownSource = false
        private val sources = mutableSetOf<String>()

        fun matches(source: String?): Boolean {
            return source == null || hasUnknownSource || source in sources
        }

        fun add(source: String?) {
            if (source == null) {
                hasUnknownSource = true
            } else {
                sources += source
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
