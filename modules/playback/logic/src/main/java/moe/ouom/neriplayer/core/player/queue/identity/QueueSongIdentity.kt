package moe.ouom.neriplayer.core.player.queue.identity
import moe.ouom.neriplayer.data.model.SongItem

interface QueueSongIdentity {
    fun stableKey(song: SongItem): String

    // 本地歌曲可能通过不同引用指向同一来源，不能用 key 相等代替
    fun sameIdentity(song: SongItem, other: SongItem?): Boolean
}
