package moe.ouom.neriplayer.data.local.media

import android.content.Context
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class LocalMediaEmbeddedLyricSourcelessSongTest {
    @Test
    fun `songs without a local media reference are not inspected for embedded lyrics`() {
        val context = mock(Context::class.java)
        val song = song(mediaUri = null)

        assertNull(LocalMediaSupport.inspectEmbeddedLyrics(context, song))
        verifyNoInteractions(context)
    }

    @Test
    fun `remote stream songs are not inspected for embedded lyrics`() {
        val context = mock(Context::class.java)
        val song = song(mediaUri = "https://music.example/stream/31")

        assertNull(LocalMediaSupport.inspectEmbeddedLyrics(context, song))
        verifyNoInteractions(context)
    }

    private fun song(mediaUri: String?): SongItem {
        return SongItem(
            id = 31L,
            name = "Night Drive",
            artist = "Artist",
            album = "Album",
            albumId = 3L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = mediaUri,
            matchedLyric = "[00:01.00]matched"
        )
    }
}
