package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.ui.component.playback.resolveMiniPlayerDisplayedCoverUrl
import moe.ouom.neriplayer.ui.screen.NowPlayingCoverFrame
import moe.ouom.neriplayer.ui.screen.buildNowPlayingCoverRequest
import moe.ouom.neriplayer.ui.screen.retainNowPlayingCoverFrame
import moe.ouom.neriplayer.ui.screen.shouldCommitNowPlayingCoverRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeriAppPlaybackTransitionPolicyTest {

    @Test
    fun `cover seed warmup is deferred only for uncached mini player transitions`() {
        assertEquals(
            180L,
            resolveCoverSeedWarmupDelayMillis(
                showNowPlaying = false,
                dynamicColorEnabled = true,
                hasCachedSample = false
            )
        )
    }

    @Test
    fun `cover seed warmup is immediate when now playing is already visible`() {
        assertEquals(
            0L,
            resolveCoverSeedWarmupDelayMillis(
                showNowPlaying = true,
                dynamicColorEnabled = true,
                hasCachedSample = false
            )
        )
    }

    @Test
    fun `cover seed warmup is skipped when dynamic color is disabled or cache is warm`() {
        assertEquals(
            0L,
            resolveCoverSeedWarmupDelayMillis(
                showNowPlaying = false,
                dynamicColorEnabled = false,
                hasCachedSample = false
            )
        )
        assertEquals(
            0L,
            resolveCoverSeedWarmupDelayMillis(
                showNowPlaying = false,
                dynamicColorEnabled = true,
                hasCachedSample = true
            )
        )
    }

    @Test
    fun `visual cover keeps previous image while new cover resolves`() {
        assertEquals(
            "old-cover",
            resolvePlaybackVisualCoverUrl(
                currentCoverUrl = null,
                previousVisualCoverUrl = "old-cover",
                hasCurrentSong = true
            )
        )
    }

    @Test
    fun `local playback keeps previous visual cover while its cover resolves`() {
        assertEquals(
            "remote-cover",
            resolvePlaybackVisualCoverUrl(
                currentCoverUrl = null,
                previousVisualCoverUrl = "remote-cover",
                hasCurrentSong = true
            )
        )
    }

    @Test
    fun `visual cover keeps the last successful frame across a song switch`() {
        assertEquals(
            "previous-song-cover",
            resolvePlaybackVisualCoverUrl(
                currentCoverUrl = null,
                previousVisualCoverUrl = "previous-song-cover",
                hasCurrentSong = true
            )
        )
    }

    @Test
    fun `visual cover clears only when playback stops`() {
        assertEquals(
            "old-cover",
            resolvePlaybackVisualCoverUrl(
                currentCoverUrl = null,
                previousVisualCoverUrl = "old-cover",
                hasCurrentSong = true
            )
        )
        assertEquals(
            null,
            resolvePlaybackVisualCoverUrl(
                currentCoverUrl = null,
                previousVisualCoverUrl = "old-cover",
                hasCurrentSong = false
            )
        )
    }

    @Test
    fun `visual cover prefers current non blank image`() {
        assertEquals(
            "new-cover",
            resolvePlaybackVisualCoverUrl(
                currentCoverUrl = "  new-cover  ",
                previousVisualCoverUrl = "old-cover",
                hasCurrentSong = true
            )
        )
    }

    @Test
    fun `now playing retains a decoded frame while the next song cover loads`() {
        val displayedFrame = NowPlayingCoverFrame(
            coverUrl = "content://downloads/Covers/song-a.jpg",
            cacheKey = "song-a-cache"
        )

        assertEquals(
            displayedFrame,
            retainNowPlayingCoverFrame(displayedFrame, hasCurrentSong = true)
        )
        assertNull(retainNowPlayingCoverFrame(displayedFrame, hasCurrentSong = false))
    }

    @Test
    fun `downloaded file cover uses the same atomic frame contract`() {
        val displayedFrame = NowPlayingCoverFrame(
            coverUrl = "file:///downloads/Covers/song-a.jpg",
            cacheKey = "song-a-cache"
        )
        val pendingRequest = buildNowPlayingCoverRequest(
            coverUrl = "file:///downloads/Covers/song-b.jpg",
            songKey = "song-b",
            coverCacheKey = "song-b-cache"
        )!!

        assertEquals(
            displayedFrame,
            retainNowPlayingCoverFrame(displayedFrame, hasCurrentSong = true)
        )
        assertNotEquals(displayedFrame, pendingRequest.frame)
    }

    @Test
    fun `stale hidden load cannot overwrite a rapid SAF cover switch`() {
        val displayedRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://tree-a/Covers/song-a.jpg",
            songKey = "song-a",
            coverCacheKey = "generation-1"
        )!!
        val staleRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://tree-a/Covers/song-b.jpg",
            songKey = "song-b",
            coverCacheKey = "generation-1"
        )!!
        val latestRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://tree-b/Covers/song-c.jpg",
            songKey = "song-c",
            coverCacheKey = "generation-2"
        )!!

        var displayedFrame = displayedRequest.frame
        val publishedUrls = mutableListOf(displayedFrame.coverUrl)
        if (shouldCommitNowPlayingCoverRequest(staleRequest, latestRequest)) {
            displayedFrame = staleRequest.frame
        }
        publishedUrls += displayedFrame.coverUrl
        if (shouldCommitNowPlayingCoverRequest(latestRequest, latestRequest)) {
            displayedFrame = latestRequest.frame
        }
        publishedUrls += displayedFrame.coverUrl

        assertEquals(
            listOf(
                "content://tree-a/Covers/song-a.jpg",
                "content://tree-a/Covers/song-a.jpg",
                "content://tree-b/Covers/song-c.jpg"
            ),
            publishedUrls
        )
    }

    @Test
    fun `same SAF uri with a new generation preloads before atomic replacement`() {
        val oldRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://downloads/Covers/song.jpg",
            songKey = "song",
            coverCacheKey = "generation-1"
        )!!
        val refreshedRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://downloads/Covers/song.jpg",
            songKey = "song",
            coverCacheKey = "generation-2"
        )!!

        assertEquals(oldRequest.frame.coverUrl, refreshedRequest.frame.coverUrl)
        assertNotEquals(oldRequest.frame, refreshedRequest.frame)
    }

    @Test
    fun `mini player keeps its cover while a newly resolved cover loads`() {
        assertEquals(
            "old-cover",
            resolveMiniPlayerDisplayedCoverUrl(
                requestedCoverUrl = "new-cover",
                displayedCoverUrl = "old-cover",
                requestSucceeded = false
            )
        )
        assertEquals(
            "new-cover",
            resolveMiniPlayerDisplayedCoverUrl(
                requestedCoverUrl = "new-cover",
                displayedCoverUrl = "old-cover",
                requestSucceeded = true
            )
        )
        assertEquals(
            "old-cover",
            resolveMiniPlayerDisplayedCoverUrl(
                requestedCoverUrl = null,
                displayedCoverUrl = "old-cover",
                requestSucceeded = false
            )
        )
        assertNull(
            resolveMiniPlayerDisplayedCoverUrl(
                requestedCoverUrl = null,
                displayedCoverUrl = "old-cover",
                requestSucceeded = false,
                clearDelayElapsed = true
            )
        )
    }

    @Test
    fun `cover seed is ignored until it belongs to the visual cover`() {
        assertNull(
            resolveActiveCoverSeedHex(
                visualCoverUrl = "new-cover",
                sampledCoverUrl = "old-cover",
                sampledSeedHex = "112233"
            )
        )
        assertEquals(
            "445566",
            resolveActiveCoverSeedHex(
                visualCoverUrl = "new-cover",
                sampledCoverUrl = "new-cover",
                sampledSeedHex = "445566"
            )
        )
    }

    @Test
    fun `cover seed follows the normalized cover cache key`() {
        assertEquals(
            "778899",
            resolveActiveCoverSeedHex(
                visualCoverUrl = "https://p1.music.126.net/cover.jpg?param=140y140",
                sampledCoverUrl = "https://p2.music.126.net/cover.jpg?param=500y500",
                sampledSeedHex = "778899"
            )
        )
    }

    @Test
    fun `cover seed stays stable until the rebound cover sample is ready`() {
        assertEquals(
            "778899",
            resolveActiveCoverSeedHex(
                visualCoverUrl = "content://current/Covers/cover.jpg",
                sampledCoverUrl = "content://old/Covers/cover.jpg",
                sampledSeedHex = "778899",
                currentSongKey = "song-key",
                sampledSongKey = "song-key"
            )
        )
        assertEquals(
            "778899",
            resolveActiveCoverSeedHex(
                visualCoverUrl = "content://current/Covers/cover.jpg",
                sampledCoverUrl = "content://old/Covers/cover.jpg",
                sampledSeedHex = "778899",
                currentSongKey = "new-song",
                sampledSongKey = "old-song"
            )
        )
    }
}
