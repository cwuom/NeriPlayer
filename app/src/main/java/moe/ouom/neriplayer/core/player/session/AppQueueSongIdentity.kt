package moe.ouom.neriplayer.core.player.session

import moe.ouom.neriplayer.core.player.queue.identity.QueueSongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey

internal object AppQueueSongIdentity : QueueSongIdentity {
    override fun stableKey(song: SongItem): String = song.stableKey()

    override fun sameIdentity(song: SongItem, other: SongItem?): Boolean = song.sameIdentityAs(other)
}
