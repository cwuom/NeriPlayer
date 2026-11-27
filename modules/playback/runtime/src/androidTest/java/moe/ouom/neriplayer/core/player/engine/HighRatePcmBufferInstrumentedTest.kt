@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.core.player.audio.SystemAudioTrackBufferSizeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class HighRatePcmBufferInstrumentedTest {
    @Test(timeout = 15_000L)
    fun fullBufferSpansRecordedPcmWriteGap() {
        assertBuffersAcrossGap(sampleRate = 192_000, gapMs = 1_490, previousFloorMs = 640)
    }

    @Test(timeout = 15_000L)
    fun halfFullNormalRateBufferSpansRecordedFeedGap() {
        assertBuffersAcrossGap(sampleRate = 96_000, gapMs = 449, waitForHalfBuffer = true)
    }

    private fun assertBuffersAcrossGap(
        sampleRate: Int,
        gapMs: Long,
        previousFloorMs: Int = 0,
        waitForHalfBuffer: Boolean = false
    ) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runPcmGapProbe") == "true")
        val platformMinimum = AudioTrack.getMinBufferSize(sampleRate, CHANNEL_MASK, ENCODING)
        assertTrue("$sampleRate Hz PCM output must be supported", platformMinimum > 0)
        val defaultBufferBytes = DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT.getBufferSizeInBytes(
            platformMinimum, C.ENCODING_PCM_16BIT, DefaultAudioSink.OUTPUT_MODE_PCM,
            FRAME_BYTES, sampleRate, C.RATE_UNSET_INT, 1.0
        )
        val oldBufferBytes = maxOf(defaultBufferBytes, sampleRate * FRAME_BYTES * previousFloorMs / 1_000)
        val newBufferBytes = SystemAudioTrackBufferSizeProvider.getBufferSizeInBytes(
            platformMinimum, C.ENCODING_PCM_16BIT, DefaultAudioSink.OUTPUT_MODE_PCM,
            FRAME_BYTES, sampleRate, C.RATE_UNSET_INT, 1.0
        )
        val oldOutput = measureWriteGap(sampleRate, oldBufferBytes, gapMs, waitForHalfBuffer)
        val newOutput = measureWriteGap(sampleRate, newBufferBytes, gapMs, waitForHalfBuffer)

        assertTrue("Expected the previous buffer to underrun: $oldOutput", oldOutput.underruns > 0)
        assertEquals("Expected no underrun: $newOutput", 0, newOutput.underruns)
        val minimumAdvance = sampleRate * gapMs / 1_000 * 3 / 4
        assertTrue("Output did not advance through the gap: $newOutput", newOutput.advancedFrames >= minimumAdvance)
    }

    private fun measureWriteGap(
        sampleRate: Int,
        bufferBytes: Int,
        gapMs: Long,
        waitForHalfBuffer: Boolean
    ): GapResult {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(ENCODING).setChannelMask(CHANNEL_MASK)
                    .setSampleRate(sampleRate).build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        try {
            val frames = track.bufferSizeInFrames
            val pcm = ByteBuffer.allocateDirect(frames * FRAME_BYTES)
            assertEquals(pcm.remaining(), track.write(pcm, pcm.remaining(), AudioTrack.WRITE_NON_BLOCKING))
            track.play()
            val targetFrame = if (waitForHalfBuffer) frames / 2 else 1
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (track.playbackHeadPosition < targetFrame && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(5)
            }
            val startFrame = track.playbackHeadPosition
            assertTrue("AudioTrack did not reach frame $targetFrame", startFrame >= targetFrame)
            val underrunsBefore = track.underrunCount
            val gapStartedMs = SystemClock.elapsedRealtime()
            SystemClock.sleep(gapMs)
            return GapResult(
                bufferFrames = frames,
                gapMs = SystemClock.elapsedRealtime() - gapStartedMs,
                advancedFrames = track.playbackHeadPosition - startFrame,
                underruns = track.underrunCount - underrunsBefore
            ).also { Log.i("NERI-PcmGapProbe", it.toString()) }
        } finally {
            track.release()
        }
    }

    private data class GapResult(
        val bufferFrames: Int,
        val gapMs: Long,
        val advancedFrames: Int,
        val underruns: Int
    )

    private companion object {
        const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_BYTES = 4
    }
}
