package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem

/**
 * 心动模式改队列的纯计算：往哪插、插哪些、关闭时删哪些。
 *
 * 心动歌插在当前歌后面，原队列剩下的歌顺延；[inserted] 记录哪些歌是心动插进来的，
 * 关闭时按它删掉，队列回到开启前的样子。
 */
object HeartbeatQueuePlan {

    class Insertion(val playlist: List<SongItem>, val songs: List<SongItem>)

    /** 当前歌后面紧挨着的心动歌数量。 */
    fun upcomingCount(playlist: List<SongItem>, currentIndex: Int, inserted: Set<String>): Int {
        var count = 0
        for (index in currentIndex + 1 until playlist.size) {
            if (HeartbeatScorer.identityKey(playlist[index]) !in inserted) break
            count++
        }
        return count
    }

    /**
     * 新一批接在还没播的心动歌后面，不打乱它们；已经在队列里的歌不重复插。
     * 没有可插的歌或当前下标无效时返回 null。
     */
    fun insertion(
        playlist: List<SongItem>,
        currentIndex: Int,
        inserted: Set<String>,
        batch: List<SongItem>,
    ): Insertion? {
        if (currentIndex !in playlist.indices) return null
        val existing = playlist.mapTo(HashSet()) { HeartbeatScorer.identityKey(it) }
        val fresh = batch.filter { existing.add(HeartbeatScorer.identityKey(it)) }
        if (fresh.isEmpty()) return null
        val insertAt = currentIndex + 1 + upcomingCount(playlist, currentIndex, inserted)
        val songs = playlist.toMutableList().apply { addAll(insertAt, fresh) }
        return Insertion(songs, fresh)
    }

    /** 关闭心动时要删的下标；正在播的那首保留，播完接回原队列。 */
    fun removalIndices(playlist: List<SongItem>, currentIndex: Int, inserted: Set<String>): Set<Int> =
        playlist.indices.filterTo(HashSet()) { index ->
            index != currentIndex && HeartbeatScorer.identityKey(playlist[index]) in inserted
        }
}
