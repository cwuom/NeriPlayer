package moe.ouom.neriplayer.ui.util

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverUrlStateTest {

    @Test
    fun `slow local cover fallback remains available during playback`() {
        assertTrue(
            shouldResolveSlowLocalCoverFallback(
                resolveLocalFallback = true
            )
        )
    }

    @Test
    fun `disabled local fallback remains disabled while idle`() {
        assertFalse(
            shouldResolveSlowLocalCoverFallback(
                resolveLocalFallback = false
            )
        )
    }

    @Test
    fun `cached local cover lookup does not reopen audio for list rows`() {
        assertFalse(
            shouldResolveEmbeddedCoverFallback(
                resolveLocalFallback = true,
                allowEmbeddedCoverFallback = false
            )
        )
    }

    @Test
    fun `embedded local cover fallback remains available outside scroll constrained rows`() {
        assertTrue(
            shouldResolveEmbeddedCoverFallback(
                resolveLocalFallback = true,
                allowEmbeddedCoverFallback = true
            )
        )
    }

    @Test
    fun `playlist fallback stops after an immediate cover is available`() {
        assertFalse(
            shouldResolvePlaylistCoverFallback(
                resolveLocalFallback = true,
                hasImmediateCover = true
            )
        )
    }

    @Test
    fun `playlist fallback remains available during playback`() {
        assertTrue(
            shouldResolvePlaylistCoverFallback(
                resolveLocalFallback = true,
                hasImmediateCover = false
            )
        )
    }

    @Test
    fun `playlist cover signature changes when a cover source changes`() {
        val original = song(coverUrl = null)
        val updated = original.copy(coverUrl = "file:///music/cover.jpg")

        assertNotEquals(
            playlistCoverResolutionSignature(listOf(original)),
            playlistCoverResolutionSignature(listOf(updated))
        )
    }

    @Test
    fun `song cover cache key survives local metadata hydration`() {
        val quickSong = SongItem(
            id = 7L,
            name = "track.mp3",
            artist = "Unknown",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 0L,
            coverUrl = null,
            mediaUri = "content://media/external/audio/media/7",
            channelId = "local",
            audioId = "7"
        )
        val hydratedSong = quickSong.copy(
            name = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L,
            localFilePath = "/music/Track.mp3"
        )

        assertEquals(
            quickSong.coverDisplayCacheKey(),
            hydratedSong.coverDisplayCacheKey()
        )
    }

    private fun song(coverUrl: String?): SongItem {
        return SongItem(
            id = 1L,
            name = "song",
            artist = "artist",
            album = "album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = coverUrl
        )
    }
}
