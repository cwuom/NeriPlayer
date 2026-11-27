@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine

import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.audio.HIGH_RATE_PCM_SAMPLE_RATE_HZ
import java.nio.ByteBuffer

internal class TimedHighRatePcmOutputProvider(delegate: AudioOutputProvider) :
    ForwardingAudioOutputProvider(delegate) {
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val output = super.getAudioOutput(config)
        return if (config.sampleRate >= HIGH_RATE_PCM_SAMPLE_RATE_HZ &&
            Util.isEncodingLinearPcm(config.encoding)
        ) {
            if (NPLogger.isFileLoggingEnabled) {
                NPLogger.d(
                    "NERI-PlaybackTiming",
                    "audioTrack configured: sampleRate=${output.sampleRate} encoding=${config.encoding} " +
                        "bufferBytes=${config.bufferSize} bufferFrames=${output.bufferSizeInFrames}"
                )
            }
            TimedPcmOutput(output)
        } else {
            output
        }
    }
}

private class TimedPcmOutput(delegate: AudioOutput) : ForwardingAudioOutput(delegate) {
    private var playing = false
    private val writes = PlaybackCallTiming("audioTrack.write", ::shouldMeasure)
    private val position = PlaybackCallTiming("audioTrack.position", ::shouldMeasure, reportGaps = false)

    override fun play() {
        writes.reset()
        super.play()
        playing = true
    }

    override fun pause() {
        playing = false
        super.pause()
    }

    override fun stop() {
        playing = false
        super.stop()
    }

    override fun flush() {
        writes.reset()
        super.flush()
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean =
        writes.measure { super.write(buffer, encodedAccessUnitCount, presentationTimeUs) }

    override fun getPositionUs(): Long = position.measure { super.getPositionUs() }

    private fun shouldMeasure(): Boolean = playing && NPLogger.isFileLoggingEnabled
}
