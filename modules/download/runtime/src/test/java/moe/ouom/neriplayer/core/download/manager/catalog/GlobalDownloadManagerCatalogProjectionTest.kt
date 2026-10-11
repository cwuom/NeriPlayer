package moe.ouom.neriplayer.core.download.manager.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.execution.persistence.testSong
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.download.DownloadProgress
import moe.ouom.neriplayer.data.model.download.DownloadStatus
import moe.ouom.neriplayer.data.model.download.DownloadTask
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalDownloadManagerCatalogProjectionTest {
    private val manager = GlobalDownloadManager
    private val song = testSong(1L)
    private val key = song.stableKey()

    @Test
    fun `changed song keys fall back to file path when the stable key is missing`() {
        val keyed = downloaded("/music/A.mp3", stableKey = "1|Album|")
        val blankKey = downloaded("/music/B.mp3", stableKey = " ")
        val noKey = downloaded("/music/C.mp3", stableKey = null)

        assertEquals(emptySet<String>(), manager.changedDownloadedSongKeys(listOf(keyed, blankKey), listOf(keyed, blankKey)))
        assertEquals(
            setOf("/music/B.mp3", "/music/C.mp3", "1|Album|"),
            manager.changedDownloadedSongKeys(
                listOf(keyed, blankKey),
                listOf(keyed.copy(fileSize = 9L), noKey)
            )
        )
    }

    @Test
    fun `latest progress overlays only an unambiguous match for the task attempt`() {
        val task = DownloadTask(song, progress = null, status = DownloadStatus.DOWNLOADING, attemptId = 2L)
        val current = progress("op-1", attemptId = 2L, bytes = 10L)

        assertSame(emptyList<DownloadTask>(), manager.overlayLatestProgress(emptyList(), mapOf("op-1" to current)))
        val tasks = listOf(task)
        assertSame(tasks, manager.overlayLatestProgress(tasks, emptyMap()))

        assertEquals(current, overlay(task, current).progress)
        val attemptless = progress("op-1", attemptId = null, bytes = 7L)
        assertEquals(attemptless, overlay(task, attemptless).progress)
        assertSame(task, overlay(task, progress("op-1", attemptId = 1L, bytes = 7L)))
        assertSame(task, overlay(task, current.copy(songKey = "other")))
        assertSame(task, overlay(task, current, progress("op-2", attemptId = 2L, bytes = 20L)))
    }

    @Test
    fun `task operation disambiguates competing progress for the same attempt`() {
        val owned = progress("op-2", attemptId = 2L, bytes = 5L)
        val task = DownloadTask(song, progress = owned, status = DownloadStatus.DOWNLOADING, attemptId = 2L)

        val merged = overlay(task, progress("op-1", attemptId = 2L, bytes = 30L), progress("op-2", attemptId = 2L, bytes = 20L))

        assertEquals("op-2", merged.progress?.operationId)
        assertEquals(20L, merged.progress?.bytesRead)
        assertSame(task, overlay(task, owned))
    }

    private fun overlay(task: DownloadTask, vararg progress: DownloadProgress): DownloadTask {
        val latest = progress.withIndex().associate { (index, value) -> "p$index" to value }
        return manager.overlayLatestProgress(listOf(task), latest).single()
    }

    private fun progress(operationId: String, attemptId: Long?, bytes: Long): DownloadProgress {
        return DownloadProgress(
            songKey = key,
            songId = song.id,
            fileName = "Song.mp3",
            bytesRead = bytes,
            totalBytes = 100L,
            speedBytesPerSec = 0L,
            attemptId = attemptId,
            operationId = operationId
        )
    }

    private fun downloaded(path: String, stableKey: String?): DownloadedSong {
        return DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = path,
            fileSize = 1L,
            downloadTime = 1L,
            stableKey = stableKey
        )
    }
}
