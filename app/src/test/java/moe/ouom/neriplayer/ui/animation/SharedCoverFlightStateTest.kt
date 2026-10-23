package moe.ouom.neriplayer.ui.animation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedCoverFlightStateTest {
    @Test
    fun linkedMorphKeepsItsPreferredArtworkUntilTheNextHandoff() {
        val state = SharedCoverFlightState()
        val bounds = Rect(16f, 700f, 64f, 748f)
        val mini = ImageBitmap(2, 2)
        val preferred = ImageBitmap(4, 4)
        val replacement = ImageBitmap(3, 3)
        state.reportSource(bounds, 10f, mini)
        state.beginLinked(preferred)
        assertSame(preferred, state.linkedBitmap)

        state.reportSource(bounds, 10f, replacement)
        state.beginLinked(replacement)
        assertSame(preferred, state.linkedBitmap)

        state.endLinked()
        assertNull(state.linkedBitmap)
        state.beginLinked()
        assertSame(replacement, state.linkedBitmap)
    }

    @Test
    fun placeholderClearsArtworkFromThePreviousSong() {
        val state = SharedCoverFlightState()
        val cover = ImageBitmap(2, 2)
        val bounds = Rect(16f, 700f, 64f, 748f)
        state.reportSource(bounds, 10f, cover)
        assertSame(cover, state.miniCoverBitmap)

        state.reportSource(bounds, 10f, null)

        assertNull(state.miniCoverBitmap)
    }

    @Test
    fun morphKeepsItsSourceWhileTheMiniPlayerExitsAndTheBackdropScales() {
        val state = SharedCoverFlightState()
        val cover = Rect(112f, 700f, 160f, 748f)
        val capsule = Rect(96f, 688f, 1200f, 768f)
        state.reportSource(cover, 10f, ImageBitmap(2, 2))
        state.reportMiniPlayerContainer(capsule)
        state.beginLinked()

        state.reportSource(Rect(120f, 730f, 164f, 768f), 9f, state.miniCoverBitmap)
        state.reportMiniPlayerContainer(Rect(110f, 720f, 1180f, 768f))

        assertEquals(cover, state.miniCoverBounds)
        assertEquals(capsule, state.miniPlayerContainerBounds)
        state.endLinked()
        val nextCover = Rect(20f, 500f, 68f, 548f)
        state.reportSource(nextCover, 10f, state.miniCoverBitmap)
        assertEquals(nextCover, state.miniCoverBounds)
    }

    @Test
    fun restoredPlayerKeepsItsRealCoverWithoutAMiniPlayerSource() {
        val state = SharedCoverFlightState()

        state.beginLinked()

        assertFalse(state.hidesCovers)
    }

    @Test
    fun realCoverRemainsVisibleUntilTheDestinationIsLaidOut() {
        val state = SharedCoverFlightState()
        state.reportSource(Rect(16f, 700f, 64f, 748f), 10f, ImageBitmap(2, 2))

        state.beginLinked()

        assertFalse(state.hidesCovers)
    }

    @Test
    fun linkedMorphDoesNotTakeOwnershipWhenItsBitmapArrivesLate() {
        val state = SharedCoverFlightState()
        val bounds = Rect(16f, 700f, 64f, 748f)
        state.reportSource(bounds, 10f, null)
        state.targetCoordinates = mock(LayoutCoordinates::class.java).also {
            `when`(it.isAttached).thenReturn(true)
        }
        state.beginLinked()

        state.reportSource(bounds, 10f, ImageBitmap(2, 2))

        assertFalse(state.hidesCovers)
        state.beginLinked()
        assertFalse(state.hidesCovers)

        state.endLinked()
        state.beginLinked()
        assertTrue(state.hidesCovers)
    }

    @Test
    fun linkedMorphKeepsOwnershipWhenTheMiniPlayerClearsItsBitmap() {
        val state = SharedCoverFlightState()
        val bounds = Rect(16f, 700f, 64f, 748f)
        state.reportSource(bounds, 10f, ImageBitmap(2, 2))
        state.targetCoordinates = mock(LayoutCoordinates::class.java).also {
            `when`(it.isAttached).thenReturn(true)
        }
        state.beginLinked()

        state.reportSource(bounds, 10f, null)

        assertNull(state.miniCoverBitmap)
        assertTrue(state.hidesCovers)
        state.beginLinked()
        assertTrue(state.hidesCovers)

        state.endLinked()
        state.beginLinked()
        assertFalse(state.hidesCovers)
    }
}
