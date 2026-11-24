package moe.ouom.neriplayer.ui.screen.playlist

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalArtistSummary
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeContentLabel
import moe.ouom.neriplayer.platform.youtube.settings.YouTubeContentPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalArtistDetailScreenTest {

    @Test
    fun `artist duration follows the songs visible under current content preferences`() {
        val audio = SongItem(
            id = 1, name = "Audio", artist = "Artist", album = "", albumId = 0, durationMs = 60_000,
            coverUrl = null, channelId = "youtubeMusic"
        )
        val video = audio.copy(id = 2, name = "Video", durationMs = 100_000, youtubeContentLabel = YouTubeContentLabel.MUSIC_VIDEO)
        val artist = LocalArtistSummary("Artist", listOf(audio, video))
        val all = LocalArtistDetailSnapshot(artist, YouTubeContentPreferences())
        val filtered = LocalArtistDetailSnapshot(artist, YouTubeContentPreferences(hideMusicVideos = true))
        assertEquals(2, all.songs.size)
        assertEquals(160_000L, all.totalDurationMs)
        assertEquals(listOf(audio), filtered.songs)
        assertEquals(60_000L, filtered.totalDurationMs)
    }

    @Test
    fun `missing artist is retained until local playlists are ready`() {
        assertFalse(
            shouldRemoveMissingLocalArtistUsage(
                localPlaylistsReady = false,
                artistFound = false
            )
        )
    }

    @Test
    fun `missing artist is removed after local playlists are ready`() {
        assertTrue(
            shouldRemoveMissingLocalArtistUsage(
                localPlaylistsReady = true,
                artistFound = false
            )
        )
        assertFalse(
            shouldRemoveMissingLocalArtistUsage(
                localPlaylistsReady = true,
                artistFound = true
            )
        )
    }

    @Test
    fun `search index stays deferred until local artist search is visible`() {
        assertFalse(
            shouldBuildPlaylistSearchIndex(
                searchVisible = false,
                query = ""
            )
        )
        assertTrue(
            shouldBuildPlaylistSearchIndex(
                searchVisible = true,
                query = ""
            )
        )
        assertTrue(
            shouldBuildPlaylistSearchIndex(
                searchVisible = false,
                query = "周杰伦"
            )
        )
    }
}
