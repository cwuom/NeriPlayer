package moe.ouom.neriplayer.ui.animation

import androidx.compose.runtime.mutableFloatStateOf
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
    fun closingMorphFollowsTheReturningMiniWithoutReplacingItsArtwork() {
        val state = SharedCoverFlightState()
        val initialCover = Rect(54f, 2096f, 159f, 2201f)
        val finalCover = Rect(54f, 2012f, 159f, 2117f)
        val finalCapsule = Rect(32f, 1980f, 1048f, 2148f)
        val artwork = ImageBitmap(2, 2)
        val replacement = ImageBitmap(3, 3)
        state.reportSource(initialCover, 21f, artwork)
        state.reportMiniPlayerContainer(finalCapsule.translate(0f, 84f))
        state.beginLinked(artwork, followMiniPlayerGeometry = true)

        state.reportSource(finalCover, 20f, replacement, continuousCorners = false)
        state.reportMiniPlayerContainer(finalCapsule)

        assertEquals(finalCover, state.miniCoverBounds)
        assertEquals(finalCapsule, state.miniPlayerContainerBounds)
        assertEquals(20f, state.miniCoverCornerPx, 0f)
        assertEquals(0f, state.miniCoverCornerSmoothing, 0f)
        assertSame(artwork, state.linkedBitmap)
        assertSame(replacement, state.miniCoverBitmap)
    }

    @Test
    fun committingAnAlreadyLinkedPreviewStartsFollowingTheMiniWithoutRecapturingArtwork() {
        val state = SharedCoverFlightState()
        val initialCover = Rect(54f, 2096f, 159f, 2201f)
        val finalCover = Rect(54f, 2012f, 159f, 2117f)
        val initialCapsule = Rect(32f, 2064f, 1048f, 2232f)
        val finalCapsule = Rect(32f, 1980f, 1048f, 2148f)
        val artwork = ImageBitmap(2, 2)
        val replacement = ImageBitmap(3, 3)
        state.reportSource(initialCover, 21f, artwork)
        state.reportMiniPlayerContainer(initialCapsule)
        state.beginLinked()
        state.reportSource(finalCover, 21f, replacement)
        state.reportMiniPlayerContainer(finalCapsule)
        assertEquals(initialCover, state.miniCoverBounds)
        assertEquals(initialCapsule, state.miniPlayerContainerBounds)

        state.beginLinked(replacement, followMiniPlayerGeometry = true)
        state.reportSource(finalCover, 21f, replacement)
        state.reportMiniPlayerContainer(finalCapsule)

        assertEquals(finalCover, state.miniCoverBounds)
        assertEquals(finalCapsule, state.miniPlayerContainerBounds)
        assertSame(artwork, state.linkedBitmap)
    }

    @Test
    fun reopeningAnAlreadyLinkedCloseFreezesTheSourceAgain() {
        val state = SharedCoverFlightState()
        val source = Rect(54f, 2012f, 159f, 2117f)
        val capsule = Rect(32f, 1980f, 1048f, 2148f)
        val artwork = ImageBitmap(2, 2)
        state.reportSource(source, 21f, artwork)
        state.reportMiniPlayerContainer(capsule)
        state.beginLinked(followMiniPlayerGeometry = true)

        state.beginLinked()
        state.reportSource(source.translate(0f, 84f), 21f, artwork)
        state.reportMiniPlayerContainer(capsule.translate(0f, 84f))

        assertEquals(source, state.miniCoverBounds)
        assertEquals(capsule, state.miniPlayerContainerBounds)
        assertSame(artwork, state.linkedBitmap)
    }

    @Test
    fun endingACloseDoesNotMakeTheNextOpeningFollowTheMini() {
        val state = SharedCoverFlightState()
        val source = Rect(54f, 2012f, 159f, 2117f)
        val capsule = Rect(32f, 1980f, 1048f, 2148f)
        val artwork = ImageBitmap(2, 2)
        state.reportSource(source, 21f, artwork)
        state.reportMiniPlayerContainer(capsule)
        state.beginLinked(followMiniPlayerGeometry = true)
        state.endLinked()

        state.beginLinked()
        state.reportSource(source.translate(0f, 84f), 21f, artwork)
        state.reportMiniPlayerContainer(capsule.translate(0f, 84f))

        assertEquals(source, state.miniCoverBounds)
        assertEquals(capsule, state.miniPlayerContainerBounds)
    }

    @Test
    fun reversingAnOpeningPreservesBothEffectiveBoundsBeforeAnotherPositionCallback() {
        val state = SharedCoverFlightState()
        val progress = mutableFloatStateOf(0.4f)
        state.nowPlayingMorphProgressState = progress
        val source = Rect(20f, 700f, 60f, 740f)
        val capsule = Rect(8f, 688f, 312f, 752f)
        state.reportSource(source, 10f, ImageBitmap(2, 2))
        state.reportMiniPlayerContainer(capsule)
        state.beginLinked()
        state.reportSource(Rect(24f, 760f, 62f, 798f), 10f, state.miniCoverBitmap)
        state.reportMiniPlayerContainer(Rect(16f, 748f, 304f, 812f))

        state.beginLinked(followMiniPlayerGeometry = true)

        assertEquals(source, state.miniCoverBounds)
        assertEquals(capsule, state.miniPlayerContainerBounds)
        progress.floatValue = 0.6f
        assertEquals("迟到的 opening 帧不能放大反向校正", source, state.miniCoverBounds)
        assertEquals(capsule, state.miniPlayerContainerBounds)
    }

    @Test
    fun reversalCorrectionFadesWithRemainingProgressWhileTheMiniKeepsMoving() {
        val state = SharedCoverFlightState()
        val progress = mutableFloatStateOf(0.8f)
        state.nowPlayingMorphProgressState = progress
        state.nowPlayingMorphProgress = 0.9f
        val source = Rect(20f, 700f, 60f, 740f)
        val capsule = Rect(8f, 688f, 312f, 752f)
        state.reportSource(source, 10f, ImageBitmap(2, 2))
        state.reportMiniPlayerContainer(capsule)
        state.beginLinked()
        state.reportSource(Rect(24f, 760f, 62f, 798f), 10f, state.miniCoverBitmap)
        state.reportMiniPlayerContainer(Rect(16f, 748f, 304f, 812f))
        state.beginLinked(followMiniPlayerGeometry = true)

        val liveSource = Rect(26f, 750f, 64f, 788f)
        val liveCapsule = Rect(19f, 738f, 307f, 802f)
        state.reportSource(liveSource, 10f, state.miniCoverBitmap)
        state.reportMiniPlayerContainer(liveCapsule)
        progress.floatValue = 0.4f
        assertEquals(Rect(24f, 720f, 63f, 759f), state.miniCoverBounds)
        assertEquals(Rect(15f, 708f, 311f, 772f), state.miniPlayerContainerBounds)

        progress.floatValue = 0f
        assertSame("落位后直接返回真实源，避免继续分配校正矩形", liveSource, state.miniCoverBounds)
        assertSame(liveCapsule, state.miniPlayerContainerBounds)
    }

    @Test
    fun reopeningACorrectedCloseFreezesItsCurrentEffectiveGeometry() {
        val state = SharedCoverFlightState()
        val progress = mutableFloatStateOf(0.8f)
        state.nowPlayingMorphProgressState = progress
        val source = Rect(20f, 700f, 60f, 740f)
        val capsule = Rect(8f, 688f, 312f, 752f)
        state.reportSource(source, 10f, ImageBitmap(2, 2))
        state.reportMiniPlayerContainer(capsule)
        state.beginLinked()
        state.reportSource(source.translate(0f, 64f), 10f, state.miniCoverBitmap)
        state.reportMiniPlayerContainer(capsule.translate(0f, 64f))
        state.beginLinked(followMiniPlayerGeometry = true)
        progress.floatValue = 0.4f

        state.beginLinked()
        state.reportSource(source.translate(0f, 84f), 10f, state.miniCoverBitmap)
        state.reportMiniPlayerContainer(capsule.translate(0f, 84f))
        progress.floatValue = 0.9f
        assertEquals(source.translate(0f, 32f), state.miniCoverBounds)
        assertEquals(capsule.translate(0f, 32f), state.miniPlayerContainerBounds)

        state.beginLinked(followMiniPlayerGeometry = true)
        assertEquals(source.translate(0f, 32f), state.miniCoverBounds)
        assertEquals(capsule.translate(0f, 32f), state.miniPlayerContainerBounds)
        progress.floatValue = 0.45f
        assertEquals(source.translate(0f, 58f), state.miniCoverBounds)
        assertEquals(capsule.translate(0f, 58f), state.miniPlayerContainerBounds)
    }

    @Test
    fun continuingTheSameFollowKeepsItsOriginalCorrectionAndArtwork() {
        val state = SharedCoverFlightState()
        val progress = mutableFloatStateOf(0.8f)
        state.nowPlayingMorphProgressState = progress
        val source = Rect(20f, 700f, 60f, 740f)
        val capsule = Rect(8f, 688f, 312f, 752f)
        val artwork = ImageBitmap(2, 2)
        val replacement = ImageBitmap(3, 3)
        state.reportSource(source, 10f, artwork)
        state.reportMiniPlayerContainer(capsule)
        state.beginLinked()
        state.reportSource(source.translate(0f, 84f), 10f, replacement)
        state.reportMiniPlayerContainer(capsule.translate(0f, 84f))
        state.beginLinked(followMiniPlayerGeometry = true)
        progress.floatValue = 0.4f

        state.beginLinked(replacement, followMiniPlayerGeometry = true)
        assertEquals(source.translate(0f, 42f), state.miniCoverBounds)
        assertEquals(capsule.translate(0f, 42f), state.miniPlayerContainerBounds)
        assertSame(artwork, state.linkedBitmap)
        progress.floatValue = 0.2f
        assertEquals(source.translate(0f, 63f), state.miniCoverBounds)
        assertEquals(capsule.translate(0f, 63f), state.miniPlayerContainerBounds)

        state.endLinked()
        assertEquals(source.translate(0f, 84f), state.miniCoverBounds)
        assertEquals(capsule.translate(0f, 84f), state.miniPlayerContainerBounds)
        progress.floatValue = 1f
        state.beginLinked()
        state.reportSource(source, 10f, replacement)
        state.reportMiniPlayerContainer(capsule)
        state.beginLinked(followMiniPlayerGeometry = true)
        assertEquals("完整展开后的关闭不延续上轮校正", source, state.miniCoverBounds)
        assertEquals(capsule, state.miniPlayerContainerBounds)
    }

    @Test
    fun followingBeforeMiniGeometryArrivesKeepsMissingEndpointsUntilTheirOwnCallbacks() {
        val state = SharedCoverFlightState()
        val progress = mutableFloatStateOf(0.8f)
        state.nowPlayingMorphProgressState = progress
        val artwork = ImageBitmap(2, 2)
        val replacement = ImageBitmap(3, 3)
        state.beginLinked(artwork)

        state.beginLinked(followMiniPlayerGeometry = true)

        assertNull(state.miniCoverBounds)
        assertNull(state.miniPlayerContainerBounds)
        assertFalse(state.hidesCovers)
        assertSame(artwork, state.linkedBitmap)

        val source = Rect(20f, 700f, 60f, 740f)
        state.reportSource(source, 10f, replacement)
        progress.floatValue = 0.4f
        assertEquals(source, state.miniCoverBounds)
        assertNull("封面回调不能捏造尚未上报的容器", state.miniPlayerContainerBounds)
        assertSame(artwork, state.linkedBitmap)

        val capsule = Rect(8f, 688f, 312f, 752f)
        state.reportMiniPlayerContainer(capsule)
        progress.floatValue = 0.2f
        assertEquals(source, state.miniCoverBounds)
        assertEquals(capsule, state.miniPlayerContainerBounds)
        assertSame(artwork, state.linkedBitmap)
    }

    @Test
    fun followingIdenticalMiniGeometryKeepsArtworkAndTracksLaterMovementWithoutAnOffset() {
        val state = SharedCoverFlightState()
        val progress = mutableFloatStateOf(0.8f)
        state.nowPlayingMorphProgressState = progress
        val source = Rect(20f, 700f, 60f, 740f)
        val capsule = Rect(8f, 688f, 312f, 752f)
        val artwork = ImageBitmap(2, 2)
        val replacement = ImageBitmap(3, 3)
        state.reportSource(source, 10f, artwork)
        state.reportMiniPlayerContainer(capsule)
        state.beginLinked()
        state.reportSource(Rect(20f, 700f, 60f, 740f), 10f, replacement)
        state.reportMiniPlayerContainer(Rect(8f, 688f, 312f, 752f))

        state.beginLinked(replacement, followMiniPlayerGeometry = true)

        assertEquals(source, state.miniCoverBounds)
        assertEquals(capsule, state.miniPlayerContainerBounds)
        assertSame(artwork, state.linkedBitmap)

        val movedSource = Rect(22f, 690f, 62f, 730f)
        val movedCapsule = Rect(10f, 678f, 314f, 742f)
        state.reportSource(movedSource, 10f, replacement)
        state.reportMiniPlayerContainer(movedCapsule)
        progress.floatValue = 0.4f
        assertEquals(movedSource, state.miniCoverBounds)
        assertEquals(movedCapsule, state.miniPlayerContainerBounds)

        progress.floatValue = 0.2f
        assertEquals("剩余进度变化不能引入不存在的校正偏移", movedSource, state.miniCoverBounds)
        assertEquals(movedCapsule, state.miniPlayerContainerBounds)
        assertSame(artwork, state.linkedBitmap)
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
