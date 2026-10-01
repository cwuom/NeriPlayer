package moe.ouom.neriplayer.core.player.audio.processing

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

@UnstableApi
class VolumeNormalizationAudioProcessor(
    private val stateProvider: () -> PlaybackVolumeNormalizationSnapshot =
        PlaybackVolumeNormalizationState::current
) : BaseAudioProcessor() {
    // 开启高解析度输出时音源会以 PCM_FLOAT 进链, 这里按编码切换归一化器
    // 避免只支持 16-bit 时对 float 直接返回 NOT_SET 让响度归一化被整段旁路
    private var normalizer: VolumeNormalizer = Pcm16VolumeNormalizer()
    private val reusableStats = Pcm16LevelStats()
    private var sampleRate = 0
    private var channelCount = 0
    private var appliedGeneration = Long.MIN_VALUE

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (
            inputAudioFormat.channelCount <= 0 ||
            inputAudioFormat.sampleRate <= 0
        ) {
            return AudioFormat.NOT_SET
        }
        normalizer = selectNormalizer(inputAudioFormat.encoding) ?: return AudioFormat.NOT_SET
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    private fun selectNormalizer(encoding: Int): VolumeNormalizer? {
        return when (encoding) {
            C.ENCODING_PCM_16BIT ->
                normalizer as? Pcm16VolumeNormalizer ?: Pcm16VolumeNormalizer()
            C.ENCODING_PCM_FLOAT ->
                normalizer as? FloatVolumeNormalizer ?: FloatVolumeNormalizer()
            // 其它编码 (如 24/32-bit 整数 PCM) 不在处理链支持范围内, 安全旁路
            else -> null
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inputSize = inputBuffer.remaining()
        if (inputSize == 0) return

        val outputBuffer = replaceOutputBuffer(inputSize)
        val state = stateProvider()
        if (state.generation != appliedGeneration) {
            normalizer.reset()
            appliedGeneration = state.generation
        }
        if (!state.enabled) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        normalizer.process(
            inputBuffer = inputBuffer,
            outputBuffer = outputBuffer,
            sampleRate = sampleRate,
            channelCount = channelCount,
            stats = reusableStats
        )
        outputBuffer.flip()
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        normalizer.reset()
        appliedGeneration = Long.MIN_VALUE
    }
}
