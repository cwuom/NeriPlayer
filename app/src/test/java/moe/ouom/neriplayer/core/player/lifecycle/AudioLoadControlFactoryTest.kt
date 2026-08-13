@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lifecycle

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLoadControlFactoryTest {
    private val playerId = PlayerId("audio-load-control-test")
    private val mediaPeriodId = MediaPeriodId("audio-load-control-period")

    @Test
    fun `audio playback starts after one second`() {
        val loadControl = buildAudioLoadControl()

        assertFalse(loadControl.shouldStart(bufferedDurationMs = 999))
        assertTrue(loadControl.shouldStart(bufferedDurationMs = 1_000))
    }

    @Test
    fun `audio playback resumes after three seconds`() {
        val loadControl = buildAudioLoadControl()

        assertFalse(loadControl.shouldStart(bufferedDurationMs = 2_999, rebuffering = true))
        assertTrue(loadControl.shouldStart(bufferedDurationMs = 3_000, rebuffering = true))
    }

    @Test
    fun `audio loading stays within 15 to 30 second window`() {
        val loadControl = buildAudioLoadControl()
        loadControl.onPrepared(playerId)

        assertTrue(loadControl.shouldContinueLoading(parameters(bufferedDurationMs = 14_999)))
        assertFalse(loadControl.shouldContinueLoading(parameters(bufferedDurationMs = 30_000)))

        loadControl.onReleased(playerId)
    }

    @Test
    fun `audio keeps a back buffer so short rewinds stay offline`() {
        val loadControl = buildAudioLoadControl()

        assertEquals(60_000L, loadControl.getBackBufferDurationUs(playerId) / 1_000)
        // 音频逐帧可解, 从关键帧起留只会白扔掉能用的数据
        assertFalse(loadControl.retainBackBufferFromKeyframe(playerId))
    }

    @Test
    fun `an out of range back buffer just means no back buffer`() {
        val loadControl = buildAudioLoadControl(AudioLoadControlPolicy(backBufferMs = -1))

        assertEquals(0L, loadControl.getBackBufferDurationUs(playerId))
        // 回退缓冲越界不该连带把调好的启播时延也打回默认值
        assertFalse(loadControl.shouldStart(999))
        assertTrue(loadControl.shouldStart(1_000))
    }

    @Test
    fun `invalid policy falls back to media3 defaults`() {
        val loadControl = buildAudioLoadControl(
            AudioLoadControlPolicy(
                minBufferMs = 500,
                maxBufferMs = 400,
                bufferForPlaybackMs = 800,
                bufferForPlaybackAfterRebufferMs = 1_500
            )
        )
        val defaultStartBufferMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS.toLong()
        loadControl.onPrepared(playerId)

        assertFalse(loadControl.shouldStart(defaultStartBufferMs - 1))
        assertTrue(loadControl.shouldStart(defaultStartBufferMs))

        loadControl.onReleased(playerId)
    }

    private fun LoadControl.shouldStart(
        bufferedDurationMs: Long,
        rebuffering: Boolean = false
    ): Boolean {
        return shouldStartPlayback(parameters(bufferedDurationMs, rebuffering))
    }

    private fun parameters(
        bufferedDurationMs: Long,
        rebuffering: Boolean = false
    ): LoadControl.Parameters {
        return LoadControl.Parameters(
            playerId,
            timelineFor(mediaPeriodId),
            mediaPeriodId,
            0L,
            bufferedDurationMs * 1_000,
            1f,
            true,
            rebuffering,
            C.TIME_UNSET,
            C.TIME_UNSET
        )
    }

    private fun timelineFor(mediaPeriodId: MediaPeriodId): Timeline {
        return object : Timeline() {
            override fun getWindowCount(): Int = 1

            override fun getWindow(
                windowIndex: Int,
                window: Window,
                defaultPositionProjectionUs: Long
            ): Window {
                check(windowIndex == 0)
                return window.set(
                    WINDOW_UID,
                    MediaItem.EMPTY,
                    null,
                    C.TIME_UNSET,
                    C.TIME_UNSET,
                    C.TIME_UNSET,
                    true,
                    false,
                    null,
                    0L,
                    C.TIME_UNSET,
                    0,
                    0,
                    0L
                )
            }

            override fun getPeriodCount(): Int = 1

            override fun getPeriod(
                periodIndex: Int,
                period: Period,
                setIds: Boolean
            ): Period {
                check(periodIndex == 0)
                val periodUid = mediaPeriodId.periodUid
                val id = if (setIds) periodUid else null
                return period.set(id, periodUid, 0, C.TIME_UNSET, 0L)
            }

            override fun getIndexOfPeriod(uid: Any): Int {
                return if (uid == mediaPeriodId.periodUid) 0 else C.INDEX_UNSET
            }

            override fun getUidOfPeriod(periodIndex: Int): Any {
                check(periodIndex == 0)
                return mediaPeriodId.periodUid
            }
        }
    }

    private companion object {
        private val WINDOW_UID = Any()
    }
}
