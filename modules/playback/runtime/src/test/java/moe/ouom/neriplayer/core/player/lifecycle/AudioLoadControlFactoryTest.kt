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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AudioLoadControlFactoryTest {
    private val playerId = PlayerId("audio-load-control-test")
    private val mediaPeriodId = MediaPeriodId("audio-load-control-period")
    private val stream = MediaItem.fromUri("https://example.invalid/song.flac")
    private val local = MediaItem.fromUri("content://media/external/audio/media/1")
    private val lan = MediaItem.fromUri("http://127.0.0.1/media/song")

    @Test
    fun `lan resumes loading below thirty seconds after filling its fifty second window`() {
        val loadControl = buildLanAudioLoadControl()
        loadControl.onPrepared(playerId)

        assertTrue(loadControl.shouldContinueLoading(parameters(600, item = lan)))
        assertTrue(loadControl.shouldContinueLoading(parameters(49_999, item = lan)))
        assertFalse(loadControl.shouldContinueLoading(parameters(50_000, item = lan)))
        assertFalse(loadControl.shouldContinueLoading(parameters(30_000, item = lan)))
        assertTrue(loadControl.shouldContinueLoading(parameters(29_999, item = lan)))
        assertEquals(0L, loadControl.getBackBufferDurationUs(playerId))

        loadControl.onReleased(playerId)
    }

    @Test
    fun `lan requires enough playable time even when high bitrate audio fills the byte budget`() {
        val loadControl = buildLanAudioLoadControl()
        loadControl.onPrepared(playerId)
        val allocator = loadControl.getAllocator(playerId)
        val allocations = List(DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE / allocator.individualAllocationLength) {
            allocator.allocate()
        }
        try {
            assertTrue(loadControl.shouldContinueLoading(parameters(10_000, item = lan)))
            assertFalse(loadControl.shouldContinueLoading(parameters(30_000, item = lan)))
            assertFalse(loadControl.shouldStart(599, item = lan))
            assertTrue(loadControl.shouldStart(600, item = lan))
            assertFalse(loadControl.shouldStart(4_999, rebuffering = true, item = lan))
            assertTrue(loadControl.shouldStart(5_000, rebuffering = true, item = lan))
        } finally {
            allocations.forEach(allocator::release)
            loadControl.onReleased(playerId)
        }
    }

    @Test
    fun `audio playback starts after one second`() {
        val loadControl = buildAudioLoadControl()

        for (item in listOf(stream, local)) {
            assertFalse(loadControl.shouldStart(999, item = item))
            assertTrue(loadControl.shouldStart(1_000, item = item))
        }
    }

    @Test
    fun `local playback resumes after three seconds`() {
        val loadControl = buildAudioLoadControl()

        assertFalse(loadControl.shouldStart(2_999, rebuffering = true, item = local))
        assertTrue(loadControl.shouldStart(3_000, rebuffering = true, item = local))
    }

    @Test
    fun `streaming playback resumes after five seconds`() {
        val loadControl = buildAudioLoadControl()

        assertFalse(loadControl.shouldStart(4_999, rebuffering = true, item = stream))
        assertTrue(loadControl.shouldStart(5_000, rebuffering = true, item = stream))
    }

    @Test
    fun `streaming refills earlier than local playback after reaching five minutes`() {
        for ((item, lowWatermarkMs) in listOf(stream to 30_000L, local to 15_000L)) {
            val loadControl = buildAudioLoadControl()
            loadControl.onPrepared(playerId)

            assertTrue(loadControl.shouldContinueLoading(parameters(lowWatermarkMs - 1, item = item)))
            assertTrue(loadControl.shouldContinueLoading(parameters(200_000, item = item)))
            assertFalse(loadControl.shouldContinueLoading(parameters(300_000, item = item)))
            assertFalse(loadControl.shouldContinueLoading(parameters(lowWatermarkMs, item = item)))
            assertTrue(loadControl.shouldContinueLoading(parameters(lowWatermarkMs - 1, item = item)))

            loadControl.onReleased(playerId)
        }
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
                localMinBufferMs = 500,
                maxBufferMs = 400,
                bufferForPlaybackMs = 800,
                localBufferForPlaybackAfterRebufferMs = 1_500
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
        rebuffering: Boolean = false,
        item: MediaItem = stream
    ): Boolean {
        return shouldStartPlayback(parameters(bufferedDurationMs, rebuffering, item))
    }

    private fun parameters(
        bufferedDurationMs: Long,
        rebuffering: Boolean = false,
        item: MediaItem = stream
    ): LoadControl.Parameters {
        return LoadControl.Parameters(
            playerId,
            timelineFor(mediaPeriodId, item),
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

    private fun timelineFor(mediaPeriodId: MediaPeriodId, item: MediaItem): Timeline {
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
                    item,
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
