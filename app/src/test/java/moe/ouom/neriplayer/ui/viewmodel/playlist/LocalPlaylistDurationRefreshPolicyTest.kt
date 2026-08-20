package moe.ouom.neriplayer.ui.viewmodel.playlist

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistDurationRefreshPolicyTest {

    @Test
    fun `cover-only gaps do not schedule a duration refresh`() {
        assertFalse(
            shouldScheduleLocalDurationRefresh(
                localSong(durationMs = 180_000L, coverUrl = null)
            )
        )
    }

    @Test
    fun `missing duration with a local path is eligible`() {
        assertTrue(
            shouldScheduleLocalDurationRefresh(
                localSong(durationMs = 0L, localFilePath = "/music/song.mp3")
            )
        )
    }

    @Test
    fun `missing duration with a file uri is eligible`() {
        assertTrue(
            shouldScheduleLocalDurationRefresh(
                localSong(
                    durationMs = 0L,
                    localFilePath = null,
                    mediaUri = "file:///music/song.mp3"
                )
            )
        )
    }

    @Test
    fun `missing duration without a local source is ignored`() {
        assertFalse(
            shouldScheduleLocalDurationRefresh(
                localSong(durationMs = 0L, localFilePath = null, mediaUri = null)
            )
        )
    }

    private fun localSong(
        durationMs: Long,
        coverUrl: String? = "file:///music/cover.jpg",
        localFilePath: String? = "/music/song.mp3",
        mediaUri: String? = "file:///music/song.mp3"
    ): SongItem {
        return SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = durationMs,
            coverUrl = coverUrl,
            mediaUri = mediaUri,
            localFilePath = localFilePath,
            channelId = "local"
        )
    }
}
