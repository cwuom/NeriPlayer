package moe.ouom.neriplayer.core.player.queue.model
import moe.ouom.neriplayer.data.model.SongItem

class PlayerQueueSnapshot private constructor(
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
