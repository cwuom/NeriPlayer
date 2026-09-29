package moe.ouom.neriplayer.core.player.queue.state
import moe.ouom.neriplayer.core.player.queue.TestQueueSongIdentity
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueSnapshot
import moe.ouom.neriplayer.core.player.queue.policy.PlayerQueueNavigationOwner
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerQueueSessionTest {
    private val songs = (1L..3L).map { SongItem(it, "Song $it", "Artist", "Album", it, 100L, null) }

    @Test
    fun `enabling twice keeps the original restore order and disabling consumes it once`() {
        val store = store()
        store.setLocalShuffle(true, songs[1]) { it.reverse() }
        val shuffled = store.sessionSnapshot()
        assertEquals(listOf(2L, 3L, 1L), shuffled.queue.playlist.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), shuffled.shuffleRestore?.playlist?.map { it.id })
        assertEquals(1, shuffled.shuffleRestore?.currentIndex)
        assertTrue(shuffled.shuffleEnabled)

        assertNull(store.setLocalShuffle(true, songs[1]) { error("must not reshuffle") })
        assertSame(shuffled, store.sessionSnapshot())
        store.setLocalShuffle(false, songs[1])
        val restored = store.sessionSnapshot()
        assertEquals(listOf(1L, 2L, 3L), restored.queue.playlist.map { it.id })
        assertEquals(1, restored.queue.currentIndex)
        assertFalse(restored.shuffleEnabled)
        assertNull(restored.shuffleRestore)
        assertNull(store.setLocalShuffle(false, songs[1]))
        assertSame(restored, store.sessionSnapshot())
    }

    @Test
    fun `restore uses the latest metadata and selected song`() {
        val store = store()
        store.setLocalShuffle(true, songs[1]) { it.reverse() }
        store.select(2)
        store.updateSongMatching(songs[0]) { it.copy(name = "Edited", matchedLyric = "New lyrics") }

        store.setLocalShuffle(false, songs[0])

        assertEquals(listOf(1L, 2L, 3L), store.snapshot().playlist.map { it.id })
        assertEquals("Edited", store.snapshot().playlist[0].name)
        assertEquals("New lyrics", store.snapshot().playlist[0].matchedLyric)
        assertEquals(0, store.snapshot().currentIndex)
    }

    @Test
    fun `failed restore consumes only the obsolete snapshot and preserves the edited queue`() {
        val store = store()
        store.setLocalShuffle(true, songs[1]) { it.reverse() }
        store.publish(listOf(songs[2], songs[1]), 1)

        assertNull(store.setLocalShuffle(false, songs[1]))
        assertEquals(listOf(3L, 2L), store.snapshot().playlist.map { it.id })
        assertNull(store.sessionSnapshot().shuffleRestore)
        store.setLocalShuffle(true, songs[1]) { it.reverse() }
        assertEquals(listOf(3L, 2L), store.sessionSnapshot().shuffleRestore?.playlist?.map { it.id })
        store.setLocalShuffle(false, songs[1])
        assertEquals(listOf(3L, 2L), store.snapshot().playlist.map { it.id })
    }

    @Test
    fun `repeat all reshuffles retain the first restore order`() {
        val store = store()
        store.setLocalShuffle(true, songs[1]) { it.reverse() }
        val restore = store.sessionSnapshot().shuffleRestore
        store.select(2)
        store.update { PlayerQueueNavigationOwner.repeatAllShuffle(it) { indices -> indices.reverse() } }

        assertSame(restore, store.sessionSnapshot().shuffleRestore)
        store.setLocalShuffle(false, songs[2])
        assertEquals(listOf(1L, 2L, 3L), store.snapshot().playlist.map { it.id })
        assertEquals(2, store.snapshot().currentIndex)
    }

    @Test
    fun `starting another playlist captures that playlist rather than the previous restore order`() {
        val store = store()
        store.setLocalShuffle(true, songs[1]) { it.reverse() }
        val replacement = PlayerQueueSnapshot.from(listOf(songs[2], songs[0]), 1)

        store.startPlayback(replacement, shuffleLocally = true) { it.reverse() }

        assertEquals(listOf(1L, 3L), store.snapshot().playlist.map { it.id })
        assertEquals(listOf(3L, 1L), store.sessionSnapshot().shuffleRestore?.playlist?.map { it.id })
        assertTrue(store.sessionSnapshot().shuffleEnabled)
        store.setLocalShuffle(false, songs[0])
        assertEquals(listOf(3L, 1L), store.snapshot().playlist.map { it.id })
    }

    @Test
    fun `remote playlist start keeps its order and clears local restore data`() {
        val store = store()
        store.setLocalShuffle(true, songs[1]) { it.reverse() }
        val remote = PlayerQueueSnapshot.from(songs.reversed(), 2)

        assertSame(remote, store.startPlayback(remote, shuffleLocally = false) { error("remote order") })
        assertEquals(2, store.snapshot().currentIndex)
        assertNull(store.sessionSnapshot().shuffleRestore)
        assertTrue(store.sessionSnapshot().shuffleEnabled)
    }

    @Test
    fun `remote mode updates preserve restore state but explicit remote commands clear it`() {
        val store = store()
        store.setLocalShuffle(true, songs[1]) { it.reverse() }
        val before = store.sessionSnapshot()
        store.setShuffleMode(false)
        assertSame(before.queue, store.snapshot())
        assertSame(before.shuffleRestore, store.sessionSnapshot().shuffleRestore)
        assertFalse(store.shuffleModeFlow.value)

        store.setShuffleMode(true, clearRestore = true)
        assertSame(before.queue, store.snapshot())
        assertNull(store.sessionSnapshot().shuffleRestore)
        assertTrue(store.shuffleModeFlow.value)
    }

    @Test
    fun `clearing the last song drops restore data without changing shuffle preference`() {
        val store = store()
        store.setLocalShuffle(true, songs[1]) { it.reverse() }
        store.update { PlayerQueueSnapshot.EMPTY }
        assertNull(store.sessionSnapshot().shuffleRestore)
        assertTrue(store.sessionSnapshot().shuffleEnabled)
        assertNull(store.setLocalShuffle(false, songs[1]))
        assertTrue(store.snapshot().playlist.isEmpty())
        assertEquals(-1, store.snapshot().currentIndex)
    }

    @Test
    fun `empty and singleton queues can toggle shuffle and a missing selection has a valid fallback`() {
        val empty = PlayerQueueStateStore(TestQueueSongIdentity)
        assertNull(empty.setLocalShuffle(true, null))
        assertNull(empty.sessionSnapshot().shuffleRestore)
        assertTrue(empty.sessionSnapshot().shuffleEnabled)

        empty.startPlayback(PlayerQueueSnapshot.from(listOf(songs[0]), -1), shuffleLocally = true)
        assertEquals(0, empty.snapshot().currentIndex)
        assertEquals(0, empty.sessionSnapshot().shuffleRestore?.currentIndex)
        empty.setLocalShuffle(false, null)
        assertEquals(listOf(1L), empty.snapshot().playlist.map { it.id })
        assertEquals(0, empty.snapshot().currentIndex)
    }

    @Test
    fun `hydration owns its input lists and publishes mode queue and restore data together`() {
        val store = store()
        val restore = songs.toMutableList()
        val queued = songs.reversed().toMutableList()
        store.restoreSession(PlayerQueueSnapshot.from(queued, 1), true, PlayerQueueSnapshot.from(restore, 1))
        queued.clear()
        restore.clear()
        val session = store.sessionSnapshot()
        assertEquals(listOf(3L, 2L, 1L), session.queue.playlist.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), session.shuffleRestore?.playlist?.map { it.id })
        assertTrue(session.shuffleEnabled)
        assertEquals(session.queue.playlist, store.playlistFlow.value)
        assertEquals(session.shuffleEnabled, store.shuffleModeFlow.value)
        assertTrue(store.clearShuffleRestore())
        assertFalse(store.clearShuffleRestore())
        assertSame(session.queue, store.snapshot())
    }

    @Test
    fun `hydration without shuffle or without a queue cannot retain stale restore data`() {
        val store = store()
        val queue = store.snapshot()
        store.restoreSession(queue, false, queue)
        assertNull(store.sessionSnapshot().shuffleRestore)
        store.restoreSession(PlayerQueueSnapshot.EMPTY, true, queue)
        assertNull(store.sessionSnapshot().shuffleRestore)
        assertTrue(store.snapshot().playlist.isEmpty())
    }

    private fun store() = PlayerQueueStateStore(TestQueueSongIdentity).also { it.publish(songs, 1) }
}
