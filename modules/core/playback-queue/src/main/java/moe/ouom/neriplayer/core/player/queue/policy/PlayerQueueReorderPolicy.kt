package moe.ouom.neriplayer.core.player.queue.policy

import moe.ouom.neriplayer.data.model.stableKey

import moe.ouom.neriplayer.core.player.queue.identity.QueueSongIdentity
import moe.ouom.neriplayer.data.model.SongItem

internal fun reorderQueueSongsPreservingLatestMetadata(
    currentQueue: List<SongItem>,
    requestedQueue: List<SongItem>,
    identity: QueueSongIdentity
): List<SongItem>? {
    if (requestedQueue.size != currentQueue.size) return null
    val currentByKey = currentQueue.groupBy(identity::stableKey)
    val requestedByKey = requestedQueue.withIndex().groupBy { identity.stableKey(it.value) }
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
