package moe.ouom.neriplayer.core.player.persistence

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.core.player.model.PersistedPlaybackState
import moe.ouom.neriplayer.core.player.model.PersistedState
import moe.ouom.neriplayer.core.player.queue.state.PlayerQueueStateStore
import moe.ouom.neriplayer.core.player.session.AppQueueSongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueSessionPersistenceTest {
    @Test
    fun `persistence during shuffle sees one complete session`() {
        val songs = (1L..3L).map { SongItem(it, "Song $it", "Artist", "Album", it, 100L, null) }
        val store = PlayerQueueStateStore(AppQueueSongIdentity).also { it.publish(songs, 1) }
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val shuffle = executor.submit {
                store.setLocalShuffle(true, songs[1]) {
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                    it.reverse()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val before = persisted(store)
            assertEquals(listOf(1L, 2L, 3L), before.playlist.map { it.id })
            assertEquals(1, before.index)
            assertEquals(false, before.shuffleEnabled)
            assertNull(before.shuffleRestorePlaylist)
            resume.countDown()
            shuffle.get(5, TimeUnit.SECONDS)

            val after = persisted(store)
            assertEquals(listOf(2L, 3L, 1L), after.playlist.map { it.id })
            assertEquals(0, after.index)
            assertEquals(true, after.shuffleEnabled)
            assertEquals(listOf(1L, 2L, 3L), after.shuffleRestorePlaylist?.map { it.id })
            assertEquals(1, after.shuffleRestoreIndex)
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    private fun persisted(store: PlayerQueueStateStore): PersistedState {
        val session = store.sessionSnapshot()
        return PlaybackStatePersistenceSnapshot(
            session, PersistedPlaybackState(session.queue.currentIndex), keepShuffleMode = true
        ).toPersistedState()
    }
}
