package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.ui.haptic.HapticFeedbackEffect
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingProgressInfoSegment
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingProgressOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingSeekActionOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.buildNowPlayingProgressInfoSegments
import moe.ouom.neriplayer.ui.screen.nowplaying.isNowPlayingPhoneLandscape
import moe.ouom.neriplayer.ui.screen.nowplaying.nowPlayingProgressFraction
import moe.ouom.neriplayer.ui.screen.nowplaying.nowPlayingVisibleProgressInfoSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingPlaybackControlsTest {
    @Test
    fun `only phone landscape hides audio badges and rotating back restores the original information`() {
        val segments = listOf(
            NowPlayingProgressInfoSegment("高清环绕声", highlighted = true),
            NowPlayingProgressInfoSegment("FLAC"),
            NowPlayingProgressInfoSegment("96 kHz | 24 bit")
        )

        assertTrue(nowPlayingVisibleProgressInfoSegments(
            segments, phoneLandscape = isNowPlayingPhoneLandscape(true, 599)
        ).isEmpty())
        assertSame(segments, nowPlayingVisibleProgressInfoSegments(
            segments, phoneLandscape = isNowPlayingPhoneLandscape(false, 599)
        ))
        listOf(600, 800).forEach { tabletWidth ->
            listOf(true, false).forEach { landscape ->
                assertSame(segments, nowPlayingVisibleProgressInfoSegments(
                    segments, phoneLandscape = isNowPlayingPhoneLandscape(landscape, tabletWidth)
                ))
            }
        }
        assertEquals(3, segments.size)
        assertTrue(segments.first().highlighted)
    }

    @Test
    fun `tablet audio badges still respect individual display settings`() {
        val info = PlaybackAudioInfo(
            source = PlaybackAudioSource.NETEASE,
            qualityLabel = "Hi-Res",
            codecLabel = "FLAC",
            sampleRateHz = 96000,
            bitDepth = 24
        )
        val tabletLandscape = isNowPlayingPhoneLandscape(true, 800)
        assertEquals(
            listOf(NowPlayingProgressInfoSegment("Hi-Res", highlighted = true)),
            nowPlayingVisibleProgressInfoSegments(
                buildNowPlayingProgressInfoSegments(info, true, false, false, 1f), tabletLandscape
            )
        )
        assertEquals(
            listOf(NowPlayingProgressInfoSegment("FLAC")),
            nowPlayingVisibleProgressInfoSegments(
                buildNowPlayingProgressInfoSegments(info, false, true, false, 1f), tabletLandscape
            )
        )
        assertEquals(
            listOf(NowPlayingProgressInfoSegment("96 kHz | 24 bit")),
            nowPlayingVisibleProgressInfoSegments(
                buildNowPlayingProgressInfoSegments(info, false, false, true, 1f), tabletLandscape
            )
        )
        assertTrue(nowPlayingVisibleProgressInfoSegments(
            buildNowPlayingProgressInfoSegments(info, false, false, false, 1f), tabletLandscape
        ).isEmpty())
    }

    @Test
    fun `seek owner keeps preview until playback settles`() {
        val owner = NowPlayingProgressOwner(1000L)
        assertEquals(1000L, owner.previewPositionMs(1000L))
        assertNull(owner.previewOverrideMs(1000L))
        assertFalse(owner.isPreviewing)

        assertEquals(5000L, owner.startDrag(0.5f, 10000L))
        assertTrue(owner.isDragging)
        assertTrue(owner.isPreviewing)
        assertEquals(6000L, owner.moveDrag(0.6f, 10000L))
        owner.observePlaybackPosition(1100L)
        assertEquals(6000L, owner.previewOverrideMs(1100L))

        assertEquals(6000L, owner.finishDrag())
        assertFalse(owner.isDragging)
        assertEquals(6000L, owner.pendingSeekPreviewPositionMs)
        assertTrue(owner.isPreviewing)
        owner.observePlaybackPosition(1200L)
        assertEquals(6000L, owner.previewPositionMs(1200L))
        owner.observePlaybackPosition(5900L)
        assertNull(owner.pendingSeekPreviewPositionMs)
        assertFalse(owner.isPreviewing)
        assertNull(owner.previewOverrideMs(5900L))
        owner.observePlaybackPosition(6100L)
        assertEquals(6100L, owner.sliderPosition.toLong())
    }

    @Test
    fun `cancelled seek returns to live position and zero duration stays safe`() {
        val owner = NowPlayingProgressOwner(800L)
        owner.startDrag(0.75f, 10000L)
        owner.cancelDrag(900L)
        assertFalse(owner.isDragging)
        assertNull(owner.pendingSeekPreviewPositionMs)
        assertEquals(900L, owner.previewPositionMs(900L))
        assertEquals(0f, nowPlayingProgressFraction(100L, 0L), 0f)
        assertEquals(0.5f, nowPlayingProgressFraction(500L, 1000L), 0f)
    }

    @Test
    fun `seek action owner dispatches one start move finish sequence`() {
        val progress = NowPlayingProgressOwner(1000L)
        val calls = mutableListOf<String>()
        val actions = NowPlayingSeekActionOwner(
            progress = progress,
            onSeekStart = { calls += "start:$it" },
            onSeekMove = { calls += "move:$it" },
            onSeekEnd = { calls += "end" },
            onSeekTo = { calls += "seek:$it" },
            onFeedback = { calls += "haptic:$it" }
        )
        actions.updatePlayback(10000L, 1000L)
        actions.onValueChangeStarted(0.4f)
        actions.onValueChange(0.5f)
        actions.onValueChangeFinished()
        assertEquals(
            listOf(
                "start:4000", "haptic:${HapticFeedbackEffect.Click}",
                "move:5000", "seek:5000", "end", "haptic:${HapticFeedbackEffect.Confirm}"
            ),
            calls
        )
        actions.updatePlayback(10000L, 2000L)
        actions.onValueChangeStarted(0.8f)
        actions.onValueChangeCanceled()
        assertEquals(2000L, progress.previewPositionMs(2000L))
        assertFalse(progress.isDragging)
    }

    @Test
    fun `seek owner keeps gesture callbacks stable while rebinding current effects`() {
        val progress = NowPlayingProgressOwner(0L)
        val calls = mutableListOf<String>()
        val first = progress.bindSeekActions(
            onSeekStart = { calls += "old:$it" },
            onSeekMove = {},
            onSeekEnd = {},
            onSeekTo = {},
            onFeedback = {}
        )
        val onValueChangeStarted = first.onValueChangeStarted
        val rebound = progress.bindSeekActions(
            onSeekStart = { calls += "current:$it" },
            onSeekMove = {},
            onSeekEnd = {},
            onSeekTo = {},
            onFeedback = {}
        )
        assertSame(first, rebound)
        assertSame(onValueChangeStarted, rebound.onValueChangeStarted)
        rebound.updatePlayback(1000L, 0L)
        rebound.onValueChangeStarted(0.5f)
        assertEquals(listOf("current:500"), calls)
    }

    @Test
    fun `progress badges reflect enabled metadata and nondefault speed`() {
        val info = PlaybackAudioInfo(
            source = PlaybackAudioSource.NETEASE,
            qualityLabel = "Hi-Res",
            codecLabel = "FLAC",
            sampleRateHz = 96000,
            bitDepth = 24
        )
        assertEquals(
            listOf(
                NowPlayingProgressInfoSegment("Hi-Res", highlighted = true),
                NowPlayingProgressInfoSegment("1.25x"),
                NowPlayingProgressInfoSegment("FLAC"),
                NowPlayingProgressInfoSegment("96 kHz | 24 bit")
            ),
            buildNowPlayingProgressInfoSegments(info, true, true, true, 1.25f)
        )
        assertEquals(
            listOf(NowPlayingProgressInfoSegment("FLAC")),
            buildNowPlayingProgressInfoSegments(info, false, true, false, 1f)
        )
        assertEquals(
            emptyList<NowPlayingProgressInfoSegment>(),
            buildNowPlayingProgressInfoSegments(null, true, true, true, 1.25f)
        )
        assertEquals(
            emptyList<NowPlayingProgressInfoSegment>(),
            buildNowPlayingProgressInfoSegments(
                info.copy(
                    qualityLabel = " ",
                    codecLabel = " ",
                    sampleRateHz = null,
                    bitDepth = null
                ),
                true, true, true, 1f
            )
        )
    }
}
