package moe.ouom.neriplayer.core.player.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sameIdentityAs

internal class PlayerQueueStateStore {
    private val lock = Any()
    @Volatile
    private var current = PlayerQueueSnapshot.EMPTY
    private val _playlistFlow = MutableStateFlow<List<SongItem>>(emptyList())
    val playlistFlow: StateFlow<List<SongItem>> = _playlistFlow

    fun snapshot(): PlayerQueueSnapshot = current

    fun select(index: Int) {
        synchronized(lock) {
            current = current.selecting(index)
        }
    }

    fun publish(playlist: List<SongItem>, currentIndex: Int): PlayerQueueSnapshot =
        synchronized(lock) {
            publishLocked(PlayerQueueSnapshot.from(playlist, currentIndex))
        }

    fun update(
        transform: (PlayerQueueSnapshot) -> PlayerQueueSnapshot?
    ): PlayerQueueSnapshot? = synchronized(lock) {
        // 变换只计算队列，播放器和磁盘操作留在锁外，避免阻塞其它队列写入
        val updated = transform(current) ?: return@synchronized null
        publishLocked(updated)
    }

    fun updatePlaylist(
        transform: (List<SongItem>) -> List<SongItem>?
    ): PlayerQueueSnapshot? = update { snapshot ->
        val updated = transform(snapshot.playlist) ?: return@update null
        PlayerQueueSnapshot.from(updated, snapshot.currentIndex)
    }

    fun updateSongMatching(
        song: SongItem,
        transform: (SongItem) -> SongItem?
    ): SongItem? {
        var updatedSong: SongItem? = null
        updatePlaylist { playlist ->
            val index = playlist.indexOfFirst { it.sameIdentityAs(song) }
            if (index < 0) return@updatePlaylist null
            val updated = transform(playlist[index]) ?: return@updatePlaylist null
            updatedSong = updated
            playlist.toMutableList().also { it[index] = updated }
        }
        return updatedSong
    }

    private fun publishLocked(next: PlayerQueueSnapshot): PlayerQueueSnapshot {
        current = next
        _playlistFlow.value = next.playlist
        return next
    }
}
