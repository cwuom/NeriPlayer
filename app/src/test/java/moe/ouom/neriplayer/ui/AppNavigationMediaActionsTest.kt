package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AppNavigationMediaActionsTest {
    @Test
    fun tabSongClickUsesTheUnscopedPlaybackRoute() {
        val songs = emptyList<SongItem>()
        var playedSongs: List<SongItem>? = null
        var playedIndex = -1
        var sourceRoute: String? = "unexpected"
        val actions = AppNavigationMediaActions(
            playSongs = { items, index, route ->
                playedSongs = items
                playedIndex = index
                sourceRoute = route
            },
            playBiliAudio = { _, _, _ -> },
            playBiliParts = { _, _, _, _ -> },
            onNeteaseAlbumClick = { },
            onYouTubePlaylistClick = { },
            onYouTubeCreatorClick = { }
        )

        actions.onSongClick(songs, 2)

        assertSame(songs, playedSongs)
        assertEquals(2, playedIndex)
        assertNull(sourceRoute)
    }
}
