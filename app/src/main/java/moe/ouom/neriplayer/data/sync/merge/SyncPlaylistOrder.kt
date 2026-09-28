package moe.ouom.neriplayer.data.sync.merge

import moe.ouom.neriplayer.data.sync.model.SyncPlaylist

internal object SyncPlaylistOrder {
    fun orderMergedPlaylists(
        local: List<SyncPlaylist>,
        remote: List<SyncPlaylist>,
        mergedById: Map<Long, SyncPlaylist>,
        lastSyncTime: Long
    ): List<SyncPlaylist> {
        if (mergedById.isEmpty()) return emptyList()

        val localChangedAfterSync = hasPlaylistCollectionChangedAfterSync(local, lastSyncTime)
        val remoteChangedAfterSync = hasPlaylistCollectionChangedAfterSync(remote, lastSyncTime)
        val primary = if (remoteChangedAfterSync && !localChangedAfterSync) remote else local
        val secondary = if (primary === local) remote else local
        val orderedIds = LinkedHashSet<Long>()

        fun appendPlaylistIds(source: List<SyncPlaylist>) {
            source.asSequence()
                .filterNot(SyncPlaylist::isDeleted)
                .map(SyncPlaylist::id)
                .filter(mergedById::containsKey)
                .forEach(orderedIds::add)
        }

        appendPlaylistIds(primary)
        appendPlaylistIds(secondary)
        mergedById.keys.forEach(orderedIds::add)

        return orderedIds.mapNotNull(mergedById::get)
    }

    private fun hasPlaylistCollectionChangedAfterSync(
        playlists: List<SyncPlaylist>,
        lastSyncTime: Long
    ): Boolean {
        return playlists.any { !it.isDeleted && it.modifiedAt > lastSyncTime }
    }
}
