package moe.ouom.neriplayer.data.sync.merge.history

import moe.ouom.neriplayer.data.sync.identity.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy

internal object SyncRecentPlayMerger {
    fun mergeRecentPlays(
        local: List<SyncRecentPlay>,
        remote: List<SyncRecentPlay>,
        deletions: List<SyncRecentPlayDeletion>
    ): List<SyncRecentPlay> {
        val deletionByIdentity = mergeRecentPlayDeletions(deletions, emptyList()).associateBy { it.identity().stableKey() }
        val lyricsByIdentity = LinkedHashMap<String, SyncSong>()
        (local.asSequence() + remote.asSequence()).forEach { play ->
            val normalized = SyncSongLyricMergePolicy.normalize(play.song)
            if (normalized.lyricSyncRevision <= 0L) return@forEach
            val key = play.song.identity().stableKey()
            val previous = lyricsByIdentity[key]
            lyricsByIdentity[key] = if (previous == null) normalized
                else SyncSongLyricMergePolicy.merge(previous, listOf(previous, normalized))
        }
        return (local + remote)
            .sortedWith(
                compareByDescending<SyncRecentPlay> { it.playedAt }
                    .thenByDescending { it.resumePositionMs }
                    .thenByDescending { it.deviceId }
            )
            .distinctBy { it.song.identity().stableKey() }
            .filter { recentPlay ->
                val deletion = deletionForSong(recentPlay.song, deletionByIdentity)
                deletion == null || recentPlay.playedAt > deletion.deletedAt
            }
            .map { play ->
                play.copy(song = SyncSongLyricMergePolicy.merge(play.song, listOfNotNull(lyricsByIdentity[play.song.identity().stableKey()])))
            }
    }

    fun mergeRecentPlayDeletions(
        local: List<SyncRecentPlayDeletion>,
        remote: List<SyncRecentPlayDeletion>
    ): List<SyncRecentPlayDeletion> {
        return (local + remote)
            .map(::normalizeDeletion)
            .groupBy { it.identity().stableKey() }
            .mapNotNull { (_, snapshots) ->
                snapshots.maxWithOrNull(
                    compareBy<SyncRecentPlayDeletion> { it.deletedAt }
                        .thenBy { it.deviceId }
                )
            }
            .sortedByDescending { it.deletedAt }
    }

    fun normalizeDeletion(deletion: SyncRecentPlayDeletion): SyncRecentPlayDeletion {
        if (deletion.album == "netease" && deletion.mediaUri == null) return deletion
        val normalized = SyncSong(id = deletion.songId, album = deletion.album, mediaUri = deletion.mediaUri).identity()
        // 缺少 audio 和分 P 字段时不能再哈希已规范化的 Bilibili 墓碑 ID
        if (normalized.album != "netease" && deletion.mediaUri.isNullOrBlank()) return deletion
        if (normalized == deletion.identity()) return deletion
        return deletion.copy(songId = normalized.id, album = normalized.album, mediaUri = normalized.mediaUri)
    }

    private fun deletionForSong(song: SyncSong, deletions: Map<String, SyncRecentPlayDeletion>): SyncRecentPlayDeletion? {
        val canonical = deletions[song.identity().stableKey()]
        val legacy = deletions[SongIdentity(song.id, song.album, song.mediaUri).stableKey()]
        return if (legacy != null && (canonical == null || legacy.deletedAt > canonical.deletedAt)) legacy else canonical
    }

    fun pruneRecentPlayDeletions(
        deletions: List<SyncRecentPlayDeletion>,
        recentPlays: List<SyncRecentPlay>
    ): List<SyncRecentPlayDeletion> {
        val latestPlayByIdentity = mutableMapOf<String, Long>()
        recentPlays.forEach { play ->
            val canonical = play.song.identity().stableKey()
            val legacy = SongIdentity(play.song.id, play.song.album, play.song.mediaUri).stableKey()
            latestPlayByIdentity[canonical] = maxOf(latestPlayByIdentity[canonical] ?: Long.MIN_VALUE, play.playedAt)
            latestPlayByIdentity[legacy] = maxOf(latestPlayByIdentity[legacy] ?: Long.MIN_VALUE, play.playedAt)
        }
        return mergeRecentPlayDeletions(deletions, emptyList())
            .filter { deletion ->
                val latestPlay = latestPlayByIdentity[deletion.identity().stableKey()]
                latestPlay == null || latestPlay <= deletion.deletedAt
            }
            .sortedByDescending { it.deletedAt }
    }
}
