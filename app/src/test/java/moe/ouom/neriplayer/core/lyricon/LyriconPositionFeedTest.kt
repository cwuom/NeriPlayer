package moe.ouom.neriplayer.core.lyricon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyriconPositionFeedTest {
    @Test
    fun `missing anchor never resolves feed position`() {
        assertNull(
            resolveLyriconFeedPosition(
                anchor = null,
                nowElapsedRealtimeNanos = 90_000_000_000L,
            )
        )
    }

    @Test
    fun `one x matches advanced lyrics wall clock delta`() {
        val anchor = LyriconPositionAnchor(
            positionMs = 12_000L,
            elapsedRealtimeNanos = 50_000_000_000L,
            durationMs = 180_000L,
            playbackSpeed = 1f,
        )
        assertEquals(
            12_200L,
            resolveLyriconFeedPosition(
                anchor = anchor,
                nowElapsedRealtimeNanos = 50_200_000_000L,
            )
        )
    }

    @Test
    fun `slow speed half rate matches advanced lyrics formula`() {
        val anchor = LyriconPositionAnchor(
            positionMs = 10_000L,
            elapsedRealtimeNanos = 1_000_000_000L,
            durationMs = 200_000L,
            playbackSpeed = 0.5f,
        )
        assertEquals(
            10_200L,
            resolveLyriconFeedPosition(
                anchor = anchor,
                nowElapsedRealtimeNanos = 1_400_000_000L,
            )
        )
    }

    @Test
    fun `fast speed one point five matches advanced lyrics formula`() {
        val anchor = LyriconPositionAnchor(
            positionMs = 10_000L,
            elapsedRealtimeNanos = 1_000_000_000L,
            durationMs = 200_000L,
            playbackSpeed = 1.5f,
        )
        assertEquals(
            10_300L,
            resolveLyriconFeedPosition(
                anchor = anchor,
                nowElapsedRealtimeNanos = 1_200_000_000L,
            )
        )
    }

    @Test
    fun `zero speed freezes media position`() {
        val anchor = LyriconPositionAnchor(
            positionMs = 8_000L,
            elapsedRealtimeNanos = 0L,
            durationMs = 60_000L,
            playbackSpeed = 0f,
        )
        assertEquals(
            8_000L,
            resolveLyriconFeedPosition(
                anchor = anchor,
                nowElapsedRealtimeNanos = 5_000_000_000L,
            )
        )
    }

    @Test
    fun `rebasing speed freezes media position then advances with new speed`() {
        val anchor = LyriconPositionAnchor(
            positionMs = 10_000L,
            elapsedRealtimeNanos = 1_000_000_000L,
            durationMs = 200_000L,
            playbackSpeed = 1f,
        )
        val rebased = rebaseLyriconPositionAnchor(
            anchor = anchor,
            newSpeed = 0.5f,
            nowElapsedRealtimeNanos = 1_200_000_000L,
            durationMs = 200_000L,
        )
        assertEquals(10_200L, rebased?.positionMs)
        assertEquals(0.5f, rebased?.playbackSpeed)
        assertEquals(
            10_300L,
            resolveLyriconFeedPosition(
                anchor = rebased,
                nowElapsedRealtimeNanos = 1_400_000_000L,
            )
        )
    }

    @Test
    fun `rebasing from slow to fast does not keep old drift`() {
        val slowAnchor = LyriconPositionAnchor(
            positionMs = 5_000L,
            elapsedRealtimeNanos = 0L,
            durationMs = 60_000L,
            playbackSpeed = 0.5f,
        )
        val rebased = rebaseLyriconPositionAnchor(
            anchor = slowAnchor,
            newSpeed = 2f,
            nowElapsedRealtimeNanos = 1_000_000_000L,
            durationMs = 60_000L,
        )
        assertEquals(5_500L, rebased?.positionMs)
        assertEquals(
            5_700L,
            resolveLyriconFeedPosition(
                anchor = rebased,
                nowElapsedRealtimeNanos = 1_100_000_000L,
            )
        )
    }

    @Test
    fun `invalid speed falls back to one x`() {
        val anchor = LyriconPositionAnchor(
            positionMs = 5_000L,
            elapsedRealtimeNanos = 100_000_000L,
            durationMs = 0L,
            playbackSpeed = Float.NaN,
        )
        assertEquals(
            5_200L,
            resolveLyriconFeedPosition(
                anchor = anchor,
                nowElapsedRealtimeNanos = 300_000_000L,
            )
        )
        assertEquals(1f, normalizeLyriconPlaybackSpeed(Float.NaN))
        assertEquals(0f, normalizeLyriconPlaybackSpeed(-1f))
    }

    @Test
    fun `media position is strict without display lead`() {
        assertEquals(
            1_000L,
            mediaLyriconPositionMs(positionMs = 1_000L, durationMs = 10_000L)
        )
        assertEquals(
            10_000L,
            mediaLyriconPositionMs(positionMs = 10_500L, durationMs = 10_000L)
        )
    }

    @Test
    fun `display lead compensates status bar lag without stacking into anchor`() {
        assertEquals(750L, LYRICON_DISPLAY_LEAD_MS)
        assertEquals(
            1_750L,
            displayLyriconPositionMs(mediaPositionMs = 1_000L, durationMs = 10_000L)
        )
        assertEquals(
            10_000L,
            displayLyriconPositionMs(mediaPositionMs = 9_500L, durationMs = 10_000L)
        )
        // lead 不进媒体锚点：同一 media 多次 display 结果稳定
        val media = 2_000L
        assertEquals(
            displayLyriconPositionMs(mediaPositionMs = media, durationMs = 10_000L),
            displayLyriconPositionMs(mediaPositionMs = media, durationMs = 10_000L),
        )
    }

    @Test
    fun `position clamp never goes negative or past duration`() {
        assertEquals(0L, clampLyriconPositionMs(-10L, 1_000L))
        assertEquals(1_000L, clampLyriconPositionMs(5_000L, 1_000L))
        assertEquals(5_000L, clampLyriconPositionMs(5_000L, 0L))
    }

    @Test
    fun `bogus zero anchor can hit duration while null avoids it`() {
        val bogusZeroAnchor = LyriconPositionAnchor(
            positionMs = 0L,
            elapsedRealtimeNanos = 0L,
            durationMs = 180_000L,
            playbackSpeed = 1f,
        )
        assertEquals(
            180_000L,
            resolveLyriconFeedPosition(
                anchor = bogusZeroAnchor,
                nowElapsedRealtimeNanos = 250_000_000_000L,
            )
        )
        assertNull(
            resolveLyriconFeedPosition(
                anchor = null,
                nowElapsedRealtimeNanos = 250_000_000_000L,
            )
        )
    }
}
