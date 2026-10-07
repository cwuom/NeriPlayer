package moe.ouom.neriplayer.data.local.media

import android.content.Context
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalArtistSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

class LocalArtistCoverSelectionTest {
    private val context: Context = mock(Context::class.java)

    @Test
    fun `artist covers come from the first song with a usable cover`() {
        val artist = LocalArtistSummary(
            name = "Artist",
            songs = listOf(
                song(id = 1, coverUrl = null),
                song(id = 2, coverUrl = "   "),
                song(id = 3, coverUrl = "/sdcard/covers/three.jpg"),
                song(id = 4, coverUrl = "/sdcard/covers/four.jpg")
            )
        )

        assertEquals("/sdcard/covers/three.jpg", artist.displayCoverUrl(context, resolveLocalMetadataFallback = false))
    }

    @Test
    fun `artists whose songs only carry media store album art have no cover`() {
        val artist = LocalArtistSummary(
            name = "Artist",
            songs = listOf(
                song(id = 1, coverUrl = null),
                song(id = 2, coverUrl = "content://media/external/audio/albumart/12")
            )
        )

        assertNull(artist.displayCoverUrl(context, resolveLocalMetadataFallback = false))
    }

    private fun song(id: Long, coverUrl: String?) = SongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 9,
        durationMs = 0,
        coverUrl = coverUrl,
        mediaUri = "https://cdn.example/$id.mp3"
    )
}
