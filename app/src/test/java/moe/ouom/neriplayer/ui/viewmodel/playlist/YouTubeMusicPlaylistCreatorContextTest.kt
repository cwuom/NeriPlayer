package moe.ouom.neriplayer.ui.viewmodel.playlist

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeMusicPlaylistCreatorContextTest {

    @Test
    fun keepsCreatorFromIncomingArtistPageWhenPreviousStateHasNone() {
        val previous = YouTubeMusicPlaylist(
            browseId = "MPREalbum",
            playlistId = "MPREalbum",
            title = "Demo Album",
            subtitle = "Album",
            coverUrl = ""
        )
        val incoming = previous.copy(creatorName = "Demo Creator")

        val resolved = retainYouTubeMusicPlaylistCreatorContext(incoming, previous)

        assertEquals("Demo Creator", resolved.creatorName)
    }

    @Test
    fun keepsCreatorFromPreviousStateWhenTheIncomingPlaylistHasNone() {
        val previous = YouTubeMusicPlaylist(
            browseId = "MPREalbum",
            playlistId = "MPREalbum",
            title = "Demo Album",
            subtitle = "Album",
            coverUrl = "",
            creatorName = "Demo Creator"
        )

        val resolved = retainYouTubeMusicPlaylistCreatorContext(
            playlist = previous.copy(creatorName = ""),
            previousPlaylist = previous
        )

        assertEquals("Demo Creator", resolved.creatorName)
    }

    @Test
    fun usesCreatorContextWhenPlaylistTrackHasNoArtist() {
        assertEquals(
            "Demo Creator",
            resolveYouTubeMusicPlaylistTrackArtist(
                trackArtist = "",
                playlistCreatorName = "Demo Creator"
            )
        )
    }

    @Test
    fun keepsTrackArtistWhenPlaylistHasCreatorContext() {
        assertEquals(
            "Guest Artist",
            resolveYouTubeMusicPlaylistTrackArtist(
                trackArtist = "Guest Artist",
                playlistCreatorName = "Demo Creator"
            )
        )
    }

    @Test
    fun fillsOnlyMissingArtistInReusedTracks() {
        val tracks = listOf(
            song(artist = ""),
            song(artist = "Guest Artist"),
            song(artist = "", customArtist = "User Artist")
        )

        val resolved = applyYouTubeMusicPlaylistCreatorContext(
            tracks = tracks,
            creatorName = "Demo Creator"
        )

        assertEquals("Demo Creator", resolved[0].artist)
        assertEquals("Demo Creator", resolved[0].originalArtist)
        assertEquals("Guest Artist", resolved[1].artist)
        assertEquals("", resolved[2].artist)
        assertEquals("User Artist", resolved[2].customArtist)
    }

    private fun song(artist: String, customArtist: String? = null): SongItem {
        return SongItem(
            id = 1L,
            name = "Demo Song",
            artist = artist,
            album = "Demo Album",
            albumId = 1L,
            durationMs = 0L,
            coverUrl = null,
            customArtist = customArtist
        )
    }
}
