package moe.ouom.neriplayer.core.download.manager.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalDownloadManagerDeletionBarrierTest {
    private val manager = GlobalDownloadManager
    private val begun = mutableListOf<String>()

    @After
    fun tearDown() {
        manager.endDownloadedSongDeletion(begun.toList())
        begun.clear()
    }

    @Test
    fun `deletion barrier is reference counted per song`() = runTest {
        assertFalse(manager.isDownloadedSongDeletionActive())
        assertTrue(manager.awaitAllDownloadedSongDeletions())

        begin("a", "a", "b")
        end("a")
        assertTrue(manager.isDownloadedSongDeletionActive())
        assertTrue(manager.awaitDownloadedSongDeletion(listOf("c", " ")))
        assertTrue(manager.awaitDownloadedSongDeletion(emptyList()))

        end("a", "b")
        assertFalse(manager.isDownloadedSongDeletionActive())
        assertTrue(manager.awaitDownloadedSongDeletion(listOf("a", "b")))
    }

    @Test
    fun `waiting for a deletion that never ends times out`() = runTest {
        begin("a")

        assertFalse(manager.awaitDownloadedSongDeletion(listOf("a")))
        assertFalse(manager.awaitAllDownloadedSongDeletions())
    }

    @Test
    fun `waiters resume once the deletion ends`() = runTest {
        begin("a")
        val one = async { manager.awaitDownloadedSongDeletion(listOf("a")) }
        val all = async { manager.awaitAllDownloadedSongDeletions() }
        runCurrent()
        assertFalse(one.isCompleted)
        assertFalse(all.isCompleted)

        end("a")

        assertTrue(one.await())
        assertTrue(all.await())
    }

    @Test
    fun `deletion keys use trimmed stable keys and skip songs without one`() {
        val keys = manager.downloadedSongDeletionKeys(
            listOf(downloaded(" 1|Album| "), downloaded(" "), downloaded(null), downloaded("1|Album|"))
        )

        assertEquals(setOf("1|Album|"), keys)
    }

    private fun begin(vararg keys: String) {
        begun += keys
        manager.beginDownloadedSongDeletion(keys.toList())
    }

    private fun end(vararg keys: String) {
        keys.forEach(begun::remove)
        manager.endDownloadedSongDeletion(keys.toList())
    }

    private fun downloaded(stableKey: String?): DownloadedSong {
        return DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/Song.mp3",
            fileSize = 1L,
            downloadTime = 1L,
            stableKey = stableKey
        )
    }
}
