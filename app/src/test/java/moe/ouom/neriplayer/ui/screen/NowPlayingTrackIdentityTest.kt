package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingTrackDisplay
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingTrackIdentityOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.resolveNowPlayingTrackDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTrackIdentityTest {
    @Test
    fun `title and artist menus have independent owner state`() {
        val owner = NowPlayingTrackIdentityOwner()
        owner.openNameMenu()
        owner.openArtistMenuAction()
        assertTrue(owner.showNameMenu)
        assertTrue(owner.showArtistMenu)

        owner.copyName(null)
        assertFalse(owner.showNameMenu)
        assertTrue(owner.showArtistMenu)

        owner.closeArtistMenuAction()
        assertFalse(owner.showArtistMenu)
    }

    @Test
    fun `track display uses edited metadata and keeps empty session nullable`() {
        val empty = resolveNowPlayingTrackDisplay(null)
        assertNull(empty.name)
        assertNull(empty.artist)
        val song = SongItem(
            id = 7L,
            name = "Original",
            artist = "Original artist",
            album = "Album",
            albumId = 0L,
            durationMs = 5_000L,
            coverUrl = null
        )
        assertEquals(
            NowPlayingTrackDisplay("Original", "Original artist"),
            resolveNowPlayingTrackDisplay(song)
        )
        assertEquals(
            NowPlayingTrackDisplay("Edited", "Edited artist"),
            resolveNowPlayingTrackDisplay(
                song.copy(
                    customName = "Edited",
                    customArtist = "Edited artist"
                )
            )
        )
    }
}
