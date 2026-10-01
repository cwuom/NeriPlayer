package moe.ouom.neriplayer.core.player.session

import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueSnapshot
import moe.ouom.neriplayer.core.player.queue.state.PlayerQueueStateStore
import moe.ouom.neriplayer.core.player.persistence.RestoredPlayerStateSnapshot
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.SongItem

internal class PlayerQueueSessionBindings(private val store: PlayerQueueStateStore) {
    fun prepareForNewEngine() {
        // 新 Media3 引擎默认关闭随机模式，持久化模式随后由恢复流程应用
        store.setShuffleMode(false, clearRestore = true)
    }

    fun startPlaylist(
        songs: List<SongItem>,
        startIndex: Int,
        commandSource: PlaybackCommandSource,
        shuffleRemaining: (MutableList<Int>) -> Unit = { it.shuffle() }
    ): PlayerQueueSnapshot? {
        val requested = PlayerQueueSnapshot.from(songs, startIndex.coerceIn(songs.indices))
        val started = store.startPlayback(
            requested, commandSource != PlaybackCommandSource.REMOTE_SYNC, shuffleRemaining
        )
        return started.takeUnless { it === requested }
    }

    fun restore(snapshot: RestoredPlayerStateSnapshot): Boolean {
        if (snapshot.playlist.isEmpty()) {
            store.publish(emptyList(), -1)
            return false
        }
        store.restoreSession(
            PlayerQueueSnapshot.from(snapshot.playlist, snapshot.currentIndex),
            snapshot.shuffleEnabled,
            snapshot.shuffleRestorePlaylist?.let {
                PlayerQueueSnapshot.from(it, snapshot.shuffleRestoreIndex)
            }
        )
        return true
    }
}
