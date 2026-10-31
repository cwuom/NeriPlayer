package moe.ouom.neriplayer.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.identity.playbackVisualKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingBlurCoverRequest
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingBlurEffectOwner
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingBlurPreloadRequest
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingBlurRetentionRequest
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingBlurState
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingCoverAssetRefresh
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayBackground
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayCover
import moe.ouom.neriplayer.ui.playback.visual.PLAYBACK_VISUAL_COVER_GRACE_MS
import moe.ouom.neriplayer.ui.playback.visual.nowPlayingBackdropFrame
import moe.ouom.neriplayer.ui.playback.visual.nowPlayingBlurImageCacheKey
import moe.ouom.neriplayer.ui.playback.visual.nowPlayingBlurNeighborCover
import moe.ouom.neriplayer.ui.playback.visual.nowPlayingBlurPreloadRequest
import moe.ouom.neriplayer.ui.util.hasKnownEmptyLocalCover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingBlurEffectOwnerTest {
    @Test
    fun `neighbor preload cache identity matches its eventual visible request after refresh`() = runTest {
        val currentUrl = "https://example.com/current"
        val nextUrl = "https://example.com/next"
        val background = NowPlayingOverlayBackground(true, 4f, 0f, false, false)
        for (revision in listOf(7, 8)) {
            val current = nowPlayingBackdropFrame(
                NowPlayingOverlayCover(currentUrl, "current-song", null, revision),
                background, NowPlayingBlurState(), true
            )
            val next = nowPlayingBackdropFrame(
                NowPlayingOverlayCover(nextUrl, "next-song", null, revision),
                background, NowPlayingBlurState(), true
            )
            val request = nowPlayingBlurPreloadRequest(current, listOf(next.cover))
            val prefetchedKeys = mutableListOf<String>()
            val owner = NowPlayingBlurEffectOwner(this)
            owner.updatePreload(request) { cover ->
                prefetchedKeys += nowPlayingBlurImageCacheKey(
                    cover.coverUrl, cover.blurStrength, cover.assetVersion
                )
            }

            assertEquals(
                listOf("nowplaying-blur:$nextUrl:4.0:$revision:next-song:$nextUrl"),
                prefetchedKeys
            )
            assertEquals(
                listOf(nowPlayingBlurImageCacheKey(nextUrl, next.blurStrength, next.assetVersion)),
                prefetchedKeys
            )
        }
    }

    @Test
    fun `local neighbor preload uses the eventual local decode size`() {
        val background = NowPlayingOverlayBackground(true, 128f, 0f, false, false)
        val current = nowPlayingBackdropFrame(
            NowPlayingOverlayCover("https://example.com/current", "current-song", null, 7),
            background, NowPlayingBlurState(), true
        )
        val next = nowPlayingBackdropFrame(
            NowPlayingOverlayCover("content://media/next", "next-song", null, 7),
            background, NowPlayingBlurState(), true
        )
        val request = nowPlayingBlurPreloadRequest(current, listOf(next.cover))

        assertEquals(384, request.covers.single().imageSizePx)
        assertEquals(next.imageSizePx, request.covers.single().imageSizePx)
    }

    @Test
    fun `local neighbor preload uses the eventual local blur strength`() {
        val background = NowPlayingOverlayBackground(true, 128f, 0f, false, false)
        val current = nowPlayingBackdropFrame(
            NowPlayingOverlayCover("https://example.com/current", "current-song", null, 7),
            background, NowPlayingBlurState(), true
        )
        val next = nowPlayingBackdropFrame(
            NowPlayingOverlayCover("content://media/next", "next-song", null, 7),
            background, NowPlayingBlurState(), true
        )
        val request = nowPlayingBlurPreloadRequest(current, listOf(next.cover))

        assertEquals(64f, request.covers.single().blurStrength)
        assertEquals(next.blurStrength, request.covers.single().blurStrength)
    }

    @Test
    fun `neighbor revision stays independent of the current song and global refresh`() {
        val neighbor = SongItem(2, "Next", "Artist", "Album", 1, 1_000, "https://example.com/next")
        val background = NowPlayingOverlayBackground(true, 4f, 0f, false, false)
        val refresh = NowPlayingCoverAssetRefresh(3, 5)
        val currentCover = NowPlayingOverlayCover(
            "https://example.com/current", "current-song", null, 3_045, refresh
        )
        val neighborCover = nowPlayingBlurNeighborCover(currentCover, neighbor, neighbor.coverUrl, 11)
        val currentFrame = nowPlayingBackdropFrame(
            currentCover, background, NowPlayingBlurState(), true
        )
        val request = nowPlayingBlurPreloadRequest(currentFrame, listOf(neighborCover))
        val visibleNeighbor = nowPlayingBackdropFrame(
            NowPlayingOverlayCover(neighbor.coverUrl, neighbor.playbackVisualKey(), neighbor, 3_049, refresh),
            background, NowPlayingBlurState(), true
        )

        assertEquals(3_049, neighborCover.assetRefreshKey)
        assertEquals(
            "3049:${neighborCover.songKey}:https://example.com/next",
            request.covers.single().assetVersion
        )
        assertEquals(visibleNeighbor.assetVersion, request.covers.single().assetVersion)
        val changedCurrentCover = currentCover.copy(assetRefreshKey = 3_050)
        assertEquals(
            request,
            nowPlayingBlurPreloadRequest(
                nowPlayingBackdropFrame(changedCurrentCover, background, NowPlayingBlurState(), true),
                listOf(nowPlayingBlurNeighborCover(changedCurrentCover, neighbor, neighbor.coverUrl, 11))
            )
        )
        assertNotEquals(
            request,
            nowPlayingBlurPreloadRequest(
                currentFrame,
                listOf(nowPlayingBlurNeighborCover(currentCover, neighbor, neighbor.coverUrl, 12))
            )
        )
        for (changedRefresh in listOf(refresh.copy(foregroundToken = 4), refresh.copy(rootGeneration = 6))) {
            val changedCover = currentCover.copy(assetRefresh = changedRefresh)
            assertNotEquals(
                request,
                nowPlayingBlurPreloadRequest(
                    currentFrame,
                    listOf(nowPlayingBlurNeighborCover(changedCover, neighbor, neighbor.coverUrl, 11))
                )
            )
        }
    }

    @Test
    fun `neighbors sharing a URL keep separate song cache identities`() {
        val url = "https://example.com/shared"
        val first = NowPlayingOverlayCover(url, "first-song", null, 7)
        val second = NowPlayingOverlayCover(url, "second-song", null, 7)
        val frame = nowPlayingBackdropFrame(
            first, NowPlayingOverlayBackground(true, 4f, 0f, false, false),
            NowPlayingBlurState(), true
        )
        val request = nowPlayingBlurPreloadRequest(frame, listOf(first, second, first))

        assertEquals(2, request.covers.size)
        assertNotEquals(request.covers[0].assetVersion, request.covers[1].assetVersion)
    }

    @Test
    fun `preload enqueues once per distinct blur request`() = runTest {
        val owner = NowPlayingBlurEffectOwner(this)
        val queued = mutableListOf<NowPlayingBlurCoverRequest>()
        val covers = listOf(
            NowPlayingBlurCoverRequest("previous", 4f, 640, "previous-revision"),
            NowPlayingBlurCoverRequest("next", 4f, 640, "next-revision")
        )
        val request = NowPlayingBlurPreloadRequest(
            true, covers, false
        )
        owner.updatePreload(request, queued::add)
        owner.updatePreload(request, queued::add)
        assertEquals(covers, queued)
        val changedCovers = covers.map { it.copy(blurStrength = 8f) }
        owner.updatePreload(request.copy(covers = changedCovers), queued::add)
        assertEquals(covers + changedCovers, queued)
    }

    @Test
    fun `retention cancels stale grace when a replacement song arrives`() = runTest {
        val owner = NowPlayingBlurEffectOwner(this)
        val state = owner.blurState
        state.onImageSuccess("key", "key", "retained", 4f)
        val missing = NowPlayingBlurRetentionRequest(true, true, null, null)
        owner.updateRetention(missing)
        runCurrent()
        owner.updateRetention(NowPlayingBlurRetentionRequest(true, true, "new", null))
        runCurrent()
        advanceTimeBy(PLAYBACK_VISUAL_COVER_GRACE_MS)
        runCurrent()
        assertEquals("retained", state.stableCoverUrl)
    }

    @Test
    fun `retention preference change clears cover`() = runTest {
        val owner = NowPlayingBlurEffectOwner(this)
        val state = owner.blurState
        state.onImageSuccess("key", "key", "retained", 4f)
        owner.updateRetention(NowPlayingBlurRetentionRequest(true, false, "song", "cover"))
        runCurrent()
        assertNull(state.stableCoverUrl)
    }

    @Test
    fun `restoring no artwork clears the backdrop owner and rejects its late image result`() = runTest {
        val song = SongItem(
            id = 74_126L,
            name = "Restored backdrop",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 60_000L,
            coverUrl = null,
            mediaUri = "content://backdrop-restore/audio/74126",
            originalCoverUrl = "",
            channelId = "local",
            audioId = "74126"
        )
        val songKey = song.playbackVisualKey()
        val editedCover = "file:///backdrop-restore/edited.jpg"
        val owner = NowPlayingBlurEffectOwner(this)
        val state = owner.blurState
        state.onImageSuccess("edited-request", "edited-request", editedCover, 4f)
        owner.updateRetention(NowPlayingBlurRetentionRequest(true, true, songKey, null))
        runCurrent()
        assertEquals("a transient missing URL keeps the backdrop", editedCover, state.stableCoverUrl)

        owner.updateRetention(
            NowPlayingBlurRetentionRequest(true, true, songKey, null, song.hasKnownEmptyLocalCover())
        )
        runCurrent()

        assertNull("a restored empty cover clears the stable backdrop", state.stableCoverUrl)
        assertNull(state.stableBlurStrength)
        state.onImageSuccess("edited-request", "edited-request", editedCover, 4f)
        assertNull("a late image callback cannot restore the old backdrop", state.stableCoverUrl)
        val frame = nowPlayingBackdropFrame(
            NowPlayingOverlayCover(null, songKey, song, 0),
            NowPlayingOverlayBackground(true, 4f, 0f, false, true), state, true
        )
        assertFalse("the real backdrop frame must stop drawing retained blur", frame.hasCoverBlur)
    }

    @Test
    fun `forgetting backdrop cancels pending cover clear`() = runTest {
        val owner = NowPlayingBlurEffectOwner(backgroundScope)
        val state = owner.blurState
        state.onImageSuccess("key", "key", "retained", 4f)
        owner.updateRetention(NowPlayingBlurRetentionRequest(true, true, null, null))
        runCurrent()
        owner.onForgotten()
        advanceTimeBy(PLAYBACK_VISUAL_COVER_GRACE_MS)
        runCurrent()
        assertEquals("retained", state.stableCoverUrl)
    }
}
