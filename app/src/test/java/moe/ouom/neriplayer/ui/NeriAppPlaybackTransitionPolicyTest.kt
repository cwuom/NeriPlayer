package moe.ouom.neriplayer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import moe.ouom.neriplayer.ui.component.playback.resolveMiniPlayerDisplayedCoverUrl
import moe.ouom.neriplayer.ui.screen.NowPlayingCoverFrame
import moe.ouom.neriplayer.ui.screen.buildNowPlayingCoverRequest
import moe.ouom.neriplayer.ui.screen.buildNowPlayingCoverCacheKey
import moe.ouom.neriplayer.ui.screen.resolveNowPlayingCoverOwnerKey
import moe.ouom.neriplayer.ui.screen.resolveNowPlayingCoverCacheKeys
import moe.ouom.neriplayer.ui.screen.retainNowPlayingCoverFrame
import moe.ouom.neriplayer.ui.screen.resolveNowPlayingVisibleCoverFrame
import moe.ouom.neriplayer.ui.screen.isNowPlayingCachedCoverFrameCompatible
import moe.ouom.neriplayer.ui.screen.isNowPlayingRetainedCoverFrameCompatible
import moe.ouom.neriplayer.ui.screen.shouldClearNowPlayingCoverFrame
import moe.ouom.neriplayer.ui.screen.shouldClearNowPlayingRetainedCoverAfterGrace
import moe.ouom.neriplayer.ui.screen.shouldAnimateNowPlayingCoverFrame
import moe.ouom.neriplayer.ui.screen.shouldKeepNowPlayingCoverVisible
import moe.ouom.neriplayer.ui.screen.shouldRetainNowPlayingCoverOnError
import moe.ouom.neriplayer.ui.screen.shouldCommitNowPlayingCoverRequest
import moe.ouom.neriplayer.ui.screen.shouldHandleNowPlayingCoverError
import moe.ouom.neriplayer.ui.screen.resolveNowPlayingCoverRequestUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriAppPlaybackTransitionPolicyTest {

    @Test
    fun `now playing cover owner follows the visible song over a stale parent prop`() {
        assertEquals(
            "song-b",
            resolveNowPlayingCoverOwnerKey(
                currentSongKey = "song-b",
                parentSongKey = "song-a"
            )
        )
        assertEquals(
            "song-a",
            resolveNowPlayingCoverOwnerKey(
                currentSongKey = null,
                parentSongKey = "song-a"
            )
        )
    }

    @Test
    fun `cover cache writes stay scoped to the completed request owner`() {
        assertEquals(
            listOf("song-a"),
            resolveNowPlayingCoverCacheKeys(
                requestSongKey = "song-a",
                latestRequestSongKey = "song-b",
                latestSongKeyAliases = listOf("song-b", "legacy-b")
            )
        )
        assertEquals(
            listOf("song-b", "legacy-b"),
            resolveNowPlayingCoverCacheKeys(
                requestSongKey = "song-b",
                latestRequestSongKey = "song-b",
                latestSongKeyAliases = listOf("song-b", "legacy-b")
            )
        )
    }

    @Test
    fun `cover cache key changes when local asset revision changes`() {
        val first = buildNowPlayingCoverCacheKey(
            coverUrl = "content://tree/Covers/song.jpg",
            downloadPresenceVersion = 1,
            assetRootGeneration = 3L,
            assetSongRevision = 0L
        )
        val refreshed = buildNowPlayingCoverCacheKey(
            coverUrl = "content://tree/Covers/song.jpg",
            downloadPresenceVersion = 1,
            assetRootGeneration = 3L,
            assetSongRevision = 1L
        )

        assertNotEquals(first, refreshed)
    }

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
    fun `visual cover is retained while active song cover is temporarily unresolved`() {
        assertFalse(
            shouldClearPlaybackVisualCover(
                currentSongKey = "local-song",
                requestedCoverUrl = null,
                clearDelayElapsed = true
            )
        )
        assertTrue(
            shouldClearPlaybackVisualCover(
                currentSongKey = null,
                requestedCoverUrl = null,
                clearDelayElapsed = true
            )
        )
    }

    @Test
    fun `retained visual cover is kept while an active song has no resolved cover`() {
        assertFalse(
            shouldClearRetainedPlaybackVisualCoverAfterGrace(
                currentSongKey = "local-song",
                retainedCoverUrl = "content://tree/Covers/old-song.jpg",
                requestedCoverUrl = null,
                clearDelayElapsed = false
            )
        )
        assertTrue(
            shouldClearRetainedPlaybackVisualCoverAfterGrace(
                currentSongKey = null,
                retainedCoverUrl = "content://tree/Covers/old-song.jpg",
                requestedCoverUrl = null,
                clearDelayElapsed = true
            )
        )
        assertFalse(
            shouldClearRetainedPlaybackVisualCoverAfterGrace(
                currentSongKey = "local-song",
                retainedCoverUrl = "content://tree/Covers/old-song.jpg",
                requestedCoverUrl = "content://tree/Covers/new-song.jpg",
                clearDelayElapsed = true
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
    fun `retained visual cover keeps its original song owner`() {
        val previous = PlaybackVisualCoverState(
            url = "old-cover",
            ownerSongKey = "song-a"
        )

        assertEquals(
            previous,
            resolvePlaybackVisualCoverState(
                currentCoverUrl = null,
                previousState = previous,
                currentSongKey = "song-b",
                hasCurrentSong = true
            )
        )
        assertEquals(
            PlaybackVisualCoverState("new-cover", "song-b"),
            resolvePlaybackVisualCoverState(
                currentCoverUrl = "new-cover",
                previousState = previous,
                currentSongKey = "song-b",
                hasCurrentSong = true
            )
        )
    }

    @Test
    fun `visual cover state clears owner when playback stops`() {
        assertEquals(
            PlaybackVisualCoverState(url = null, ownerSongKey = null),
            resolvePlaybackVisualCoverState(
                currentCoverUrl = null,
                previousState = PlaybackVisualCoverState("old-cover", "song-a"),
                currentSongKey = null,
                hasCurrentSong = false
            )
        )
    }

    @Test
    fun `invalidated cover reference keeps the last visible cover for the same song`() {
        assertEquals(
            PlaybackVisualCoverState(url = "stale-cover", ownerSongKey = "song-a"),
            resolvePlaybackVisualCoverState(
                currentCoverUrl = null,
                previousState = PlaybackVisualCoverState("stale-cover", "song-a"),
                currentSongKey = "song-a",
                hasCurrentSong = true
            )
        )
    }

    @Test
    fun `previous song visual cover is not used as the next song request`() {
        assertNull(
            resolveNowPlayingCoverRequestUrl(
                resolvedCoverUrl = null,
                visualCoverUrl = "song-a-cover",
                visualCoverSongKey = "song-a",
                currentSongKey = "song-b"
            )
        )
        assertEquals(
            "song-a-cover",
            resolveNowPlayingCoverRequestUrl(
                resolvedCoverUrl = null,
                visualCoverUrl = "song-a-cover",
                visualCoverSongKey = "song-a",
                currentSongKey = "song-a"
            )
        )
        assertNull(
            resolveNowPlayingCoverRequestUrl(
                resolvedCoverUrl = null,
                visualCoverUrl = "song-a-cover",
                visualCoverSongKey = null,
                currentSongKey = null
            )
        )
    }

    @Test
    fun `resolved cover from a previous song is ignored during a rapid switch`() {
        assertNull(
            resolveNowPlayingCoverRequestUrl(
                resolvedCoverUrl = "song-a-cover",
                resolvedCoverSongKey = "song-a",
                visualCoverUrl = null,
                visualCoverSongKey = null,
                currentSongKey = "song-b"
            )
        )
        assertEquals(
            "song-b-cover",
            resolveNowPlayingCoverRequestUrl(
                resolvedCoverUrl = "song-b-cover",
                resolvedCoverSongKey = "song-b",
                visualCoverUrl = "song-a-cover",
                visualCoverSongKey = "song-a",
                currentSongKey = "song-b"
            )
        )
    }

    @Test
    fun `unowned resolved cover is rejected when the current song is already known`() {
        assertNull(
            resolveNowPlayingCoverRequestUrl(
                resolvedCoverUrl = "stale-song-a-cover",
                visualCoverUrl = null,
                visualCoverSongKey = null,
                currentSongKey = "song-b",
                resolvedCoverOwnerRequired = true
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
    fun `now playing never clears a retained frame while a song is active`() {
        assertFalse(
            shouldClearNowPlayingCoverFrame(
                currentSongKey = "song-b",
                requestedCoverUrl = null,
                clearDelayElapsed = true
            )
        )
        assertTrue(
            shouldClearNowPlayingCoverFrame(
                currentSongKey = null,
                requestedCoverUrl = null,
                clearDelayElapsed = true
            )
        )
    }

    @Test
    fun `now playing retained frame is kept while an active song has no cover`() {
        assertFalse(
            shouldClearNowPlayingRetainedCoverAfterGrace(
                currentSongKey = "song-b",
                requestedCoverUrl = null,
                hasRetainedFrame = true,
                requestFailed = false,
                clearDelayElapsed = false
            )
        )
        assertFalse(
            shouldClearNowPlayingRetainedCoverAfterGrace(
                currentSongKey = "song-b",
                requestedCoverUrl = null,
                hasRetainedFrame = true,
                requestFailed = false,
                clearDelayElapsed = true
            )
        )
        assertFalse(
            shouldClearNowPlayingRetainedCoverAfterGrace(
                currentSongKey = "song-b",
                requestedCoverUrl = "content://covers/song-b.jpg",
                hasRetainedFrame = true,
                requestFailed = false,
                clearDelayElapsed = true
            )
        )
        assertTrue(
            shouldClearNowPlayingRetainedCoverAfterGrace(
                currentSongKey = null,
                requestedCoverUrl = "content://covers/missing.jpg",
                hasRetainedFrame = true,
                requestFailed = true,
                clearDelayElapsed = true
            )
        )
        assertTrue(
            shouldClearNowPlayingRetainedCoverAfterGrace(
                currentSongKey = null,
                requestedCoverUrl = null,
                hasRetainedFrame = true,
                requestFailed = false,
                clearDelayElapsed = true
            )
        )
        val retainedFrame = NowPlayingCoverFrame(
            coverUrl = "content://covers/song-a.jpg",
            cacheKey = "song-a-cache"
        )
        assertNull(
            resolveNowPlayingVisibleCoverFrame(
                displayedFrame = retainedFrame,
                requestedFrame = null,
                hasCurrentSong = true,
                clearRetainedFrame = true
            )
        )
    }

    @Test
    fun `now playing blur cover is retained while a song or request is active`() {
        assertTrue(
            shouldRetainNowPlayingBlurCover(
                stableCoverUrl = "content://covers/song-a.jpg",
                currentSongKey = "song-b",
                requestedCoverUrl = null
            )
        )
        assertTrue(
            shouldRetainNowPlayingBlurCover(
                stableCoverUrl = "content://covers/song-a.jpg",
                currentSongKey = null,
                requestedCoverUrl = "content://covers/song-b.jpg"
            )
        )
        assertFalse(
            shouldRetainNowPlayingBlurCover(
                stableCoverUrl = "content://covers/song-a.jpg",
                currentSongKey = null,
                requestedCoverUrl = null
            )
        )
    }

    @Test
    fun `now playing blur cover clears only after playback and request stop`() {
        assertFalse(
            shouldClearNowPlayingBlurCover(
                currentSongKey = "song-a",
                requestedCoverUrl = null,
                clearDelayElapsed = true
            )
        )
        assertFalse(
            shouldClearNowPlayingBlurCover(
                currentSongKey = null,
                requestedCoverUrl = "content://covers/song-a.jpg",
                clearDelayElapsed = true
            )
        )
        assertFalse(
            shouldClearNowPlayingBlurCover(
                currentSongKey = null,
                requestedCoverUrl = null,
                clearDelayElapsed = false
            )
        )
        assertTrue(
            shouldClearNowPlayingBlurCover(
                currentSongKey = null,
                requestedCoverUrl = "  ",
                clearDelayElapsed = true
            )
        )
    }

    @Test
    fun `now playing retains visible frame when a cover request fails`() {
        val displayedFrame = NowPlayingCoverFrame(
            coverUrl = "content://downloads/Covers/song-a.jpg",
            cacheKey = "song-a-cache"
        )

        assertTrue(
            shouldRetainNowPlayingCoverOnError(
                currentSongKey = "song-b",
                displayedFrame = displayedFrame
            )
        )
        assertTrue(
            shouldRetainNowPlayingCoverOnError(
                currentSongKey = null,
                displayedFrame = displayedFrame
            )
        )
        assertFalse(
            shouldRetainNowPlayingCoverOnError(
                currentSongKey = null,
                displayedFrame = null
            )
        )
    }

    @Test
    fun `now playing keeps a requested frame during the transient no-song emission`() {
        val requestedFrame = NowPlayingCoverFrame(
            coverUrl = "content://downloads/Covers/song.jpg",
            cacheKey = "song-cache"
        )

        assertTrue(
            shouldKeepNowPlayingCoverVisible(
                currentSongKey = null,
                displayedFrame = null,
                requestedFrame = requestedFrame
            )
        )
        assertFalse(
            shouldKeepNowPlayingCoverVisible(
                currentSongKey = null,
                displayedFrame = null,
                requestedFrame = null
            )
        )
    }

    @Test
    fun `now playing uses the requested frame when no decoded frame exists`() {
        val requestedFrame = NowPlayingCoverFrame(
            coverUrl = "content://downloads/Covers/song-a.jpg",
            cacheKey = "song-a-cache"
        )

        assertEquals(
            requestedFrame,
            resolveNowPlayingVisibleCoverFrame(
                displayedFrame = null,
                requestedFrame = requestedFrame,
                hasCurrentSong = true
            )
        )
        assertNull(
            resolveNowPlayingVisibleCoverFrame(
                displayedFrame = null,
                requestedFrame = requestedFrame,
                hasCurrentSong = false
            )
        )
    }

    @Test
    fun `now playing keeps the last decoded frame while a new local cover loads`() {
        val displayedFrame = NowPlayingCoverFrame(
            coverUrl = "content://downloads/Covers/song-a.jpg",
            cacheKey = "song-a-cache"
        )
        val requestedFrame = NowPlayingCoverFrame(
            coverUrl = "content://downloads/Covers/song-b.jpg",
            cacheKey = "song-b-cache"
        )

        assertEquals(
            displayedFrame,
            resolveNowPlayingVisibleCoverFrame(
                displayedFrame = displayedFrame,
                requestedFrame = requestedFrame,
                hasCurrentSong = true
            )
        )
    }

    @Test
    fun `now playing prefers the cached frame for the active song`() {
        val previousFrame = NowPlayingCoverFrame(
            coverUrl = "content://tree-a/Covers/song-a.jpg",
            cacheKey = "song-a-cache"
        )
        val cachedFrame = NowPlayingCoverFrame(
            coverUrl = "content://tree-b/Covers/song-b.jpg",
            cacheKey = "song-b-cache"
        )

        assertEquals(
            cachedFrame,
            resolveNowPlayingVisibleCoverFrame(
                displayedFrame = previousFrame,
                requestedFrame = cachedFrame,
                hasCurrentSong = true,
                cachedFrame = cachedFrame
            )
        )
    }

    @Test
    fun `failed current cover request falls back to the placeholder when no frame exists`() {
        val failedRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://tree-a/Covers/song-a.jpg",
            songKey = "song-a",
            coverCacheKey = "generation-1"
        )!!

        assertNull(
            resolveNowPlayingVisibleCoverFrame(
                displayedFrame = null,
                requestedFrame = failedRequest.frame,
                hasCurrentSong = true,
                failedRequest = failedRequest
            )
        )
    }

    @Test
    fun `failed current cover request keeps a decoded frame from another source`() {
        val previousFrame = NowPlayingCoverFrame(
            coverUrl = "content://tree-a/Covers/song-a-old.jpg",
            cacheKey = "generation-1"
        )
        val failedRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://tree-b/Covers/song-a.jpg",
            songKey = "song-a",
            coverCacheKey = "generation-2"
        )!!

        assertEquals(
            previousFrame,
            resolveNowPlayingVisibleCoverFrame(
                displayedFrame = previousFrame,
                requestedFrame = failedRequest.frame,
                hasCurrentSong = true,
                failedRequest = failedRequest
            )
        )
    }

    @Test
    fun `same cover url from another song is not evicted by a failed request`() {
        val previousFrame = NowPlayingCoverFrame(
            coverUrl = "content://tree/Covers/shared.jpg",
            cacheKey = "generation-1",
            ownerSongKey = "song-a"
        )
        val failedRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://tree/Covers/shared.jpg",
            songKey = "song-b",
            coverCacheKey = "generation-1"
        )!!

        assertEquals(
            previousFrame,
            resolveNowPlayingVisibleCoverFrame(
                displayedFrame = previousFrame,
                requestedFrame = failedRequest.frame,
                hasCurrentSong = true,
                failedRequest = failedRequest
            )
        )
    }

    @Test
    fun `same cover uri does not crossfade again after a cache generation change`() {
        val previousFrame = NowPlayingCoverFrame(
            coverUrl = "content://downloads/Covers/song.jpg",
            cacheKey = "generation-1"
        )
        val refreshedFrame = previousFrame.copy(cacheKey = "generation-2")

        assertFalse(shouldAnimateNowPlayingCoverFrame(previousFrame, refreshedFrame))
        assertTrue(
            shouldAnimateNowPlayingCoverFrame(
                previousFrame,
                refreshedFrame.copy(coverUrl = "content://downloads/Covers/other.jpg")
            )
        )
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
    fun `stale cover error cannot evict a frame after a rapid song switch`() {
        val staleRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://tree-a/Covers/song-a.jpg",
            songKey = "song-a",
            coverCacheKey = "generation-1"
        )!!
        val latestRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://tree-b/Covers/song-b.jpg",
            songKey = "song-b",
            coverCacheKey = "generation-2"
        )!!

        assertFalse(
            shouldHandleNowPlayingCoverError(
                failedRequest = staleRequest,
                latestRequest = latestRequest
            )
        )
        assertTrue(
            shouldHandleNowPlayingCoverError(
                failedRequest = latestRequest,
                latestRequest = latestRequest
            )
        )
        assertFalse(
            shouldHandleNowPlayingCoverError(
                failedRequest = staleRequest,
                latestRequest = null
            )
        )
    }

    @Test
    fun `reentering the same cover isolates old success and failure callbacks`() {
        val firstRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://tree/Covers/song.jpg",
            songKey = "local-song",
            coverCacheKey = "generation-1",
            requestToken = "request-a1"
        )!!
        val reenteredRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://tree/Covers/song.jpg",
            songKey = "local-song",
            coverCacheKey = "generation-1",
            requestToken = "request-a2"
        )!!

        assertFalse(shouldCommitNowPlayingCoverRequest(firstRequest, reenteredRequest))
        assertFalse(
            shouldHandleNowPlayingCoverError(
                failedRequest = firstRequest,
                latestRequest = reenteredRequest
            )
        )
        val displayedFrame = firstRequest.frame.copy(decodedBitmap = markerBitmap)
        assertEquals(
            displayedFrame,
            resolveNowPlayingVisibleCoverFrame(
                displayedFrame = displayedFrame,
                requestedFrame = reenteredRequest.frame,
                hasCurrentSong = true,
                failedRequest = reenteredRequest
            )
        )
    }

    @Test
    fun `same-source pending now playing frame adopts the latest request token`() {
        val firstRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://tree/Covers/song.jpg",
            songKey = "local-song",
            coverCacheKey = "generation-1",
            requestToken = "request-a1"
        )!!
        val reenteredRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://tree/Covers/song.jpg",
            songKey = "local-song",
            coverCacheKey = "generation-1",
            requestToken = "request-a2"
        )!!

        assertEquals(
            reenteredRequest.frame,
            resolveNowPlayingVisibleCoverFrame(
                displayedFrame = firstRequest.frame,
                requestedFrame = reenteredRequest.frame,
                hasCurrentSong = true
            )
        )
    }

    @Test
    fun `rapid local A B A switch keeps cached A while B is unresolved or fails`() {
        val songARequest = buildNowPlayingCoverRequest(
            coverUrl = "content://tree/Covers/song-a.jpg",
            songKey = "local-song-a",
            coverCacheKey = "generation-1"
        )!!
        val songBRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://tree/Covers/song-b.jpg",
            songKey = "local-song-b",
            coverCacheKey = "generation-1"
        )!!
        val visualStateDuringSongB = resolvePlaybackVisualCoverState(
            currentCoverUrl = null,
            previousState = PlaybackVisualCoverState(
                url = songARequest.frame.coverUrl,
                ownerSongKey = "local-song-a"
            ),
            currentSongKey = "local-song-b",
            hasCurrentSong = true
        )

        assertNull(
            resolveNowPlayingCoverRequestUrl(
                resolvedCoverUrl = null,
                visualCoverUrl = visualStateDuringSongB.url,
                visualCoverSongKey = visualStateDuringSongB.ownerSongKey,
                currentSongKey = "local-song-b"
            )
        )
        assertEquals(
            songARequest.frame,
            resolveNowPlayingVisibleCoverFrame(
                displayedFrame = songARequest.frame,
                requestedFrame = null,
                hasCurrentSong = true
            )
        )
        assertFalse(
            shouldHandleNowPlayingCoverError(
                failedRequest = songBRequest,
                latestRequest = songARequest
            )
        )
        assertEquals(
            songARequest.frame,
            resolveNowPlayingVisibleCoverFrame(
                displayedFrame = songARequest.frame,
                requestedFrame = songARequest.frame,
                cachedFrame = songARequest.frame,
                hasCurrentSong = true,
                failedRequest = songBRequest
            )
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
    fun `now playing cached frame must match the current asset generation`() {
        val cachedFrame = NowPlayingCoverFrame(
            coverUrl = "content://downloads/Covers/song.jpg",
            cacheKey = "generation-1",
            ownerSongKey = "local-song",
            decodedBitmap = markerBitmap
        )
        val refreshedRequest = buildNowPlayingCoverRequest(
            coverUrl = "content://downloads/Covers/song.jpg",
            songKey = "local-song",
            coverCacheKey = "generation-2"
        )!!

        assertFalse(
            isNowPlayingCachedCoverFrameCompatible(
                cachedFrame = cachedFrame,
                requestedFrame = refreshedRequest.frame
            )
        )
        assertTrue(
            isNowPlayingCachedCoverFrameCompatible(
                cachedFrame = cachedFrame,
                requestedFrame = cachedFrame.copy(requestToken = "new-request")
            )
        )
    }

    @Test
    fun `decoded now playing frame remains visible when the current request reports an error`() {
        val request = buildNowPlayingCoverRequest(
            coverUrl = "content://tree/Covers/song.jpg",
            songKey = "local-song",
            coverCacheKey = "generation-1"
        )!!
        val decodedFrame = request.frame.copy(decodedBitmap = markerBitmap)

        assertEquals(
            decodedFrame,
            resolveNowPlayingVisibleCoverFrame(
                displayedFrame = decodedFrame,
                requestedFrame = request.frame,
                hasCurrentSong = true,
                failedRequest = request
            )
        )
        assertTrue(
            isNowPlayingRetainedCoverFrameCompatible(
                cachedFrame = decodedFrame,
                requestedFrame = request.frame.copy(cacheKey = "generation-2")
            )
        )
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
    fun `cover seed stays stable only for the same song until rebound sample is ready`() {
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
        assertNull(
            resolveActiveCoverSeedHex(
                visualCoverUrl = "content://current/Covers/cover.jpg",
                sampledCoverUrl = "content://old/Covers/cover.jpg",
                sampledSeedHex = "778899",
                currentSongKey = "new-song",
                sampledSongKey = "old-song"
            )
        )
    }

    private companion object {
        val markerBitmap = object : ImageBitmap {
            override val width: Int = 1
            override val height: Int = 1
            override val colorSpace = ColorSpaces.Srgb
            override val hasAlpha: Boolean = true
            override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888

            override fun readPixels(
                buffer: IntArray,
                startX: Int,
                startY: Int,
                width: Int,
                height: Int,
                bufferOffset: Int,
                stride: Int
            ) = Unit

            override fun prepareToDraw() = Unit
        }
    }
}
