package moe.ouom.neriplayer.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.playback.visual.resolveNowPlayingOverlayArtwork
import moe.ouom.neriplayer.util.media.RetainedPlaybackCoverBitmapCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppNowPlayingOverlayArtworkTest {
    @Test
    fun currentSongArtworkTakesPrecedenceOverTheMiniPlayerHandoff() {
        val retained = ImageBitmap(2, 2)
        val mini = ImageBitmap(2, 2)
        RetainedPlaybackCoverBitmapCache.put("overlay-artwork-current", "content://overlay/current", null, retained)
        val flight = SharedCoverFlightState().apply {
            reportSource(Rect(0f, 0f, 48f, 48f), 8f, mini)
        }

        assertSame(retained, resolveNowPlayingOverlayArtwork("overlay-artwork-current", flight))
        assertSame(retained, resolveNowPlayingOverlayArtwork("overlay-artwork-current", null))
    }

    @Test
    fun missingCurrentSongArtworkUsesTheAvailableMiniPlayerHandoff() {
        val mini = ImageBitmap(2, 2)
        val flight = SharedCoverFlightState().apply {
            reportSource(Rect(0f, 0f, 48f, 48f), 8f, mini)
        }

        assertSame(mini, resolveNowPlayingOverlayArtwork("overlay-artwork-not-cached", flight))
        assertSame(mini, resolveNowPlayingOverlayArtwork(null, flight))
    }

    @Test
    fun absentArtworkKeepsTheMorphOnItsVisibleCoverFallback() {
        assertNull(resolveNowPlayingOverlayArtwork("overlay-artwork-empty", SharedCoverFlightState()))
        assertNull(resolveNowPlayingOverlayArtwork(null, null))
    }

    @Test
    fun explicitlyClearedArtworkDoesNotReuseSharedCacheOrMiniHandoff() {
        val songKey = "overlay-artwork-restored-empty"
        val retained = ImageBitmap(2, 2)
        val mini = ImageBitmap(2, 2)
        RetainedPlaybackCoverBitmapCache.put(songKey, "content://overlay/edited", null, retained)
        val flight = SharedCoverFlightState().apply {
            reportSource(Rect(0f, 0f, 48f, 48f), 8f, mini)
        }

        assertSame(retained, resolveNowPlayingOverlayArtwork(songKey, flight))
        assertNull(resolveNowPlayingOverlayArtwork(songKey, flight, clearCover = true))
        assertNull(resolveNowPlayingOverlayArtwork("overlay-artwork-not-cached", flight, clearCover = true))
        flight.beginLinked(resolveNowPlayingOverlayArtwork(songKey, flight, clearCover = true))
        assertNull("the real morph must not fall back to the stale mini source", flight.linkedBitmap)
    }

    @Test
    fun clearingArtworkDuringALinkedMorphRemovesItsStartedBitmapAndPreservesGeometry() {
        val bitmap = ImageBitmap(2, 2)
        val sourceBounds = Rect(12f, 720f, 60f, 768f)
        val containerBounds = Rect(0f, 700f, 400f, 780f)
        val flight = SharedCoverFlightState().apply {
            reportSource(sourceBounds, 8f, bitmap)
            reportMiniPlayerContainer(containerBounds)
            reportTarget(Rect(20f, 100f, 380f, 460f), 24f)
            beginLinked(bitmap)
        }
        assertTrue(flight.linked)
        assertSame(bitmap, flight.linkedBitmap)
        val frameBeforeClear = flight.currentFrame()

        assertNull(resolveNowPlayingOverlayArtwork("overlay-cleared-active-linked", flight, clearCover = true))

        assertEquals(sourceBounds, flight.miniCoverBounds)
        assertEquals(containerBounds, flight.miniPlayerContainerBounds)
        assertEquals(frameBeforeClear, flight.currentFrame())
        assertTrue("clearing artwork must preserve the linked morph owner", flight.linked)
        assertNull(flight.miniCoverBitmap)
        assertNull("an active linked morph must stop drawing the removed cover", flight.linkedBitmap)
        assertFalse(flight.hidesCovers)
    }

    @Test
    fun clearingArtworkDuringAFlightRemovesItsStartedBitmapAndPreservesGeometry() = runTest {
        val bitmap = ImageBitmap(2, 2)
        val sourceBounds = Rect(12f, 720f, 60f, 768f)
        val containerBounds = Rect(0f, 700f, 400f, 780f)
        val flight = SharedCoverFlightState().apply {
            reportSource(sourceBounds, 8f, bitmap)
            reportMiniPlayerContainer(containerBounds)
            reportTarget(Rect(20f, 100f, 380f, 460f), 24f)
        }
        try {
            flight.open(backgroundScope, bitmap)
            assertTrue(flight.flying)
            assertTrue(flight.hidesCovers)
            assertSame(bitmap, flight.flightBitmap)
            val frameBeforeClear = flight.currentFrame()

            assertNull(resolveNowPlayingOverlayArtwork("overlay-cleared-active-flight", flight, clearCover = true))

            assertEquals(sourceBounds, flight.miniCoverBounds)
            assertEquals(containerBounds, flight.miniPlayerContainerBounds)
            assertEquals(frameBeforeClear, flight.currentFrame())
            assertTrue("clearing artwork must preserve the active flight owner", flight.flying)
            assertNull(flight.miniCoverBitmap)
            assertNull("an active flight must stop drawing the removed cover", flight.flightBitmap)
            assertFalse("an empty flight must not hide the no-artwork placeholder", flight.hidesCovers)
        } finally {
            flight.cancel()
        }
    }
}
