package moe.ouom.neriplayer.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

class NowPlayingScreenTest {

    @Test
    fun `download action remains visible when completed task exists but local file is gone`() {
        assertFalse(
            shouldHideDownloadActionForSong(
                hasLocalDownload = false,
                currentTask = null
            )
        )
    }

    @Test
    fun `download action hides only when actual local download exists`() {
        assertTrue(
            shouldHideDownloadActionForSong(
                hasLocalDownload = true,
                currentTask = null
            )
        )
    }

    @Test
    fun `unfinished task keeps download action visible even when local download probe hits`() {
        val task = DownloadTask(
            song = SongItem(
                id = 1L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null
            ),
            progress = null,
            status = DownloadStatus.DOWNLOADING
        )

        assertFalse(
            shouldHideDownloadActionForSong(
                hasLocalDownload = true,
                currentTask = task
            )
        )
    }
}
