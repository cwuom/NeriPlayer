package moe.ouom.neriplayer.ui.screen

import androidx.compose.material3.Typography
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingTrackDisplay
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingTrackIdentityOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.resolveNowPlayingTrackDisplay
import moe.ouom.neriplayer.ui.screen.nowplaying.resolveNowPlayingTrackIdentityPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTrackIdentityTest {
    @Test
    fun `compact identity uses the side pane typography and expanded identity stays centered`() {
        val typography = Typography(
            titleLarge = TextStyle(fontSize = 22.sp),
            headlineSmall = TextStyle(fontSize = 26.sp),
            bodyMedium = TextStyle(fontSize = 14.sp),
            bodyLarge = TextStyle(fontSize = 18.sp)
        )
        val compact = resolveNowPlayingTrackIdentityPresentation(true, typography)
        val expanded = resolveNowPlayingTrackIdentityPresentation(false, typography)
        assertSame(typography.titleLarge, compact.titleStyle)
        assertSame(typography.bodyMedium, compact.artistStyle)
        assertSame(Alignment.Start, compact.horizontalAlignment)
        assertSame(typography.headlineSmall, expanded.titleStyle)
        assertSame(typography.bodyLarge, expanded.artistStyle)
        assertSame(Alignment.CenterHorizontally, expanded.horizontalAlignment)
    }

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
