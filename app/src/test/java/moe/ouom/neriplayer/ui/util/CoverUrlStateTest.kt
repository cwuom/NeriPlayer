package moe.ouom.neriplayer.ui.util

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun `fast cover reference accepts provider uri without opening descriptor`() {
        assertTrue(isFastCoverReference("content://provider/document/cover"))
    }

    @Test
    fun `fast cover reference rejects missing file path`() {
        assertFalse(isFastCoverReference("/path/that/does/not/exist.jpg"))
    }

    @Test
    fun `local song probes sidecar even when a remote cover is already present`() {
        assertTrue(
            shouldProbeFastLocalCoverCandidate(
                isLocalSong = true,
                immediateCover = "https://example.com/remote-cover.jpg"
            )
        )
    }

    @Test
    fun `remote song keeps its immediate cover without a local probe`() {
        assertFalse(
            shouldProbeFastLocalCoverCandidate(
                isLocalSong = false,
                immediateCover = "https://example.com/remote-cover.jpg"
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
    fun `playlist cover signature uses a bounded candidate prefix`() {
        val original = List(33) { index -> song(coverUrl = null).copy(id = index.toLong()) }
        val updated = original.toMutableList().apply {
            this[lastIndex] = last().copy(coverUrl = "file:///music/later-cover.jpg")
        }

        assertEquals(
            playlistCoverResolutionSignature(original),
            playlistCoverResolutionSignature(updated)
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

    @Test
    fun `song cover cache key changes when the explicit cover changes`() {
        val original = song(coverUrl = "https://example.com/old.jpg")
        val updated = original.copy(coverUrl = "https://example.com/new.jpg")

        assertNotEquals(original.coverDisplayCacheKey(), updated.coverDisplayCacheKey())
    }

    @Test
    fun `playlist cover cache key changes when additional candidates change`() {
        val playlist = LocalPlaylist(
            id = 7L,
            name = "playlist",
            songs = mutableListOf()
        )
        val withoutCover = song(coverUrl = null)
        val withCover = withoutCover.copy(coverUrl = "file:///music/cover.jpg")

        assertNotEquals(
            playlistCoverResolutionCacheKey(playlist, listOf(withoutCover)),
            playlistCoverResolutionCacheKey(playlist, listOf(withCover))
        )
    }

    @Test
    fun `versioned cover cache key separates download generations`() {
        val baseKey = "song:local-track"

        assertEquals(
            "$baseKey|generation=3",
            versionedCoverCacheKey(baseKey, generation = 3)
        )
        assertNotEquals(
            versionedCoverCacheKey(baseKey, generation = 3),
            versionedCoverCacheKey(baseKey, generation = 4)
        )
    }

    @Test
    fun `versioned cover cache key keeps absent keys absent`() {
        assertNull(versionedCoverCacheKey(null, generation = 1))
        assertNull(versionedCoverCacheKey("  ", generation = 1))
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
