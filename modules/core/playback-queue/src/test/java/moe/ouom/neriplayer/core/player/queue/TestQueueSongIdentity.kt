package moe.ouom.neriplayer.core.player.queue
import moe.ouom.neriplayer.core.player.queue.identity.QueueSongIdentity
import moe.ouom.neriplayer.data.model.SongItem

internal object TestQueueSongIdentity : QueueSongIdentity {
    override fun stableKey(song: SongItem): String = song.id.toString()

    override fun sameIdentity(song: SongItem, other: SongItem?): Boolean = song.id == other?.id
}
