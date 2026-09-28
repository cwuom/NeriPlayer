package moe.ouom.neriplayer.data.sync.merge

import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.sync.model.SyncRecentPlay
import moe.ouom.neriplayer.data.sync.model.SyncRecentPlayDeletion

internal object SyncRecentPlayMerger {
    fun mergeRecentPlays(
        local: List<SyncRecentPlay>,
        remote: List<SyncRecentPlay>,
        deletions: List<SyncRecentPlayDeletion>
    ): List<SyncRecentPlay> {
        val deletionByIdentity = deletions.associateBy { it.identity().stableKey() }
        return (local + remote)
            .sortedWith(
                compareByDescending<SyncRecentPlay> { it.playedAt }
                    .thenByDescending { it.resumePositionMs }
                    .thenByDescending { it.deviceId }
            )
            .distinctBy { it.song.identity().stableKey() }
            .filter { recentPlay ->
                val deletion = deletionByIdentity[recentPlay.song.identity().stableKey()]
                deletion == null || recentPlay.playedAt > deletion.deletedAt
            }
            .take(500)
    }

    fun mergeRecentPlayDeletions(
        local: List<SyncRecentPlayDeletion>,
        remote: List<SyncRecentPlayDeletion>
    ): List<SyncRecentPlayDeletion> {
        return (local + remote)
            .groupBy { it.identity().stableKey() }
            .mapNotNull { (_, snapshots) ->
                snapshots.maxWithOrNull(
                    compareBy<SyncRecentPlayDeletion> { it.deletedAt }
                        .thenBy { it.deviceId }
                )
            }
            .sortedByDescending { it.deletedAt }
            .take(500)
    }

    fun pruneRecentPlayDeletions(
        deletions: List<SyncRecentPlayDeletion>,
        recentPlays: List<SyncRecentPlay>
    ): List<SyncRecentPlayDeletion> {
        val latestPlayByIdentity = recentPlays
            .groupBy { it.song.identity().stableKey() }
            .mapValues { (_, plays) -> plays.maxOf { it.playedAt } }
        return deletions
            .filter { deletion ->
                val latestPlay = latestPlayByIdentity[deletion.identity().stableKey()]
                latestPlay == null || latestPlay <= deletion.deletedAt
            }
            .sortedByDescending { it.deletedAt }
            .take(500)
    }
}
