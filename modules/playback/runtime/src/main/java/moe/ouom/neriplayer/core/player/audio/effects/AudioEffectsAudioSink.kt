package moe.ouom.neriplayer.core.player.audio.effects

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsInactiveReason
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val STATS_INTERVAL_MS = 1_000L
private val SupportedEncodings = setOf(
    C.ENCODING_PCM_16BIT,
    C.ENCODING_PCM_FLOAT,
    C.ENCODING_PCM_24BIT,
    C.ENCODING_PCM_32BIT
)

@UnstableApi
internal data class AudioEffectsPcmFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: Int,
    val frameBytes: Int
) {
    companion object {
        fun from(format: Format): AudioEffectsPcmFormat? {
            if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return null
            if (format.pcmEncoding !in SupportedEncodings) return null
            if (format.channelCount !in 1..2 || format.sampleRate <= 0) return null
            return AudioEffectsPcmFormat(
                sampleRate = format.sampleRate,
                channelCount = format.channelCount,
                encoding = format.pcmEncoding,
                frameBytes = Util.getPcmFrameSize(format.pcmEncoding, format.channelCount)
            )
        }
    }
}

internal interface AudioEffectsSinkRuntime {
    fun snapshot(): AudioEffectsRuntimeSnapshot
    fun publishEngineStats(stats: AudioEffectsEngineStats)
    fun publishPathState(sinkReason: AudioEffectsInactiveReason?, nativeAvailable: Boolean)
    fun elapsedRealtimeMs(): Long
}

