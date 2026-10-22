package moe.ouom.neriplayer.ui.screen.history

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecentSongsStateTest {

    @Test
    fun `history mapping and identity preparation never read entries on the caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        val callerReads = AtomicInteger()
        val entries = object : AbstractList<PlayedEntry>() {
            override val size = 1_000

            override fun get(index: Int): PlayedEntry {
                if (Thread.currentThread() === caller) callerReads.incrementAndGet()
                return entry(index.toLong())
            }
        }

        val snapshot = prepareRecentSongs(entries)

        assertEquals(1_000, snapshot.songs.size)
        assertEquals("mapping all history entries must leave the UI thread", 0, callerReads.get())
    }

    @Test
    fun `history keeps queue order metadata and item identity when a song moves to the front`() = runBlocking {
        val local = entry(2L).copy(
            album = "__local_files__",
            localFileName = "track.flac",
            localFilePath = "/music/track.flac",
            mediaUri = "content://media/external/audio/media/2",
            customName = "Custom title",
            customArtist = "Custom artist",
            customCoverUrl = "file:///music/cover.jpg",
            explicitLocalSourceReference = "content://media/external/audio/media/2"
        )
        val original = prepareRecentSongs(listOf(entry(1L), local))
        val reordered = prepareRecentSongs(listOf(local, entry(1L)))

        assertEquals(listOf(1L, 2L), original.songs.map { it.id })
        assertEquals(listOf(2L, 1L), reordered.songs.map { it.id })
        assertEquals(original.rows[1].itemKey, reordered.rows[0].itemKey)
        assertEquals(original.rows[1].selectionKey, reordered.rows[0].selectionKey)
        assertSame(original.songs[1], original.rows[1].song)
        with(original.songs[1]) {
            assertEquals("track.flac", localFileName)
            assertEquals("Custom title", customName)
            assertEquals("Custom artist", customArtist)
            assertEquals("file:///music/cover.jpg", customCoverUrl)
            assertEquals("content://media/external/audio/media/2", explicitLocalSourceReference)
        }
    }

    @Test
    fun `equal numeric ids from separate sources retain separate lazy item identities`() = runBlocking {
        val snapshot = prepareRecentSongs(listOf(entry(7L), entry(7L).copy(album = "bilibili")))

        assertNotEquals(snapshot.rows[0].itemKey, snapshot.rows[1].itemKey)
        assertNotEquals(snapshot.rows[0].selectionKey, snapshot.rows[1].selectionKey)
    }

    @Test
    fun `cancelled history preparation cannot publish over its replacement`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val latest = AtomicReference<RecentSongsSnapshot>()
        val oldEntries = object : AbstractList<PlayedEntry>() {
            override val size = 1

            override fun get(index: Int): PlayedEntry {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return entry(1L)
            }
        }
        val oldJob = launch(Dispatchers.Default) {
            latest.set(prepareRecentSongs(oldEntries))
        }

        try {
            check(started.await(5, TimeUnit.SECONDS))
            oldJob.cancel()
            latest.set(prepareRecentSongs(listOf(entry(99L))))
        } finally {
            release.countDown()
            oldJob.join()
        }

        assertEquals(listOf(99L), latest.get().songs.map { it.id })
    }

    private fun entry(id: Long) = PlayedEntry(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        durationMs = 60_000L,
        coverUrl = null,
        playedAt = 1_000L - id
    )
}
