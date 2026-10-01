package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.sync.SyncSong

internal enum class SyncSongMergeStrategy {
    REMOTE_ONLY, LOCAL_ONLY, LOCAL_CLEAR, REMOTE_CLEAR,
    REMOTE_PRIMARY, LOCAL_PRIMARY, LOCAL_CONCURRENT, REMOTE_CONCURRENT, DETERMINISTIC
}

internal object SyncSongMergeSelection {
    fun resolve(
        local: List<SyncSong>, remote: List<SyncSong>, localModified: Long, remoteModified: Long,
        localChanged: Boolean, remoteChanged: Boolean, lastSync: Long, favorites: Boolean
    ): SyncSongMergeStrategy {
        if (local.isEmpty() && remote.isNotEmpty()) {
            return localEmpty(remote, localModified, remoteModified, localChanged, lastSync, favorites)
        }
        if (remote.isEmpty() && local.isNotEmpty()) {
            return remoteEmpty(local, localModified, remoteModified, remoteChanged)
        }
        return changedSide(localModified, remoteModified, localChanged, remoteChanged)
    }

    private fun localEmpty(
        remote: List<SyncSong>, localModified: Long, remoteModified: Long,
        localChanged: Boolean, lastSync: Long, favorites: Boolean
    ): SyncSongMergeStrategy {
        if (favorites && lastSync <= 0L) return SyncSongMergeStrategy.REMOTE_ONLY
        if (hasMembershipTokens(remote)) return SyncSongMergeStrategy.REMOTE_ONLY
        return if (localChanged && localModified >= remoteModified) SyncSongMergeStrategy.LOCAL_CLEAR
            else SyncSongMergeStrategy.REMOTE_ONLY
    }

    private fun remoteEmpty(
        local: List<SyncSong>, localModified: Long, remoteModified: Long, remoteChanged: Boolean
    ): SyncSongMergeStrategy {
        if (hasMembershipTokens(local)) return SyncSongMergeStrategy.LOCAL_ONLY
        return if (remoteChanged && remoteModified > localModified) SyncSongMergeStrategy.REMOTE_CLEAR
            else SyncSongMergeStrategy.LOCAL_ONLY
    }

    private fun changedSide(localModified: Long, remoteModified: Long, localChanged: Boolean, remoteChanged: Boolean): SyncSongMergeStrategy {
        if (localChanged != remoteChanged) {
            return if (remoteChanged) SyncSongMergeStrategy.REMOTE_PRIMARY else SyncSongMergeStrategy.LOCAL_PRIMARY
        }
        if (!localChanged) return SyncSongMergeStrategy.DETERMINISTIC
        if (localModified > remoteModified) return SyncSongMergeStrategy.LOCAL_CONCURRENT
        if (remoteModified > localModified) return SyncSongMergeStrategy.REMOTE_CONCURRENT
        return SyncSongMergeStrategy.DETERMINISTIC
    }

    private fun hasMembershipTokens(songs: List<SyncSong>): Boolean =
        songs.any { it.syncMembershipTokens.orEmpty().isNotEmpty() }
}
