package moe.ouom.neriplayer.ui.screen

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.ui.haptic.HapticFeedbackEffect
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingProgressInfoSegment
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingProgressOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingSeekActionOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.buildNowPlayingProgressInfoPresentation
import moe.ouom.neriplayer.ui.screen.nowplaying.buildNowPlayingProgressInfoSegments
import moe.ouom.neriplayer.ui.screen.nowplaying.isNowPlayingPhoneLandscape
import moe.ouom.neriplayer.ui.screen.nowplaying.nowPlayingProgressFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingPlaybackControlsTest {
    @Test
    fun `progress presentation keeps its row while next track metadata is loading`() {
        val info = PlaybackAudioInfo(
            source = PlaybackAudioSource.NETEASE,
            qualityLabel = "Hi-Res",
            codecLabel = "FLAC",
            sampleRateHz = 96000,
            bitDepth = 24
        )
        val loaded = buildNowPlayingProgressInfoPresentation(info, true, true, true, 1f, false)
        val loading = buildNowPlayingProgressInfoPresentation(null, true, true, true, 1f, false)
        assertEquals(listOf("24 bit · 96 kHz"), loaded.specRow?.map { it.label })
        assertEquals(listOf("Hi-Res", "FLAC"), loaded.badgeRow?.map { it.label })
        assertEquals(emptyList<NowPlayingProgressInfoSegment>(), loading.specRow)
        assertEquals(emptyList<NowPlayingProgressInfoSegment>(), loading.badgeRow)
    }

    @Test
    fun `row space follows each setting while metadata is loading`() {
        fun checkRows(quality: Boolean, codec: Boolean, spec: Boolean, speed: Float, phone: Boolean) {
            val layout = buildNowPlayingProgressInfoPresentation(null, quality, codec, spec, speed, phone)
            assertEquals(spec, layout.specRow != null)
            assertEquals(!phone && (quality || codec || speed == 1.25f), layout.badgeRow != null)
        }
        for (phone in listOf(false, true)) {
            checkRows(false, false, false, 1f, phone)
            checkRows(true, false, false, 1f, phone)
            checkRows(false, true, false, 1f, phone)
            checkRows(false, false, true, 1f, phone)
            checkRows(true, true, true, 1.25f, phone)
            checkRows(false, false, false, 1.25f, phone)
            checkRows(false, false, false, 1.004f, phone)
        }
    }

    @Test
    fun `phone landscape follows the spec setting and rotation restores the lower badges`() {
        val info = PlaybackAudioInfo(
            PlaybackAudioSource.NETEASE, qualityLabel = "高清环绕声", codecLabel = "FLAC",
            bitDepth = 24, sampleRateHz = 96_000, bitrateKbps = 3129
        )
        fun layout(landscape: Boolean, width: Int, showSpec: Boolean) =
            buildNowPlayingProgressInfoPresentation(info, true, true, showSpec, 1.25f,
                isNowPlayingPhoneLandscape(landscape, width))

        val spec = listOf(NowPlayingProgressInfoSegment("24 bit · 96 kHz · 3129 kbps"))
        assertEquals(spec, layout(true, 599, true).specRow)
        assertNull(layout(true, 599, false).specRow)
        assertNull(layout(true, 599, true).badgeRow)
        val badges = listOf("高清环绕声", "1.25x", "FLAC")
        assertEquals(badges, layout(false, 599, true).badgeRow?.map { it.label })
        assertEquals(badges, layout(false, 599, false).badgeRow?.map { it.label })
        for (width in listOf(600, 800)) {
            assertEquals(spec, layout(true, width, true).specRow)
            assertEquals(badges, layout(true, width, true).badgeRow?.map { it.label })
        }
    }

    @Test
    fun `tablet quality and codec settings affect only the lower row`() {
        val info = PlaybackAudioInfo(PlaybackAudioSource.NETEASE, qualityLabel = "Hi-Res", codecLabel = "FLAC")
        fun badges(quality: Boolean, codec: Boolean) =
            buildNowPlayingProgressInfoPresentation(info, quality, codec, true, 1f, false).badgeRow

        assertEquals(listOf(NowPlayingProgressInfoSegment("Hi-Res", highlighted = true)), badges(true, false))
        assertEquals(listOf(NowPlayingProgressInfoSegment("FLAC")), badges(false, true))
        assertNull(badges(false, false))
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
    fun `live progress reads ignore mirrored slider position changes`() {
        val owner = NowPlayingProgressOwner(1000L)
        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            Snapshot.sendApplyNotifications()
            observer.observeReads(Any(), { _: Any -> invalidations++ }) {
                assertEquals(1000L, owner.previewPositionMs(1000L))
                assertNull(owner.previewOverrideMs(1000L))
            }

            owner.observePlaybackPosition(2000L)
            Snapshot.sendApplyNotifications()

            assertEquals(0, invalidations)
            assertEquals(2000L, owner.sliderPosition.toLong())
            assertEquals(2000L, owner.previewPositionMs(2000L))
            assertNull(owner.previewOverrideMs(2000L))
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test
    fun `dragged progress reads follow slider position changes`() {
        val owner = NowPlayingProgressOwner(1000L)
        owner.startDrag(0.6f, 10000L)
        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            Snapshot.sendApplyNotifications()
            observer.observeReads(Any(), { _: Any -> invalidations++ }) {
                assertEquals(6000L, owner.previewPositionMs(1000L))
                assertEquals(6000L, owner.previewOverrideMs(1000L))
            }

            owner.observePlaybackPosition(2000L)
            Snapshot.sendApplyNotifications()
            assertEquals(0, invalidations)

            owner.moveDrag(0.7f, 10000L)
            Snapshot.sendApplyNotifications()

            assertEquals(1, invalidations)
            assertEquals(7000L, owner.previewPositionMs(2000L))
            assertEquals(7000L, owner.previewOverrideMs(2000L))
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test
    fun `pending progress reads invalidate when playback reaches the seek preview`() {
        val owner = NowPlayingProgressOwner(1000L)
        owner.startDrag(0.6f, 10000L)
        owner.finishDrag()
        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            Snapshot.sendApplyNotifications()
            observer.observeReads(Any(), { _: Any -> invalidations++ }) {
                assertEquals(6000L, owner.previewPositionMs(1000L))
                assertEquals(6000L, owner.previewOverrideMs(1000L))
            }

            owner.observePlaybackPosition(2000L)
            Snapshot.sendApplyNotifications()
            assertEquals(0, invalidations)
            assertEquals(6000L, owner.previewPositionMs(2000L))

            owner.observePlaybackPosition(5900L)
            Snapshot.sendApplyNotifications()

            assertEquals(1, invalidations)
            assertNull(owner.pendingSeekPreviewPositionMs)
            assertEquals(5900L, owner.previewPositionMs(5900L))
            assertNull(owner.previewOverrideMs(5900L))
        } finally {
            observer.stop()
            observer.clear()
        }
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
                NowPlayingProgressInfoSegment("FLAC")
            ),
            buildNowPlayingProgressInfoSegments(info, true, true, 1.25f)
        )
        assertEquals(
            listOf(NowPlayingProgressInfoSegment("FLAC")),
            buildNowPlayingProgressInfoSegments(info, false, true, 1f)
        )
        assertEquals(
            emptyList<NowPlayingProgressInfoSegment>(),
            buildNowPlayingProgressInfoSegments(null, true, true, 1.25f)
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
                true, true, 1f
            )
        )
    }
}
