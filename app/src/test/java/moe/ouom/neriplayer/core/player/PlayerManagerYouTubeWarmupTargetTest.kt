package moe.ouom.neriplayer.core.player

import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerYouTubeWarmupTargetTest {

    @Test
    fun `resolveYouTubeWarmupTargets keeps current and next youtube ids`() {
        val targets = resolveYouTubeWarmupTargets(
            playlist = listOf(
                testSong(
                    id = 1L,
                    mediaUri = "https://music.youtube.com/watch?v=currentVideo"
                ),
                testSong(
                    id = 2L,
                    mediaUri = "https://music.youtube.com/watch?v=nextVideo"
                )
            ),
            currentSongIndex = 0,
            preferredQuality = "very_high"
        )

        assertTrue(targets.hasWork)
        assertEquals("currentVideo", targets.currentVideoId)
        assertEquals("nextVideo", targets.nextVideoId)
        assertEquals("very_high", targets.preferredQuality)
    }

    @Test
    fun `resolveYouTubeWarmupTargets ignores non youtube neighbors`() {
        val targets = resolveYouTubeWarmupTargets(
            playlist = listOf(
                testSong(id = 1L, mediaUri = "file:///sdcard/Music/local.mp3"),
                testSong(
                    id = 2L,
                    mediaUri = "https://music.youtube.com/watch?v=onlyCurrent"
                ),
                testSong(id = 3L, mediaUri = "https://example.com/audio.mp3")
            ),
            currentSongIndex = 1,
            preferredQuality = "medium"
        )

        assertTrue(targets.hasWork)
        assertEquals("onlyCurrent", targets.currentVideoId)
        assertEquals(null, targets.nextVideoId)
        assertEquals("medium", targets.preferredQuality)
    }

    @Test
    fun `resolveYouTubeWarmupTargets reports no work for non youtube queue`() {
        val targets = resolveYouTubeWarmupTargets(
            playlist = listOf(
                testSong(id = 1L, mediaUri = "file:///sdcard/Music/local.mp3"),
                testSong(id = 2L, mediaUri = "https://example.com/audio.mp3")
            ),
            currentSongIndex = 0,
            preferredQuality = "high"
        )

        assertFalse(targets.hasWork)
        assertEquals(null, targets.currentVideoId)
        assertEquals(null, targets.nextVideoId)
    }

    private fun testSong(id: Long, mediaUri: String): SongItem {
        return SongItem(
            id = id,
            name = "song-$id",
            artist = "artist",
            album = "album",
            albumId = id,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = mediaUri
        )
    }
}
