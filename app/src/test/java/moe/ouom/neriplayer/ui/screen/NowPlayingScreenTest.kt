package moe.ouom.neriplayer.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceType
import moe.ouom.neriplayer.data.model.SongItem

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

    @Test
    fun `queue item key keeps duplicate songs visible`() {
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null
        )

        assertNotEquals(
            buildNowPlayingQueueItemKey(index = 0, song = song),
            buildNowPlayingQueueItemKey(index = 1, song = song)
        )
    }

    @Test
    fun `playback source badge uses resolved bili audio source over netease tag`() {
        val sourceType = resolveNowPlayingPlaybackSourceType(
            isLocalSong = false,
            isYouTubeMusicSong = false,
            isFromNeteaseTag = true,
            isFromBiliTag = false,
            currentMediaUrl = "https://m701.music.126.net/demo.mp3",
            playbackAudioSource = PlaybackAudioSource.BILIBILI
        )

        assertTrue(sourceType == PlaybackSourceType.BILIBILI)
    }

    @Test
    fun `remote local cache does not override original platform badge`() {
        val sourceType = resolveNowPlayingPlaybackSourceType(
            isLocalSong = false,
            isYouTubeMusicSong = false,
            isFromNeteaseTag = true,
            isFromBiliTag = false,
            currentMediaUrl = "content://downloads/demo.flac",
            playbackAudioSource = PlaybackAudioSource.LOCAL
        )

        assertTrue(sourceType == PlaybackSourceType.NETEASE)
    }
}
