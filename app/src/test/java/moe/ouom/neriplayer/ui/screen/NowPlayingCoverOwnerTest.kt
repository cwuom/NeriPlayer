package moe.ouom.neriplayer.ui.screen

import android.content.res.Configuration
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingCoverOwnerTest {
    @Test
    fun `stale success and error cannot publish after switching songs`() = runTest {
        val owner = NowPlayingCoverOwner(this)
        val first = owner.newRequest("content://cover-owner-test/first", "cover-owner-test-first", "g1")!!
        owner.onPresented("cover-owner-test-first", owner.presentation(first, "cover-owner-test-first", emptyList()))
        owner.rejectDecodedFrame(first.frame, first)
        assertEquals(first, owner.currentFailedRequest)
        val second = owner.newRequest("content://cover-owner-test/second", "cover-owner-test-second", "g2")!!
        assertNull(owner.currentFailedRequest)
        owner.onPresented("cover-owner-test-second", owner.presentation(second, "cover-owner-test-second", listOf("cover-owner-test-alias")))

        owner.publishDecodedFrame(first, first.frame.copy(decodedBitmap = markerBitmap))
        owner.rejectDecodedFrame(first.frame, first)
        assertNull(owner.displayedFrame)
        assertNull(owner.currentFailedRequest)

        val decoded = second.frame.copy(decodedBitmap = markerBitmap)
        owner.publishDecodedFrame(second, decoded)
        owner.rejectDecodedFrame(second.frame, second)
        assertEquals(decoded, owner.displayedFrame)
        assertNull(owner.currentFailedRequest)
        assertEquals(setOf("cover-owner-test-second", "cover-owner-test-alias"), owner.cachedSongKeys)
        owner.close()
    }

    @Test
    fun `same cover reentry gets a new generation and ignores old callbacks`() = runTest {
        val owner = NowPlayingCoverOwner(this)
        val first = owner.newRequest("content://cover-owner-test/reentry", "cover-owner-test-reentry", "g1")!!
        owner.onPresented("cover-owner-test-reentry", owner.presentation(first, "cover-owner-test-reentry", emptyList()))
        val decoded = first.frame.copy(decodedBitmap = markerBitmap)
        owner.publishDecodedFrame(first, decoded)

        val reentered = owner.newRequest("content://cover-owner-test/reentry", "cover-owner-test-reentry", "g1")!!
        assertNotEquals(first.requestToken, reentered.requestToken)
        owner.rejectDecodedFrame(first.frame, first)
        owner.publishDecodedFrame(first, first.frame.copy(cacheKey = "stale"))
        assertEquals(decoded, owner.displayedFrame)
        assertNull(owner.currentFailedRequest)
        owner.close()
    }

    @Test
    fun `retained frame clears after grace and a new request cancels the clear`() = runTest {
        val owner = NowPlayingCoverOwner(this, nullGraceMs = 500L)
        val first = owner.newRequest("content://cover-owner-test/grace", "cover-owner-test-grace", "g1")!!
        owner.onPresented("cover-owner-test-grace", owner.presentation(first, "cover-owner-test-grace", emptyList()))
        owner.publishDecodedFrame(first, first.frame.copy(decodedBitmap = markerBitmap))

        owner.newRequest(null, null, null)
        owner.onPresented(null, owner.presentation(null, null, emptyList()))
        advanceTimeBy(400L)
        runCurrent()
        assertEquals(first.frame.coverUrl, owner.presentation(null, null, emptyList()).visibleFrame?.coverUrl)

        val next = owner.newRequest("content://cover-owner-test/next", "cover-owner-test-next", "g2")!!
        owner.onPresented("cover-owner-test-next", owner.presentation(next, "cover-owner-test-next", emptyList()))
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(first.frame.coverUrl, owner.displayedFrame?.coverUrl)

        owner.newRequest(null, null, null)
        owner.onPresented(null, owner.presentation(null, null, emptyList()))
        advanceTimeBy(500L)
        runCurrent()
        assertNull(owner.presentation(null, null, emptyList()).visibleFrame)
        owner.close()
    }

    @Test
    fun `preview belongs to its playback session and closes before another song`() = runTest {
        val owner = NowPlayingCoverOwner(this)
        assertFalse(owner.openPreview("song-a", " "))
        assertFalse(owner.previewOpenFor("song-a"))
        assertTrue(owner.openPreview("song-a", "content://cover-owner-test/preview"))
        assertTrue(owner.previewOpenFor("song-a"))
        assertFalse(owner.previewOpenFor("song-b"))
        assertNull(owner.visiblePreviewUrl("song-a", null))
        assertNull(owner.visiblePreviewUrl("song-a", " "))
        assertEquals(
            "content://cover-owner-test/preview",
            owner.visiblePreviewUrl("song-a", "content://cover-owner-test/preview")
        )
        owner.onPreviewSessionChanged("song-a")
        assertTrue(owner.previewOpenFor("song-a"))
        owner.onPreviewSessionChanged("song-b")
        assertFalse(owner.previewOpenFor("song-a"))
        owner.onPreviewSessionChanged("song-a")
        assertNull(owner.visiblePreviewUrl("song-a", "content://cover-owner-test/preview"))

        assertTrue(owner.openPreview("song-a", "content://cover-owner-test/preview"))
        var downloadSawClosedPreview = false
        owner.downloadPreviewAction {
            downloadSawClosedPreview = !owner.previewOpenFor("song-a")
        }()
        assertTrue(downloadSawClosedPreview)

        assertTrue(owner.openPreview("song-a", "content://cover-owner-test/preview"))
        owner.closePreview()
        assertFalse(owner.previewOpenFor("song-a"))
        assertNull(owner.visiblePreviewUrl("song-a", "content://cover-owner-test/preview"))
        owner.close()
    }

    @Test
    fun `preview gesture policy keeps local tap inert but allows long press`() = runTest {
        val owner = NowPlayingCoverOwner(this)
        val localSong = previewSong("__local_files__")
        val remoteSong = previewSong("Remote")
        assertEquals(
            NowPlayingCoverPreviewAttempt.IGNORED,
            owner.tryOpenPreview(null, false, "none", "content://cover-owner-test/preview")
        )
        assertEquals(
            NowPlayingCoverPreviewAttempt.IGNORED,
            owner.tryOpenPreview(localSong, false, "local", "content://cover-owner-test/preview")
        )
        assertEquals(
            NowPlayingCoverPreviewAttempt.UNAVAILABLE,
            owner.tryOpenPreview(remoteSong, false, "remote", null)
        )
        assertEquals(
            NowPlayingCoverPreviewAttempt.OPENED,
            owner.tryOpenPreview(localSong, true, "local", "content://cover-owner-test/preview")
        )
        assertEquals("content://cover-owner-test/preview", owner.visiblePreviewUrl("local", "content://cover-owner-test/preview"))
        assertNull(owner.visiblePreviewUrl("remote", "content://cover-owner-test/preview"))
        owner.close()
    }

    @Test
    fun `preview unavailable callback is emitted only for a permitted missing cover`() = runTest {
        val owner = NowPlayingCoverOwner(this)
        var unavailableCalls = 0
        val onUnavailable: () -> Unit = { unavailableCalls++ }
        owner.openPreviewOrNotify(previewSong("__local_files__"), false, "local", null, onUnavailable)
        owner.openPreviewOrNotify(previewSong("Remote"), false, "remote", null, onUnavailable)
        owner.openPreviewOrNotify(previewSong("Remote"), false, "remote", "content://cover-owner-test/preview", onUnavailable)
        assertEquals(1, unavailableCalls)
        owner.close()
    }

    @Test
    fun `composed request reuses its token until the cover input changes`() = runTest {
        val owner = NowPlayingCoverOwner(this)
        val firstInput = NowPlayingCoverRequestInput("content://cover-owner-test/first", "song", "g1")
        val first = owner.requestFor(firstInput)!!
        assertEquals(first, owner.requestFor(firstInput.copy()))
        val changed = owner.requestFor(firstInput.copy(cacheKey = "g2"))!!
        assertNotEquals(first.requestToken, changed.requestToken)
        assertNull(owner.requestFor(NowPlayingCoverRequestInput(null, null, null)))
        assertNotEquals(first.requestToken, owner.requestFor(firstInput)!!.requestToken)
        owner.close()
    }

    @Test
    fun `cover size keeps portrait and landscape bounds`() {
        assertEquals(180.dp, resolveNowPlayingCoverSize(false, false, 400.dp, 300.dp, 300.dp))
        assertEquals(200.dp, resolveNowPlayingCoverSize(false, true, 600.dp, 300.dp, 400.dp))
        assertEquals(168.dp, resolveNowPlayingCoverSize(true, true, 800.dp, 300.dp, 400.dp))
    }

    @Test
    fun `cover viewport enters wide mode only in wide landscape`() {
        assertFalse(resolveNowPlayingCoverViewport(800.dp, Configuration.ORIENTATION_PORTRAIT).wideLandscape)
        assertFalse(resolveNowPlayingCoverViewport(479.dp, Configuration.ORIENTATION_LANDSCAPE).wideLandscape)
        assertTrue(resolveNowPlayingCoverViewport(480.dp, Configuration.ORIENTATION_LANDSCAPE).wideLandscape)
    }

    @Test
    fun `source badge appears only with an enabled known source`() {
        assertEquals(0f, nowPlayingCoverBadgeScaleTarget(false, PlaybackSourceType.LOCAL))
        assertEquals(0f, nowPlayingCoverBadgeScaleTarget(true, null))
        assertEquals(1f, nowPlayingCoverBadgeScaleTarget(true, PlaybackSourceType.LOCAL))
    }

    @Test
    fun `cover source retains cache revision aliases and edited song name`() {
        val song = previewSong("Remote").copy(customName = "Edited preview")
        val source = buildNowPlayingCoverSource(
            "content://cover-owner-test/preview", "song", listOf("alias"),
            2, 3L, 4L, song
        )
        assertEquals("Edited preview", source.contentDescription)
        assertEquals(listOf("alias"), source.songKeyAliases)
        assertEquals(
            buildNowPlayingCoverCacheKey(source.coverUrl, 2, 3L, 4L),
            source.cacheKey
        )
        assertEquals("", buildNowPlayingCoverSource(null, null, emptyList(), 0, 0L, 0L, null).contentDescription)
    }

    @Test
    fun `preload is required only when the visible frame has a different source`() {
        val request = buildNowPlayingCoverRequest("content://cover-owner-test/new", "song", "g2")!!
        assertTrue(shouldPreloadNowPlayingCover(request, null, null))
        assertTrue(shouldPreloadNowPlayingCover(request, null, request.frame.copy(cacheKey = "old")))
        assertFalse(shouldPreloadNowPlayingCover(request, null, request.frame))
        assertFalse(shouldPreloadNowPlayingCover(request, request, null))
        assertFalse(shouldPreloadNowPlayingCover(null, null, null))
    }

    @Test
    fun `cache aliases reject missing request owners and normalize only current aliases`() {
        assertEquals(emptyList<String>(), resolveNowPlayingCoverCacheKeys(null, null, listOf("alias")))
        assertEquals(emptyList<String>(), resolveNowPlayingCoverCacheKeys("  ", "  ", listOf("alias")))
        assertEquals(
            listOf("song"),
            resolveNowPlayingCoverCacheKeys(" song ", "other", listOf("alias"))
        )
        assertEquals(
            listOf("song", "alias"),
            resolveNowPlayingCoverCacheKeys("song", "song", listOf("  ", " alias ", "alias"))
        )
    }

    @Test
    fun `request frame identity ignores bitmap but isolates owner cache key and token`() {
        val first = buildNowPlayingCoverRequest("content://cover-owner-test/identity", "song", "g1", "first")!!
        assertTrue(sameNowPlayingCoverRequestFrame(null, null))
        assertFalse(sameNowPlayingCoverRequestFrame(first.frame, null))
        assertFalse(sameNowPlayingCoverRequestFrame(null, first.frame))
        assertTrue(sameNowPlayingCoverRequestFrame(first.frame, first.frame.copy(decodedBitmap = markerBitmap)))
        assertFalse(sameNowPlayingCoverRequestFrame(first.frame, first.frame.copy(cacheKey = "g2")))
        assertFalse(sameNowPlayingCoverRequestFrame(first.frame, first.frame.copy(ownerSongKey = "other")))
        assertFalse(sameNowPlayingCoverRequestFrame(first.frame, first.frame.copy(requestToken = "second")))
    }

    @Test
    fun `failed pending frame is removed without evicting another decoded song`() = runTest {
        val owner = NowPlayingCoverOwner(this)
        val decodedRequest = owner.newRequest("content://cover-owner-test/decoded", "decoded-song", "g1")!!
        owner.presentation(decodedRequest, "decoded-song", emptyList())
        owner.publishDecodedFrame(decodedRequest, decodedRequest.frame.copy(decodedBitmap = markerBitmap))
        val pendingRequest = owner.newRequest("content://cover-owner-test/pending", "pending-song", "g2")!!
        owner.presentation(pendingRequest, "pending-song", emptyList())
        owner.publishDecodedFrame(pendingRequest, pendingRequest.frame)
        assertEquals(setOf("decoded-song", "pending-song"), owner.cachedSongKeys)
        owner.rejectDecodedFrame(pendingRequest.frame, pendingRequest)
        assertEquals(setOf("decoded-song"), owner.cachedSongKeys)
        assertEquals(pendingRequest, owner.currentFailedRequest)
        owner.close()
    }

    @Test
    fun `retained and decoded cache compatibility require the right frame owner`() {
        val request = buildNowPlayingCoverRequest("content://cover-owner-test/compatible", "song", "g2")!!
        val decoded = request.frame.copy(decodedBitmap = markerBitmap)
        assertFalse(isNowPlayingCachedCoverFrameCompatible(null, request.frame))
        assertFalse(isNowPlayingCachedCoverFrameCompatible(request.frame, request.frame))
        assertTrue(isNowPlayingCachedCoverFrameCompatible(decoded, null))
        assertTrue(isNowPlayingCachedCoverFrameCompatible(decoded, request.frame))
        assertFalse(isNowPlayingCachedCoverFrameCompatible(decoded.copy(cacheKey = "old"), request.frame))
        assertFalse(isNowPlayingRetainedCoverFrameCompatible(null, request.frame))
        assertFalse(isNowPlayingRetainedCoverFrameCompatible(request.frame, request.frame))
        assertTrue(isNowPlayingRetainedCoverFrameCompatible(decoded, null))
        assertTrue(isNowPlayingRetainedCoverFrameCompatible(decoded.copy(cacheKey = "old"), request.frame))
        assertFalse(isNowPlayingRetainedCoverFrameCompatible(decoded.copy(ownerSongKey = "other"), request.frame))
        assertFalse(isNowPlayingRetainedCoverFrameCompatible(decoded.copy(coverUrl = "other"), request.frame))
    }

    private companion object {
        fun previewSong(album: String) = SongItem(
            id = 7L,
            name = "Preview",
            artist = "Artist",
            album = album,
            albumId = 0L,
            durationMs = 5_000L,
            coverUrl = "content://cover-owner-test/preview"
        )

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
