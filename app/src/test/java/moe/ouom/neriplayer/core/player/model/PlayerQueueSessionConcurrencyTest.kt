package moe.ouom.neriplayer.core.player.model

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.core.player.persistence.PlaybackStatePersistenceSnapshot
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerQueueSessionConcurrencyTest {
    private val songs = (1L..3L).map { SongItem(it, "Song $it", "Artist", "Album", it, 100L, null) }

    @Test
    fun `persistence during shuffle sees the complete old session until the new one is published`() {
        val store = PlayerQueueStateStore().also { it.publish(songs, 1) }
        withPausedShuffle(store) {
            val before = persisted(store)
            assertEquals(listOf(1L, 2L, 3L), before.playlist.map { it.id })
            assertEquals(1, before.index)
            assertEquals(false, before.shuffleEnabled)
            assertNull(before.shuffleRestorePlaylist)
        }
        val after = persisted(store)
        assertEquals(listOf(2L, 3L, 1L), after.playlist.map { it.id })
        assertEquals(0, after.index)
        assertEquals(true, after.shuffleEnabled)
        assertEquals(listOf(1L, 2L, 3L), after.shuffleRestorePlaylist?.map { it.id })
        assertEquals(1, after.shuffleRestoreIndex)
    }

    @Test
    fun `a queued clear cannot leave a shuffle restore that resurrects deleted songs`() {
        val store = PlayerQueueStateStore().also { it.publish(songs, 1) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val clear = withPausedShuffle(store) {
                executor.submit { store.publish(emptyList(), -1) }
            }
            clear.get(5, TimeUnit.SECONDS)
            assertTrue(store.sessionSnapshot().shuffleEnabled)
            assertNull(store.sessionSnapshot().shuffleRestore)
            store.setLocalShuffle(false, songs[1])
            assertTrue(store.snapshot().playlist.isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a queued playlist replacement cannot inherit another playlist restore snapshot`() {
        val store = PlayerQueueStateStore().also { it.publish(songs, 1) }
        val replacement = PlayerQueueSnapshot.from(listOf(songs[2], songs[0]), 1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val start = withPausedShuffle(store) {
                executor.submit { store.startPlayback(replacement, true) { it.reverse() } }
            }
            start.get(5, TimeUnit.SECONDS)
            assertSame(replacement.playlist, store.sessionSnapshot().shuffleRestore?.playlist)
            store.setLocalShuffle(false, songs[0])
            assertEquals(listOf(3L, 1L), store.snapshot().playlist.map { it.id })
            assertEquals(1, store.snapshot().currentIndex)
            assertFalse(store.sessionSnapshot().shuffleEnabled)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun persisted(store: PlayerQueueStateStore): PersistedState {
        val session = store.sessionSnapshot()
        return PlaybackStatePersistenceSnapshot(
            session, PersistedPlaybackState(session.queue.currentIndex), keepShuffleMode = true
        ).toPersistedState()
    }

    private fun <T> withPausedShuffle(store: PlayerQueueStateStore, duringShuffle: () -> T): T {
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
            val result = duringShuffle()
            resume.countDown()
            shuffle.get(5, TimeUnit.SECONDS)
            return result
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }
}
