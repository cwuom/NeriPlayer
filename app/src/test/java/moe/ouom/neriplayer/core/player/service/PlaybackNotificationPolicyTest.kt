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

    @Test
    fun `favorite toggle is blocked until local playlists are ready`() {
        assertFalse(
            shouldAllowExternalFavoriteToggle(
                localPlaylistsReady = false,
                hasCurrentSong = true,
                requiresInteractiveConfirmation = false,
            )
        )
        assertTrue(
            shouldUseInteractiveFavoriteIntent(
                localPlaylistsReady = false,
                hasCurrentSong = true,
                isFavorite = false,
                isLocalSong = false,
            )
        )
    }

    @Test
    fun `favorite toggle is exposed after playlists are ready`() {
        assertTrue(
            shouldAllowExternalFavoriteToggle(
                localPlaylistsReady = true,
                hasCurrentSong = true,
                requiresInteractiveConfirmation = false,
            )
        )
        assertFalse(
            shouldUseInteractiveFavoriteIntent(
                localPlaylistsReady = true,
                hasCurrentSong = true,
                isFavorite = true,
                isLocalSong = true,
            )
        )
        assertTrue(
            shouldUseInteractiveFavoriteIntent(
                localPlaylistsReady = true,
                hasCurrentSong = true,
                isFavorite = false,
                isLocalSong = true,
            )
        )
    }
}
