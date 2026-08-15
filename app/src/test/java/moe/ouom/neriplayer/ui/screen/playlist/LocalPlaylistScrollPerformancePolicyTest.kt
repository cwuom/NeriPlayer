package moe.ouom.neriplayer.ui.screen.playlist

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.util.shouldResolveEmbeddedCoverFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistScrollPerformancePolicyTest {

    @Test
    fun `row artwork fallback remains enabled while the list is scrolling`() {
        assertTrue(shouldResolveLocalPlaylistRowArtworkFallback(isScrollInProgress = true))
    }

    @Test
    fun `row artwork fallback runs once the list is idle`() {
        assertTrue(shouldResolveLocalPlaylistRowArtworkFallback(isScrollInProgress = false))
    }

    @Test
    fun `idle row artwork fallback enables embedded cover resolution during playback`() {
        val resolveFallback = shouldResolveLocalPlaylistRowArtworkFallback(
            isScrollInProgress = false,
            hasReachedIdleWindow = true
        )

        assertTrue(
            shouldResolveEmbeddedCoverFallback(
                resolveLocalFallback = resolveFallback,
                allowEmbeddedCoverFallback = resolveFallback
            )
        )
    }

    @Test
    fun `row artwork fallback waits for the idle window after scrolling stops`() {
        assertFalse(
            shouldResolveLocalPlaylistRowArtworkFallback(
                isScrollInProgress = false,
                hasReachedIdleWindow = false
            )
        )
    }

    @Test
    fun `row artwork retains a loaded cover while a new request is deferred`() {
        assertEquals(
            "file:///cache/loaded.jpg",
            retainedLocalPlaylistArtworkUrl(
                displayedCoverUrl = "file:///cache/loaded.jpg",
                requestedCoverUrl = null
            )
        )
    }

    @Test
    fun `row artwork keeps the loaded cover until a replacement succeeds`() {
        assertEquals(
            "file:///cache/loaded.jpg",
            retainedLocalPlaylistArtworkUrl(
                displayedCoverUrl = "file:///cache/loaded.jpg",
                requestedCoverUrl = "file:///cache/new.jpg"
            )
        )
    }

    @Test
    fun `row artwork restores the resolved cache before an immediate source`() {
        assertEquals(
            "file:///cache/resolved.jpg",
            initialLocalPlaylistArtworkUrl(
                retainedCoverUrl = null,
                requestedCoverUrl = "file:///cache/resolved.jpg",
                immediateCoverUrl = "file:///source/immediate.jpg"
            )
        )
    }

    @Test
    fun `row artwork keeps an immediate source when its cache is not ready`() {
        assertEquals(
            "file:///source/immediate.jpg",
            initialLocalPlaylistArtworkUrl(
                retainedCoverUrl = null,
                requestedCoverUrl = null,
                immediateCoverUrl = "file:///source/immediate.jpg"
            )
        )
    }

    @Test
    fun `row artwork retains a decoded cover when it reenters the viewport`() {
        assertEquals(
            "file:///cache/loaded.jpg",
            initialLocalPlaylistArtworkUrl(
                retainedCoverUrl = "file:///cache/loaded.jpg",
                requestedCoverUrl = null,
                immediateCoverUrl = null
            )
        )
    }

    @Test
    fun `favorite lookup keeps local source aliases equivalent`() {
        val favorite = localSong(id = 1L, audioId = "local-audio")
        val sameSource = localSong(id = 2L, audioId = "local-audio")

        assertTrue(SongIdentityLookup(listOf(favorite)).contains(sameSource))
    }

    @Test
    fun `favorite lookup does not match a different local source`() {
        val favorite = localSong(id = 1L, audioId = "local-audio-a")
        val differentSource = localSong(id = 2L, audioId = "local-audio-b")

        assertFalse(SongIdentityLookup(listOf(favorite)).contains(differentSource))
    }

    private fun localSong(id: Long, audioId: String): SongItem {
        return SongItem(
            id = id,
            name = "song",
            artist = "artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "local",
            audioId = audioId
        )
    }
}
