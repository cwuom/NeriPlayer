package moe.ouom.neriplayer.core.player.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedOutlinedLyricTextViewTest {

    @Test
    fun longLyricsRevealFasterPerCharacter() {
        val shortLine = "0123456789"
        val longLine = "01234567890123456789"

        val shortDuration = AnimatedOutlinedLyricTextView.resolveRevealDurationMs(shortLine)
        val longDuration = AnimatedOutlinedLyricTextView.resolveRevealDurationMs(longLine)

        assertEquals(360L, shortDuration)
        assertEquals(540L, longDuration)
        assertTrue(longDuration.toFloat() / longLine.length < shortDuration.toFloat() / shortLine.length)
    }

    @Test
    fun veryLongLyricsUseTheBoundedRevealDuration() {
        assertEquals(
            900L,
            AnimatedOutlinedLyricTextView.resolveRevealDurationMs("0123456789".repeat(5))
        )
    }

    @Test
    fun pausedPlaybackDoesNotStartLongLineScroll() {
        assertTrue(
            !AnimatedOutlinedLyricTextView.shouldStartScroll(
                playbackActive = false,
                viewWidthPx = 320,
                contentWidthPx = 280f,
                textWidthPx = 520f,
                thresholdPx = 2f
            )
        )
        assertTrue(
            AnimatedOutlinedLyricTextView.shouldStartScroll(
                playbackActive = true,
                viewWidthPx = 320,
                contentWidthPx = 280f,
                textWidthPx = 520f,
                thresholdPx = 2f
            )
        )
    }

    @Test
    fun edgeMaskWaitsForTheLineToLeaveItsRestingEndpoint() {
        val progressAtStart = AnimatedOutlinedLyricTextView.resolveEdgeMaskProgress(
            scrollOffsetPx = 0f,
            overflowPx = 96f,
            activationDistancePx = 8f,
            transitionDistancePx = 12f
        )
        val progressAtActivation = AnimatedOutlinedLyricTextView.resolveEdgeMaskProgress(
            scrollOffsetPx = 8f,
            overflowPx = 96f,
            activationDistancePx = 8f,
            transitionDistancePx = 12f
        )
        val progressDuringTransition = AnimatedOutlinedLyricTextView.resolveEdgeMaskProgress(
            scrollOffsetPx = 14f,
            overflowPx = 96f,
            activationDistancePx = 8f,
            transitionDistancePx = 12f
        )
        val progressAfterTransition = AnimatedOutlinedLyricTextView.resolveEdgeMaskProgress(
            scrollOffsetPx = 20f,
            overflowPx = 96f,
            activationDistancePx = 8f,
            transitionDistancePx = 12f
        )

        assertEquals(0f, progressAtStart, 0.001f)
        assertEquals(0f, progressAtActivation, 0.001f)
        assertEquals(0.5f, progressDuringTransition, 0.001f)
        assertEquals(1f, progressAfterTransition, 0.001f)
    }

    @Test
    fun edgeMaskFadesOutBeforeTheLineReachesTheOtherEndpoint() {
        val nearOtherEndpoint = AnimatedOutlinedLyricTextView.resolveEdgeMaskProgress(
            scrollOffsetPx = 88f,
            overflowPx = 96f,
            activationDistancePx = 8f,
            transitionDistancePx = 12f
        )
        val partwayBack = AnimatedOutlinedLyricTextView.resolveEdgeMaskProgress(
            scrollOffsetPx = 82f,
            overflowPx = 96f,
            activationDistancePx = 8f,
            transitionDistancePx = 12f
        )

        assertEquals(0f, nearOtherEndpoint, 0.001f)
        assertEquals(0.5f, partwayBack, 0.001f)
    }

    @Test
    fun edgeMaskStaysOffWhenTheLineCannotLeaveEitherEndpointFarEnough() {
        val progress = AnimatedOutlinedLyricTextView.resolveEdgeMaskProgress(
            scrollOffsetPx = 8f,
            overflowPx = 16f,
            activationDistancePx = 8f,
            transitionDistancePx = 12f
        )

        assertEquals(0f, progress, 0.001f)
    }

    @Test
    fun edgeFadeWidthIsBoundedForLargeAndSmallViewports() {
        assertEquals(
            24f,
            AnimatedOutlinedLyricTextView.resolveEdgeFadeWidthPx(280f, density = 1f)
        )
        assertEquals(
            6.6f,
            AnimatedOutlinedLyricTextView.resolveEdgeFadeWidthPx(30f, density = 1f),
            0.001f
        )
    }

    @Test
    fun opacitySliderMapsEndpointsToTransparentAndOpaquePaintAlpha() {
        assertEquals(0, resolveFloatingLyricsAlphaByte(0f))
        assertEquals(128, resolveFloatingLyricsAlphaByte(0.5f))
        assertEquals(255, resolveFloatingLyricsAlphaByte(1f))
        assertEquals(255, resolveFloatingLyricsAlphaByte(1.5f))
    }

    @Test
    fun opacitySliderOverridesAnySourceColorAlpha() {
        assertEquals(
            0xFFFFFFFF.toInt(),
            resolveFloatingLyricsColorWithAlpha(0xFFFFFFFF.toInt(), 1f)
        )
        assertEquals(
            0xFFABCDEF.toInt(),
            resolveFloatingLyricsColorWithAlpha(0x80ABCDEF.toInt(), 1f)
        )
        assertEquals(
            0x80ABCDEF.toInt(),
            resolveFloatingLyricsColorWithAlpha(0x40ABCDEF, 0.5f)
        )
        assertEquals(
            0x00ABCDEF,
            resolveFloatingLyricsColorWithAlpha(0xFFABCDEF.toInt(), 0f)
        )
    }

    @Test
    fun translucentEffectsFadeFasterThanTheLyricFill() {
        assertEquals(0f, resolveFloatingLyricsEffectAlpha(0f), 0.001f)
        assertEquals(1f, resolveFloatingLyricsEffectAlpha(1f), 0.001f)
        assertEquals(0.09f, resolveFloatingLyricsEffectAlpha(0.3f), 0.001f)
    }
}
