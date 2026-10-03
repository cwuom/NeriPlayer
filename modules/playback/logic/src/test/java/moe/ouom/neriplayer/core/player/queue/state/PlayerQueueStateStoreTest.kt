package moe.ouom.neriplayer.core.player.queue.state
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.core.player.queue.TestQueueSongIdentity
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueSnapshot
import moe.ouom.neriplayer.core.player.queue.policy.reorderQueueSongsPreservingLatestMetadata
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerQueueStateStoreTest {
    @Test
    fun `song projection updates active and shuffle restore atomically without changing selection or order`() {
        val first = song(1, "First")
        val second = song(2, "Second")
        val store = PlayerQueueStateStore(TestQueueSongIdentity)
        store.restoreSession(PlayerQueueSnapshot.from(listOf(second, first), 1), true,
            PlayerQueueSnapshot.from(listOf(first, second), 0))
        assertTrue(store.projectSongs { songs -> songs.map { it.copy(matchedLyric = "remote", lyricSyncRevision = 20) } })
        val after = store.sessionSnapshot()
        val restored = checkNotNull(after.shuffleRestore)
        assertEquals(listOf(2L, 1L), after.queue.playlist.map { it.id })
        assertEquals(listOf(1L, 2L), restored.playlist.map { it.id })
        assertEquals(1, after.queue.currentIndex)
        assertEquals(0, restored.currentIndex)
        assertTrue(after.shuffleEnabled)
        assertEquals("remote", after.queue.playlist[1].matchedLyric)
        assertEquals("remote", restored.playlist[0].matchedLyric)
        assertFalse(store.projectSongs { it })
        assertSame(after, store.sessionSnapshot())
    }

    @Test
    fun `failed shuffle projection publishes neither half of the queue state`() {
        val store = PlayerQueueStateStore(TestQueueSongIdentity)
        store.restoreSession(PlayerQueueSnapshot.from(listOf(song(1, "First")), 0), true,
            PlayerQueueSnapshot.from(listOf(song(2, "Second")), 0))
        val before = store.sessionSnapshot()
        val failure = runCatching {
            store.projectSongs { songs ->
                check(songs.single().id == 1L) { "restore projection failed" }
                songs.map { it.copy(matchedLyric = "remote") }
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertSame(before, store.sessionSnapshot())
    }

    @Test
    fun `late metadata update follows song identity and keeps the latest selection`() {
        val first = song(1L, "First")
        val second = song(2L, "Second")
        val third = song(3L, "Third")
        val store = PlayerQueueStateStore(TestQueueSongIdentity)
        store.publish(listOf(first, second, third), currentIndex = 0)

        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pendingUpdate = executor.submit<SongItem?> {
                started.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                store.updateSongMatching(first) { it.copy(name = "Edited") }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))

            store.publish(listOf(third, second, first), currentIndex = 0)
            store.select(1)
            resume.countDown()
            assertEquals("Edited", pendingUpdate.get(5, TimeUnit.SECONDS)?.name)

            val snapshot = store.snapshot()
            assertEquals(listOf(3L, 2L, 1L), snapshot.playlist.map { it.id })
            assertEquals("Edited", snapshot.playlist[2].name)
            assertEquals(1, snapshot.currentIndex)
            assertEquals(second.id, snapshot.playlist[snapshot.currentIndex].id)
            assertEquals(snapshot.playlist, store.playlistFlow.value)
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `late metadata update cannot resurrect a removed song`() {
        val first = song(1L, "First")
        val second = song(2L, "Second")
        val store = PlayerQueueStateStore(TestQueueSongIdentity)
        store.publish(listOf(first, second), currentIndex = 0)
        store.publish(listOf(second), currentIndex = 0)

        assertEquals(null, store.updateSongMatching(first) { it.copy(name = "Edited") })
        assertEquals(listOf(2L), store.snapshot().playlist.map { it.id })
        assertEquals(0, store.snapshot().currentIndex)
    }

    @Test
    fun `a stale reorder request preserves metadata written before the transaction`() {
        val first = song(1L, "First")
        val second = song(2L, "Second")
        val store = PlayerQueueStateStore(TestQueueSongIdentity)
        store.publish(listOf(first, second), currentIndex = 0)
        val requestedOrder = store.snapshot().playlist.reversed()

        store.updateSongMatching(first) { it.copy(name = "Edited") }
        val reordered = checkNotNull(store.update { snapshot ->
            val latestSongs = reorderQueueSongsPreservingLatestMetadata(
                currentQueue = snapshot.playlist,
                requestedQueue = requestedOrder, identity = TestQueueSongIdentity) ?: return@update null
            PlayerQueueSnapshot.from(latestSongs, currentIndex = 1)
        })

        assertEquals(listOf("Second", "Edited"), reordered.playlist.map { it.name })
        assertEquals(1L, reordered.playlist[reordered.currentIndex].id)
        assertEquals(reordered.playlist, store.playlistFlow.value)
    }

    private fun song(id: Long, name: String): SongItem = SongItem(
        id = id,
        name = name,
        artist = "Artist",
        album = "Album",
        albumId = id,
        durationMs = 180_000L,
        coverUrl = null
    )
}