internal object DefaultAudioEffectsSinkRuntime : AudioEffectsSinkRuntime {
    override fun snapshot(): AudioEffectsRuntimeSnapshot = AudioEffectsRuntimeState.current()
    override fun publishEngineStats(stats: AudioEffectsEngineStats) = AudioEffectsRuntimeState.publishEngineStats(stats)
    override fun publishPathState(sinkReason: AudioEffectsInactiveReason?, nativeAvailable: Boolean) =
        AudioEffectsRuntimeState.publishPathState(sinkReason, nativeAvailable)
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * 在系统输出和 USB 独占之前处理 PCM。处理结果与输入等长、零延迟；
 * 下游未完全接收前持续提交同一块处理结果，输入缓冲只在全部写出后才标记为已消费
 */
@UnstableApi
internal class AudioEffectsAudioSink(
    delegate: AudioSink,
    private val usbNativeOutputActive: () -> Boolean = { false },
    private val engineFactory: () -> AudioEffectsDspEngine? = NativeAudioEffectsDspEngine::createOrNull,
    private val runtime: AudioEffectsSinkRuntime = DefaultAudioEffectsSinkRuntime
) : ForwardingAudioSink(delegate) {
    private var format: AudioEffectsPcmFormat? = null
    private var engine: AudioEffectsDspEngine? = null
    private var engineUnavailable = false
    private var engineConfigured = false
    private var engineRunning = false
    private var appliedGeneration = Long.MIN_VALUE
    private var appliedWanted = false
    private var pendingInput: ByteBuffer? = null
    private var passthroughInput: ByteBuffer? = null
    private var processed: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var staging: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var lastStatsAtMs = 0L
    private var lastReportKey: Triple<AudioEffectsInactiveReason?, Boolean, Long>? = null
    private val statsBuffer = LongArray(AUDIO_EFFECTS_STATS_LENGTH)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        clearPending()
        format = AudioEffectsPcmFormat.from(inputFormat)
        engineConfigured = false
        engineRunning = false
        appliedGeneration = Long.MIN_VALUE
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (passthroughInput === buffer) return passThrough(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (pendingInput !== buffer) {
            clearPending()
            if (!processIntoPending(buffer)) return passThrough(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (!super.handleBuffer(processed, presentationTimeUs, encodedAccessUnitCount)) return false
        buffer.position(buffer.limit())
        pendingInput = null
        return true
    }

    // 下游未完全接收的原始缓冲必须原样重交，音效开关只能在缓冲边界生效
    private fun passThrough(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        passthroughInput = if (handled) null else buffer
        return handled
    }

    override fun flush() {
        clearPending()
        engine?.reset()
        super.flush()
    }

    override fun reset() {
        clearPending()
        releaseEngine()
        super.reset()
    }

    override fun release() {
        clearPending()
        releaseEngine()
        super.release()
    }

    private fun processIntoPending(buffer: ByteBuffer): Boolean {
        val snapshot = runtime.snapshot()
        val pcm = format
        if (pcm == null) {
            reportPath(if (snapshot.active) AudioEffectsInactiveReason.UNSUPPORTED_FORMAT else null, snapshot)
            return false
        }
        val usbBlocked = snapshot.active && !snapshot.usbNativeAllowed && usbNativeOutputActive()
        reportPath(if (usbBlocked) AudioEffectsInactiveReason.USB_EXCLUSIVE else null, snapshot)
        if (usbBlocked) {
            // 比特完美路径不做淡出，直接交给 USB 原始 PCM
            engineRunning = false
            return false
        }
        if (!snapshot.active && !engineRunning) return false
        val dsp = configuredEngine(pcm, snapshot) ?: return false
        syncParams(dsp, snapshot)
        return runEngine(dsp, buffer, pcm)
    }

    private fun runEngine(dsp: AudioEffectsDspEngine, buffer: ByteBuffer, pcm: AudioEffectsPcmFormat): Boolean {
        val size = buffer.remaining()
        val alignedSize = size - size % pcm.frameBytes
        if (alignedSize <= 0) return false
        val input = directInput(buffer, size)
        val output = processedBuffer(size)
        val status = dsp.process(input, input.position(), output, 0, alignedSize)
        if (status < 0) {
            engineRunning = false
            return false
        }
        for (index in alignedSize until size) {
            output.put(index, input.get(input.position() + index))
        }
        output.position(0)
        output.limit(size)
        engineRunning = status and AUDIO_EFFECTS_STATUS_IDLE == 0
        pendingInput = buffer
        maybePublishStats(dsp, pcm)
        return true
    }

    private fun configuredEngine(pcm: AudioEffectsPcmFormat, snapshot: AudioEffectsRuntimeSnapshot): AudioEffectsDspEngine? {
        if (engineUnavailable) return null
        val current = engine ?: engineFactory()?.also { engine = it }
        if (current == null) {
            engineUnavailable = true
            reportPath(AudioEffectsInactiveReason.NATIVE_UNAVAILABLE, snapshot)
            return null
        }
        if (!engineConfigured) {
            if (!current.configure(pcm.sampleRate, pcm.channelCount, pcm.encoding)) {
                format = null
                reportPath(AudioEffectsInactiveReason.UNSUPPORTED_FORMAT, snapshot)
                return null
            }
            engineConfigured = true
            appliedGeneration = Long.MIN_VALUE
        }
        return current
    }

    private fun syncParams(dsp: AudioEffectsDspEngine, snapshot: AudioEffectsRuntimeSnapshot) {
        if (appliedGeneration == snapshot.generation && appliedWanted == snapshot.active) return
        val params = if (snapshot.active) snapshot.params else snapshot.disabledParams
        if (dsp.setParams(params)) {
            appliedGeneration = snapshot.generation
            appliedWanted = snapshot.active
        }
    }

    private fun directInput(buffer: ByteBuffer, size: Int): ByteBuffer {
        if (buffer.isDirect) return buffer
        if (staging.capacity() < size) {
            staging = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }
        staging.clear()
        staging.put(buffer.duplicate())
        staging.flip()
        return staging
    }

    private fun processedBuffer(size: Int): ByteBuffer {
        if (processed.capacity() < size) {
            processed = ByteBuffer.allocateDirect(maxOf(size, processed.capacity() * 2)).order(ByteOrder.nativeOrder())
        }
        processed.clear()
        return processed
    }

    private fun maybePublishStats(dsp: AudioEffectsDspEngine, pcm: AudioEffectsPcmFormat) {
        val now = runtime.elapsedRealtimeMs()
        if (now - lastStatsAtMs < STATS_INTERVAL_MS) return
        lastStatsAtMs = now
        if (!dsp.readStats(statsBuffer)) return
        runtime.publishEngineStats(
            AudioEffectsEngineStats(
                processedFrames = statsBuffer[0],
                processingNanos = statsBuffer[1],
                limiterReductionDb = statsBuffer[2] / 100f,
                compressorReductionDb = statsBuffer[4] / 100f,
                sampleRate = statsBuffer[6].toInt().takeIf { it > 0 } ?: pcm.sampleRate
            )
        )
    }

    private fun reportPath(reason: AudioEffectsInactiveReason?, snapshot: AudioEffectsRuntimeSnapshot) {
        val key = Triple(reason, !engineUnavailable, snapshot.generation)
        if (key == lastReportKey) return
        lastReportKey = key
        runtime.publishPathState(reason, !engineUnavailable)
    }

    private fun clearPending() {
        pendingInput = null
        passthroughInput = null
    }

    private fun releaseEngine() {
        engine?.release()
        engine = null
        engineConfigured = false
        engineRunning = false
        appliedGeneration = Long.MIN_VALUE
    }
}
