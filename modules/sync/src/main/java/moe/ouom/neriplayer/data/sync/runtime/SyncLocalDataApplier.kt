package moe.ouom.neriplayer.data.sync.runtime

import moe.ouom.neriplayer.data.model.sync.SyncData

class SyncLocalDataApplier(private val host: SyncLocalApplyHost) {
    suspend fun apply(data: SyncData, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean {
        if (!host.applyPlaylists(data, expectedMutationVersion)) return false
        if (!host.applyDeletions(data, expectedMutationVersion)) return false
        if (!host.applyFavorites(data, expectedMutationVersion)) return false
        if (!host.applyHistory(data, remoteChanged, expectedMutationVersion)) return false
        host.applyStatistics(data)
        return host.applyVideoSkipRules(data, expectedMutationVersion)
    }
}
