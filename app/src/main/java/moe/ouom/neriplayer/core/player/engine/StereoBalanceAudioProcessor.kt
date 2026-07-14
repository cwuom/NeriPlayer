package moe.ouom.neriplayer.core.player.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_VOLUME_BALANCE
import moe.ouom.neriplayer.core.player.model.normalizePlaybackVolumeBalance

internal object PlaybackVolumeBalanceState {
    @Volatile
    private var balance = DEFAULT_PLAYBACK_VOLUME_BALANCE

    fun update(balance: Float) {
        this.balance = normalizePlaybackVolumeBalance(balance)
    }

    fun current(): Float = balance
}

@UnstableApi
internal class StereoBalanceAudioProcessor(
    private val balanceProvider: () -> Float = PlaybackVolumeBalanceState::current
) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.channelCount != STEREO_CHANNEL_COUNT
        ) {
            return AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inputSize = inputBuffer.remaining()
        if (inputSize == 0) return

        val outputBuffer = replaceOutputBuffer(inputSize)
        val gains = stereoBalanceGains(balanceProvider())
        if (gains.isCentered) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        while (inputBuffer.remaining() >= STEREO_FRAME_SIZE_BYTES) {
            val left = inputBuffer.short
            val right = inputBuffer.short
            outputBuffer.putShort(scalePcm16(left, gains.left))
            outputBuffer.putShort(scalePcm16(right, gains.right))
        }
        while (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer.get())
        }
        outputBuffer.flip()
    }

    private fun scalePcm16(sample: Short, gain: Float): Short {
        return (sample.toInt() * gain)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    companion object {
        private const val STEREO_CHANNEL_COUNT = 2
        private const val BYTES_PER_PCM16_SAMPLE = 2
        private const val STEREO_FRAME_SIZE_BYTES = STEREO_CHANNEL_COUNT * BYTES_PER_PCM16_SAMPLE
    }
}

internal data class StereoBalanceGains(
    val left: Float,
    val right: Float
) {
    val isCentered: Boolean
        get() = left == 1f && right == 1f
}

internal fun stereoBalanceGains(balance: Float): StereoBalanceGains {
    val normalized = normalizePlaybackVolumeBalance(balance)
    return StereoBalanceGains(
        left = if (normalized > 0f) 1f - normalized else 1f,
        right = if (normalized < 0f) 1f + normalized else 1f
    )
}
