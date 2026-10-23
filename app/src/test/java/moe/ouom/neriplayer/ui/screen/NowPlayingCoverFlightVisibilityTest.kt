package moe.ouom.neriplayer.ui.screen

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.nowPlayingCoverArtworkAlpha
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingCoverFlightVisibilityTest {
    @Test
    fun realArtworkStaysVisibleWithoutADrawableSharedFlight() {
        assertEquals(1f, nowPlayingCoverArtworkAlpha(null), 0f)
        val flight = SharedCoverFlightState()
        assertEquals(1f, nowPlayingCoverArtworkAlpha(flight), 0f)
        flight.beginLinked()
        assertEquals(1f, nowPlayingCoverArtworkAlpha(flight), 0f)
        flight.reportSource(Rect(0f, 0f, 48f, 48f), 8f, ImageBitmap(2, 2))
        assertEquals(1f, nowPlayingCoverArtworkAlpha(flight), 0f)
    }

    @Test
    fun realArtworkRemainsHiddenUntilTheLinkedFlightEnds() {
        val flight = SharedCoverFlightState()
        flight.reportSource(Rect(0f, 0f, 48f, 48f), 8f, ImageBitmap(2, 2))
        flight.targetCoordinates = mock(LayoutCoordinates::class.java).also {
            `when`(it.isAttached).thenReturn(true)
        }
        flight.beginLinked()
        assertEquals(0f, nowPlayingCoverArtworkAlpha(flight), 0f)

        flight.endLinked()
        assertEquals(1f, nowPlayingCoverArtworkAlpha(flight), 0f)
        flight.beginLinked()
        flight.reportSource(Rect(0f, 0f, 48f, 48f), 8f, null)
        assertEquals(0f, nowPlayingCoverArtworkAlpha(flight), 0f)

        flight.endLinked()
        flight.beginLinked()
        assertEquals(1f, nowPlayingCoverArtworkAlpha(flight), 0f)
    }

    @Test
    fun lateArtworkIsOnlyUsedByTheNextLinkedFlight() {
        val flight = SharedCoverFlightState()
        val bounds = Rect(0f, 0f, 48f, 48f)
        flight.reportSource(bounds, 8f, null)
        flight.targetCoordinates = mock(LayoutCoordinates::class.java).also {
            `when`(it.isAttached).thenReturn(true)
        }
        flight.beginLinked()

        flight.reportSource(bounds, 8f, ImageBitmap(2, 2))

        assertEquals(1f, nowPlayingCoverArtworkAlpha(flight), 0f)
        flight.beginLinked()
        assertEquals(1f, nowPlayingCoverArtworkAlpha(flight), 0f)

        flight.endLinked()
        flight.beginLinked()
        assertEquals(0f, nowPlayingCoverArtworkAlpha(flight), 0f)
    }
}
