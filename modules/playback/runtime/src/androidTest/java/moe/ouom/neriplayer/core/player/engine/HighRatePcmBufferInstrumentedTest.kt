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
        assumeTrue(InstrumentationRegistry.getArguments().getString("runPcmGapProbe") == "true")
        val platformMinimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        assertTrue("192 kHz PCM output must be supported", platformMinimum > 0)
        val defaultBufferBytes = DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT.getBufferSizeInBytes(
            platformMinimum, C.ENCODING_PCM_16BIT, DefaultAudioSink.OUTPUT_MODE_PCM,
            FRAME_BYTES, SAMPLE_RATE, C.RATE_UNSET_INT, 1.0
        )
        val oldBufferBytes = maxOf(defaultBufferBytes, SAMPLE_RATE * FRAME_BYTES * 640 / 1_000)
        val newBufferBytes = SystemAudioTrackBufferSizeProvider.getBufferSizeInBytes(
            platformMinimum, C.ENCODING_PCM_16BIT, DefaultAudioSink.OUTPUT_MODE_PCM,
            FRAME_BYTES, SAMPLE_RATE, C.RATE_UNSET_INT, 1.0
        )
        val oldOutput = measureWriteGap(oldBufferBytes)
        val newOutput = measureWriteGap(newBufferBytes)

        assertTrue("Expected the previous buffer to underrun: $oldOutput", oldOutput.underruns > 0)
        assertEquals("Expected no underrun: $newOutput", 0, newOutput.underruns)
        assertTrue("Output did not advance through the gap: $newOutput", newOutput.advancedFrames >= SAMPLE_RATE)
    }

    private fun measureWriteGap(bufferBytes: Int): GapResult {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(ENCODING).setChannelMask(CHANNEL_MASK)
                    .setSampleRate(SAMPLE_RATE).build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        try {
            val frames = track.bufferSizeInFrames
            val pcm = ByteBuffer.allocateDirect(frames * FRAME_BYTES)
            assertEquals(pcm.remaining(), track.write(pcm, pcm.remaining(), AudioTrack.WRITE_NON_BLOCKING))
            val underrunsBefore = track.underrunCount
            track.play()
            val deadline = SystemClock.elapsedRealtime() + 1_000
            while (track.playbackHeadPosition == 0 && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(5)
            }
            val startFrame = track.playbackHeadPosition
            assertTrue("AudioTrack did not start", startFrame > 0)
            val gapStartedMs = SystemClock.elapsedRealtime()
            SystemClock.sleep(1_490)
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
        const val SAMPLE_RATE = 192_000
        const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_BYTES = 4
    }
}
