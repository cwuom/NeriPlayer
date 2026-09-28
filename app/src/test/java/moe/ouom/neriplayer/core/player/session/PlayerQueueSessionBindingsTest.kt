package moe.ouom.neriplayer.core.player.session

import moe.ouom.neriplayer.core.player.model.PlayerQueueStateStore
import moe.ouom.neriplayer.core.player.persistence.RestoredPlayerStateSnapshot
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerQueueSessionBindingsTest {
    private val songs = (1L..3L).map { SongItem(it, "Song $it", "Artist", "Album", it, 100L, null) }

    @Test
    fun `new engine without restored state cannot inherit the released engine shuffle mode`() {
        for (emptyRestore in listOf(false, true)) {
            val store = PlayerQueueStateStore()
            val bindings = PlayerQueueSessionBindings(store)
            store.publish(songs, 1)
            store.setLocalShuffle(true, songs[1]) { it.reverse() }
            store.publish(emptyList(), -1)

            bindings.prepareForNewEngine()
            if (emptyRestore) assertFalse(bindings.restore(restored(emptyList(), true)))
            bindings.startPlaylist(songs, 1, PlaybackCommandSource.LOCAL) { error("new engine is sequential") }

            assertEquals(listOf(1L, 2L, 3L), store.snapshot().playlist.map { it.id })
            assertEquals(1, store.snapshot().currentIndex)
            assertFalse(store.sessionSnapshot().shuffleEnabled)
            assertNull(store.sessionSnapshot().shuffleRestore)
        }
    }

    @Test
    fun `restored shuffle session can start a local playlist but remote starts retain supplied order`() {
        val store = PlayerQueueStateStore()
        val bindings = PlayerQueueSessionBindings(store)
        bindings.prepareForNewEngine()
        assertTrue(bindings.restore(restored(songs.reversed(), true)))
        assertEquals(listOf(3L, 2L, 1L), store.snapshot().playlist.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), store.sessionSnapshot().shuffleRestore?.playlist?.map { it.id })
        assertTrue(store.sessionSnapshot().shuffleEnabled)

        val local = bindings.startPlaylist(songs, 10, PlaybackCommandSource.LOCAL) { it.reverse() }
        assertEquals(listOf(3L, 2L, 1L), local?.playlist?.map { it.id })
        assertEquals(2, store.sessionSnapshot().shuffleRestore?.currentIndex)
        assertNull(bindings.startPlaylist(songs, -10, PlaybackCommandSource.REMOTE_SYNC) { error("remote order") })
        assertEquals(listOf(1L, 2L, 3L), store.snapshot().playlist.map { it.id })
        assertEquals(0, store.snapshot().currentIndex)
        assertNull(store.sessionSnapshot().shuffleRestore)
    }

    @Test
    fun `restored sequential playback ignores obsolete shuffle restore data`() {
        val store = PlayerQueueStateStore()
        val bindings = PlayerQueueSessionBindings(store)
        store.setShuffleMode(true)
        assertTrue(bindings.restore(restored(songs, false)))
        assertFalse(store.sessionSnapshot().shuffleEnabled)
        assertNull(store.sessionSnapshot().shuffleRestore)
        assertTrue(bindings.restore(restored(songs, true).copy(shuffleRestorePlaylist = null)))
        assertNull(store.sessionSnapshot().shuffleRestore)
    }

    private fun restored(queue: List<SongItem>, shuffle: Boolean) = RestoredPlayerStateSnapshot(
        playlist = queue, currentIndex = 1, currentMediaUrl = null, repeatMode = 0,
        shuffleEnabled = shuffle, shuffleRestorePlaylist = songs, shuffleRestoreIndex = 1,
        resumePositionMs = 0L, shouldResumePlayback = false,
        originalPlaylistSize = queue.size, persistedIndex = 1
    )
}
