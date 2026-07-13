package moe.ouom.neriplayer.core.player.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNotificationPolicyTest {
    @Test
    fun `current song favorite change refreshes notification`() {
        assertTrue(
            hasCurrentSongFavoriteStateChanged(
                currentSongKey = "song-1",
                previousFavoriteSongKeys = emptySet(),
                updatedFavoriteSongKeys = setOf("song-1"),
            )
        )
        assertTrue(
            hasCurrentSongFavoriteStateChanged(
                currentSongKey = "song-1",
                previousFavoriteSongKeys = setOf("song-1"),
                updatedFavoriteSongKeys = emptySet(),
            )
        )
    }

    @Test
    fun `unrelated playlist changes do not refresh notification`() {
        assertFalse(
            hasCurrentSongFavoriteStateChanged(
                currentSongKey = "song-1",
                previousFavoriteSongKeys = setOf("song-2"),
                updatedFavoriteSongKeys = setOf("song-2", "song-3"),
            )
        )
    }

    @Test
    fun `missing current song does not refresh notification`() {
        assertFalse(
            hasCurrentSongFavoriteStateChanged(
                currentSongKey = null,
                previousFavoriteSongKeys = emptySet(),
                updatedFavoriteSongKeys = setOf("song-1"),
            )
        )
    }
}
