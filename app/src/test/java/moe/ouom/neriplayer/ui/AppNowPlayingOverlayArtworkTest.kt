package moe.ouom.neriplayer.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.playback.visual.resolveNowPlayingOverlayArtwork
import moe.ouom.neriplayer.util.media.RetainedPlaybackCoverBitmapCache
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
}
