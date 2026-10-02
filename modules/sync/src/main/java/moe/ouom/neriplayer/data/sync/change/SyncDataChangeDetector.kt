package moe.ouom.neriplayer.data.sync.change

import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.sync.policy.SyncBiliVideoSkipMergePolicy

object SyncDataChangeDetector {
    fun hasDataChanged(remote: SyncData, merged: SyncData): Boolean =
        SyncPlaylistChangeDetector.playlistsChanged(remote, merged) ||
            remote.lyricOverrides != merged.lyricOverrides ||
            SyncPlaylistChangeDetector.favoritesChanged(remote, merged) ||
            SyncHistoryChangeDetector.changed(remote, merged) ||
            !SyncBiliVideoSkipMergePolicy.same(remote.biliVideoSkipRules, merged.biliVideoSkipRules) ||
            SyncStatisticsChangeDetector.playbackChanged(remote, merged) ||
            SyncStatisticsChangeDetector.playlistUsageChanged(remote, merged)
}
