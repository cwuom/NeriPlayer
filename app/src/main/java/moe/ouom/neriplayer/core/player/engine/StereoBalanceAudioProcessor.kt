package moe.ouom.neriplayer.core.player.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    private var encoding = C.ENCODING_PCM_16BIT

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != STEREO_CHANNEL_COUNT) {
            return AudioFormat.NOT_SET
        }
        if (
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioFormat.NOT_SET
        }
        encoding = inputAudioFormat.encoding
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

        if (encoding == C.ENCODING_PCM_FLOAT) {
            processFloat(inputBuffer, outputBuffer, gains)
        } else {
            processPcm16(inputBuffer, outputBuffer, gains)
        }
        outputBuffer.flip()
    }

    private fun processPcm16(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        gains: StereoBalanceGains
    ) {
        while (inputBuffer.remaining() >= STEREO_PCM16_FRAME_BYTES) {
            val left = inputBuffer.short
            val right = inputBuffer.short
            outputBuffer.putShort(scalePcm16(left, gains.left))
            outputBuffer.putShort(scalePcm16(right, gains.right))
        }
        while (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer.get())
        }
    }

    private fun processFloat(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        gains: StereoBalanceGains
    ) {
        val order = inputBuffer.order()
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        while (inputBuffer.remaining() >= STEREO_FLOAT_FRAME_BYTES) {
            val left = inputBuffer.float
            val right = inputBuffer.float
            outputBuffer.putFloat((left * gains.left).coerceIn(-1f, 1f))
            outputBuffer.putFloat((right * gains.right).coerceIn(-1f, 1f))
        }
        while (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer.get())
        }
        inputBuffer.order(order)
        outputBuffer.order(order)
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
        private const val BYTES_PER_FLOAT_SAMPLE = 4
        private const val STEREO_PCM16_FRAME_BYTES = STEREO_CHANNEL_COUNT * BYTES_PER_PCM16_SAMPLE
        private const val STEREO_FLOAT_FRAME_BYTES = STEREO_CHANNEL_COUNT * BYTES_PER_FLOAT_SAMPLE
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
