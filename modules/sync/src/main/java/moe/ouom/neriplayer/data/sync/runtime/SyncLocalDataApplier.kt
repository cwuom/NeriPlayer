package moe.ouom.neriplayer.data.sync.runtime

import moe.ouom.neriplayer.data.model.sync.SyncData

class SyncLocalDataApplier(private val host: SyncLocalApplyHost) {
    suspend fun apply(
        data: SyncData,
        remoteChanged: Boolean,
        expectedMutationVersion: Long,
        applyPlayback: suspend () -> Boolean = { true }
    ): Boolean {
        // 永久恢复记录先落盘，后续容器写入中断也不能失去防回流依据
        if (!host.applyDeletions(data, expectedMutationVersion)) return false
        if (!host.applyPlaylists(data, expectedMutationVersion)) return false
        if (!host.applyFavorites(data, expectedMutationVersion)) return false
        if (!host.applyHistory(data, remoteChanged, expectedMutationVersion)) return false
        if (!applyPlayback()) return false
        host.applyStatistics(data)
        return host.applyVideoSkipRules(data, expectedMutationVersion)
    }
}
