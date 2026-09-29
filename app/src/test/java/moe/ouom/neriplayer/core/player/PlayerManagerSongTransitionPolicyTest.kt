package moe.ouom.neriplayer.core.player

import moe.ouom.neriplayer.data.identity.stableKey

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlayerManagerSongTransitionPolicyTest {
    @Test
    fun `previous progress is saved only when song identity changes`() {
        val previous = song(1L)

        assertNull(previousSongForLongFormProgress(null, song(2L)))
        assertNull(previousSongForLongFormProgress(previous, song(1L)))
        assertSame(previous, previousSongForLongFormProgress(previous, song(2L)))
        assertSame(previous, previousSongForLongFormProgress(previous, null))
    }

    @Test
    fun `playlist play attribution requires the current song in that playlist`() {
        val song = song(1L)
        val source = LocalPlaylistPlaybackSource(
            playlistId = 77L,
            songKeys = setOf(song.stableKey())
        )

        assertNull(localPlaylistIdForSong(null, song))
        assertEquals(77L, localPlaylistIdForSong(source, song))
        assertNull(localPlaylistIdForSong(source, song(2L)))
        assertNull(localPlaylistIdForSong(source, null))
    }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "song $id",
        artist = "artist",
        album = "album",
        albumId = 1L,
        durationMs = 60_000L,
        coverUrl = null
    )
}
