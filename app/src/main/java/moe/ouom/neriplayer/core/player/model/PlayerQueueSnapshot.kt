package moe.ouom.neriplayer.core.player.model

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey

internal class PlayerQueueSnapshot private constructor(
    val playlist: List<SongItem>,
    val currentIndex: Int
) {
    fun selecting(index: Int): PlayerQueueSnapshot = PlayerQueueSnapshot(
        playlist = playlist,
        currentIndex = validIndex(playlist, index)
    )

    companion object {
        val EMPTY = PlayerQueueSnapshot(emptyList(), -1)

        fun from(playlist: List<SongItem>, currentIndex: Int): PlayerQueueSnapshot {
            val ownedPlaylist = playlist.toList()
            return PlayerQueueSnapshot(
                playlist = ownedPlaylist,
                currentIndex = validIndex(ownedPlaylist, currentIndex)
            )
        }

        private fun validIndex(playlist: List<SongItem>, index: Int): Int =
            index.takeIf { it in playlist.indices } ?: -1
    }
}

internal fun reorderQueueSongsPreservingLatestMetadata(
    currentQueue: List<SongItem>,
    requestedQueue: List<SongItem>
): List<SongItem>? {
    if (requestedQueue.size != currentQueue.size) return null
    val currentByKey = currentQueue.groupBy { it.stableKey() }
    val requestedByKey = requestedQueue.withIndex().groupBy { it.value.stableKey() }
    val reordered = arrayOfNulls<SongItem>(currentQueue.size)
    currentByKey.forEach { (key, currentSongs) ->
        val requestedSongs = requestedByKey[key] ?: return null
        if (currentSongs.size != requestedSongs.size) return null
        val remaining = currentSongs.toMutableList()
        val unmatched = requestedSongs.toMutableList()

        fun matchOccurrences(matches: (SongItem, SongItem) -> Boolean) {
            val requests = unmatched.iterator()
            while (requests.hasNext()) {
                val requested = requests.next()
                val match = remaining.indexOfFirst { matches(it, requested.value) }
                if (match >= 0) {
                    reordered[requested.index] = remaining.removeAt(match)
                    requests.remove()
                }
            }
        }

        // 重复歌曲优先匹配原对象，无法区分已更新的副本时保留现有队列
        matchOccurrences { current, requested -> current === requested }
        matchOccurrences { current, requested -> current == requested }
        if (remaining.any { it != remaining.first() }) return null
        unmatched.forEachIndexed { index, requested ->
            reordered[requested.index] = remaining[index]
        }
    }
    return reordered.map { checkNotNull(it) }
}
