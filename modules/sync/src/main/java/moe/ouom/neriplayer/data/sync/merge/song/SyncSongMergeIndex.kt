package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken

internal class SongMergeIndex {
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

            addAll(fallbackMatches(candidate))
        }
    }

    private fun fallbackMatches(candidate: SongMergeCandidate): Set<Int> {
        val key = candidate.fallbackKey ?: return emptySet()
        val sources = fallbackSourcesByKey[key] ?: return emptySet()
        return sources.findAll(candidate.sourceHint)
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
