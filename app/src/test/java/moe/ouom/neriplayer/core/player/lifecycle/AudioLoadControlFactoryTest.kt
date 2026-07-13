@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lifecycle

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLoadControlFactoryTest {
    private val playerId = PlayerId("audio-load-control-test")
    private val mediaPeriodId = MediaPeriodId("audio-load-control-period")

    @Test
    fun `audio playback starts after 800 milliseconds`() {
        val loadControl = buildAudioLoadControl()

        assertFalse(loadControl.shouldStart(bufferedDurationMs = 799))
        assertTrue(loadControl.shouldStart(bufferedDurationMs = 800))
    }

    @Test
    fun `audio playback resumes after 1500 milliseconds`() {
        val loadControl = buildAudioLoadControl()

        assertFalse(loadControl.shouldStart(bufferedDurationMs = 1_499, rebuffering = true))
        assertTrue(loadControl.shouldStart(bufferedDurationMs = 1_500, rebuffering = true))
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
            Timeline.EMPTY,
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
}
