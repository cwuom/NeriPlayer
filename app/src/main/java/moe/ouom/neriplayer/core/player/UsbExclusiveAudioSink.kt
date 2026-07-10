@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import moe.ouom.neriplayer.core.player.usb.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.policy.resolvePlaybackSoundConfigForEngine
import moe.ouom.neriplayer.util.NPLogger

@UnstableApi
internal class UsbExclusiveAudioSink(
    private val context: Context,
    private val fallbackSink: AudioSink
) : ForwardingAudioSink(fallbackSink) {
    private companion object {
        const val PARAMETER_EPSILON = 0.0001f
        const val STREAM_VOLUME_POLL_INTERVAL_MS = 120L
    }

    private var listener: AudioSink.Listener? = null
    private var nativeHandle: Long = 0L
    private var usingNative = false
    private var fallbackConfigured = false
    private var configuredFormat: Format? = null
    private var configuredBufferSize = 0
    private var configuredOutputChannels: IntArray? = null
    private var sampleRate = 0
    private var channelCount = 0
    private var pcmEncoding = C.ENCODING_PCM_16BIT
    private var frameBytes = 0
    private var volume = 1f
    private var cachedStreamVolume = 1f
    private var lastStreamVolumePollMs = 0L
    private var playing = false
    private var nativeTransportStarted = false
    private var nativeHasQueuedPcm = false
    private var inputEnded = false
    private var startMediaTimeUs = C.TIME_UNSET
    private var writtenFrames = 0L
    private var writtenFramesAtTimelineStart = 0L
    private var completedFramesAtTimelineStart = 0L
    private var playAnchorPositionUs = 0L
    private var playAnchorElapsedNs = 0L
    private var lastPositionUs = 0L
    private var directScratch: ByteBuffer? = null
    private var discontinuityExpected = true
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var skipSilenceEnabled = false
    private var audioAttributes: AudioAttributes? = fallbackSink.audioAttributes
    private var audioSessionId: Int? = null
    private var auxEffectInfo = AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f)
    private var preferredDevice: AudioDeviceInfo? = null
    private var preferredDeviceWasSet = false
    private var outputStreamOffsetUs = 0L
    private var outputStreamOffsetWasSet = false
    private var tunnelingEnabled = false
    private var failoverRequested = false

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        fallbackSink.setListener(listener)
    }

    override fun setPlayerId(playerId: PlayerId?) {
        fallbackSink.setPlayerId(playerId)
    }

    override fun supportsFormat(format: Format): Boolean {
        return fallbackSink.supportsFormat(format) ||
            (PlayerManager.usbExclusivePlaybackEnabled && isNativePcmFormat(format))
    }

    override fun getFormatSupport(format: Format): Int {
        val fallbackSupport = fallbackSink.getFormatSupport(format)
        return if (PlayerManager.usbExclusivePlaybackEnabled && isNativePcmFormat(format)) {
            max(fallbackSupport, AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)
        } else {
            fallbackSupport
        }
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        return fallbackSink.getFormatOffloadSupport(format)
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        configuredFormat = inputFormat
        configuredBufferSize = specifiedBufferSize
        configuredOutputChannels = outputChannels?.copyOf()
        sampleRate = inputFormat.sampleRate
        channelCount = inputFormat.channelCount
        pcmEncoding = inputFormat.pcmEncoding
        frameBytes = pcmFrameBytes(pcmEncoding, channelCount)
        val fallbackReason = nativeCompatibilityFailure(inputFormat, configuredOutputChannels)
        failoverRequested = false
        if (PlayerManager.usbExclusivePlaybackEnabled && fallbackReason == null) {
            configureNative(inputFormat)
        } else {
            configureFallback(fallbackReason)
        }
    }

    override fun play() {
        playing = true
        UsbExclusiveAudioPathTracker.updatePlaying(playing = true, usingNative = usingNative)
        if (!usingNative) {
            fallbackSink.play()
            return
        }
        if (!startNativeTransportIfReady()) {
            requestSystemFailover("native_play_failed")
        }
    }

    override fun handleDiscontinuity() {
        if (!usingNative) {
            fallbackSink.handleDiscontinuity()
            return
        }
        discontinuityExpected = true
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!usingNative) {
            return fallbackSink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (!buffer.hasRemaining()) {
            return true
        }

        if (startMediaTimeUs == C.TIME_UNSET || discontinuityExpected) {
            val initialTimeline = startMediaTimeUs == C.TIME_UNSET
            startMediaTimeUs = max(0L, presentationTimeUs)
            writtenFramesAtTimelineStart = writtenFrames
            completedFramesAtTimelineStart =
                UsbExclusiveSessionController.completedAudioFrames(nativeHandle) +
                UsbExclusiveSessionController.queuedPlayerFrames(nativeHandle)
            playAnchorPositionUs = startMediaTimeUs
            playAnchorElapsedNs = SystemClock.elapsedRealtimeNanos()
            if (initialTimeline) {
                lastPositionUs = startMediaTimeUs
            }
            discontinuityExpected = false
            listener?.onPositionDiscontinuity()
        } else {
            val submittedFrames = writtenFrames - writtenFramesAtTimelineStart
            val expectedUs = startMediaTimeUs + framesToDurationUs(submittedFrames)
            if (abs(presentationTimeUs - expectedUs) > 200_000L) {
                startMediaTimeUs = max(
                    0L,
                    presentationTimeUs - framesToDurationUs(submittedFrames)
                )
                listener?.onPositionDiscontinuity()
            }
        }

        val original = buffer.duplicate()
        val remaining = buffer.remaining()
        val written = writeNative(buffer, remaining)
        if (written <= 0) {
            UsbExclusiveSessionController.refreshRuntime(nativeHandle)
            val runtimeReport = UsbExclusiveSessionController.state.value.runtimeReport
            if (isFatalNativeRuntime(runtimeReport)) {
                requestSystemFailover("native_transport_failed")
            }
            return false
        }
        nativeHasQueuedPcm = true

        if (playing && !startNativeTransportIfReady()) {
            requestSystemFailover("native_start_failed")
            return false
        }

        buffer.position(buffer.position() + written)
        writtenFrames += written / max(1, frameBytes)
        inputEnded = false
        original.limit(original.position() + written)
        AudioReactive.teeSink.handleBuffer(original)
        return written == remaining
    }

    override fun playToEndOfStream() {
        if (!usingNative) {
            fallbackSink.playToEndOfStream()
            return
        }
        inputEnded = true
    }

    override fun isEnded(): Boolean {
        return if (usingNative) inputEnded && !hasPendingData() else fallbackSink.isEnded()
    }

    override fun hasPendingData(): Boolean {
        if (!usingNative) {
            return fallbackSink.hasPendingData()
        }
        return UsbExclusiveSessionController.queuedPlayerFrames(nativeHandle) > 0L
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!usingNative) {
            return fallbackSink.getCurrentPositionUs(sourceEnded)
        }
        if (startMediaTimeUs == C.TIME_UNSET) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        val writtenPositionUs = startMediaTimeUs + framesToDurationUs(
            writtenFrames - writtenFramesAtTimelineStart
        )
        val positionUs = min(writtenPositionUs, currentNativePositionUs())
        lastPositionUs = max(lastPositionUs, positionUs)
        return lastPositionUs
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        if (usingNative && PlayerManager.usbExclusivePlaybackEnabled) {
            this.playbackParameters = PlaybackParameters.DEFAULT
            UsbExclusiveAudioPathTracker.updatePlaybackParameters(speed = 1f, pitch = 1f)
            return
        }
        val compatibilityChanged = hasDefaultPlaybackParameters(this.playbackParameters) !=
            hasDefaultPlaybackParameters(playbackParameters)
        this.playbackParameters = playbackParameters
        UsbExclusiveAudioPathTracker.updatePlaybackParameters(
            speed = playbackParameters.speed,
            pitch = playbackParameters.pitch
        )
        if (!usingNative) {
            fallbackSink.setPlaybackParameters(playbackParameters)
        } else if (!hasDefaultPlaybackParameters(playbackParameters)) {
            pauseNativeTransport()
        }
        if (compatibilityChanged && PlayerManager.usbExclusivePlaybackEnabled) {
            PlayerManager.scheduleUsbAudioSinkReconfiguration(
                "audio_sink_playback_parameters_changed"
            )
        }
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return playbackParameters
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        if (usingNative && PlayerManager.usbExclusivePlaybackEnabled) {
            this.skipSilenceEnabled = false
            UsbExclusiveAudioPathTracker.updateSkipSilence(false)
            return
        }
        val compatibilityChanged = this.skipSilenceEnabled != skipSilenceEnabled
        this.skipSilenceEnabled = skipSilenceEnabled
        UsbExclusiveAudioPathTracker.updateSkipSilence(skipSilenceEnabled)
        if (!usingNative) {
            fallbackSink.setSkipSilenceEnabled(skipSilenceEnabled)
        } else if (skipSilenceEnabled) {
            pauseNativeTransport()
        }
        if (compatibilityChanged && PlayerManager.usbExclusivePlaybackEnabled) {
            PlayerManager.scheduleUsbAudioSinkReconfiguration("skip_silence_compatibility_changed")
        }
    }

    override fun getSkipSilenceEnabled(): Boolean {
        return skipSilenceEnabled
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
        if (!usingNative) {
            fallbackSink.setAudioAttributes(audioAttributes)
        }
    }

    override fun getAudioAttributes(): AudioAttributes? {
        return audioAttributes
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
        if (!usingNative) {
            fallbackSink.setAudioSessionId(audioSessionId)
        }
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        if (usingNative && PlayerManager.usbExclusivePlaybackEnabled) {
            this.auxEffectInfo = AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f)
            return
        }
        val compatibilityChanged =
            (this.auxEffectInfo.effectId == AuxEffectInfo.NO_AUX_EFFECT_ID) !=
                (auxEffectInfo.effectId == AuxEffectInfo.NO_AUX_EFFECT_ID)
        this.auxEffectInfo = auxEffectInfo
        if (!usingNative) {
            fallbackSink.setAuxEffectInfo(auxEffectInfo)
        } else if (auxEffectInfo.effectId != AuxEffectInfo.NO_AUX_EFFECT_ID) {
            pauseNativeTransport()
        }
        if (compatibilityChanged && PlayerManager.usbExclusivePlaybackEnabled) {
            PlayerManager.scheduleUsbAudioSinkReconfiguration("aux_effect_compatibility_changed")
        }
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        preferredDevice = audioDeviceInfo
        preferredDeviceWasSet = true
        if (!usingNative) {
            fallbackSink.setPreferredDevice(audioDeviceInfo)
        }
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        this.outputStreamOffsetUs = outputStreamOffsetUs
        outputStreamOffsetWasSet = true
        if (!usingNative) {
            fallbackSink.setOutputStreamOffsetUs(outputStreamOffsetUs)
        }
    }

    override fun getAudioTrackBufferSizeUs(): Long {
        return if (usingNative) C.TIME_UNSET else fallbackSink.audioTrackBufferSizeUs
    }

    override fun enableTunnelingV21() {
        if (usingNative && PlayerManager.usbExclusivePlaybackEnabled) {
            tunnelingEnabled = false
            return
        }
        val compatibilityChanged = !tunnelingEnabled
        tunnelingEnabled = true
        if (!usingNative) {
            fallbackSink.enableTunnelingV21()
        } else {
            pauseNativeTransport()
        }
        if (compatibilityChanged && PlayerManager.usbExclusivePlaybackEnabled) {
            PlayerManager.scheduleUsbAudioSinkReconfiguration("tunneling_enabled")
        }
    }

    override fun disableTunneling() {
        val compatibilityChanged = tunnelingEnabled
        tunnelingEnabled = false
        if (!usingNative) {
            fallbackSink.disableTunneling()
        }
        if (compatibilityChanged && PlayerManager.usbExclusivePlaybackEnabled) {
            PlayerManager.scheduleUsbAudioSinkReconfiguration("tunneling_disabled")
        }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        val effectiveVolume = effectiveNativeVolume()
        UsbExclusiveAudioPathTracker.updateVolume(effectiveVolume)
        if (!usingNative) {
            fallbackSink.setVolume(this.volume)
        } else {
            UsbExclusiveSessionController.setPlayerVolume(nativeHandle, effectiveVolume)
        }
    }

    override fun pause() {
        if (playing && usingNative) {
            playAnchorPositionUs = currentNativePositionUs()
        }
        playing = false
        UsbExclusiveAudioPathTracker.updatePlaying(playing = false, usingNative = usingNative)
        if (!usingNative) {
            fallbackSink.pause()
            return
        }
        pauseNativeTransport()
    }

    override fun flush() {
        if (usingNative) {
            if (!UsbExclusiveSessionController.flushPlayerPcm(nativeHandle)) {
                requestSystemFailover("native_flush_failed")
            }
            nativeTransportStarted = false
            nativeHasQueuedPcm = false
        } else if (fallbackConfigured) {
            fallbackSink.flush()
        }
        resetPlaybackCounters(keepPlayState = true)
    }

    override fun reset() {
        closeNative()
        if (fallbackConfigured) {
            fallbackSink.reset()
        }
        fallbackConfigured = false
        configuredFormat = null
        configuredOutputChannels = null
        configuredBufferSize = 0
        resetPlaybackCounters(keepPlayState = false)
        UsbExclusiveAudioPathTracker.updateConfigured(
            usingNative = false,
            fallbackReason = null,
            inputFormat = "none"
        )
    }

    override fun release() {
        closeNative()
        fallbackConfigured = false
        fallbackSink.release()
        UsbExclusiveAudioPathTracker.updateConfigured(
            usingNative = false,
            fallbackReason = null,
            inputFormat = "none"
        )
        UsbExclusiveAudioPathTracker.updatePlaying(playing = false, usingNative = false)
    }

    private fun configureNative(inputFormat: Format) {
        if (fallbackConfigured) {
            fallbackSink.pause()
            fallbackSink.reset()
            fallbackConfigured = false
        }

        val openedHandle = UsbExclusiveSessionController.openPlayerPcm(
            context = context,
            inputSampleRate = inputFormat.sampleRate,
            inputChannelCount = inputFormat.channelCount,
            inputEncoding = inputFormat.pcmEncoding
        )
        if (openedHandle == 0L) {
            val openError = UsbExclusiveSessionController.state.value.lastError
                ?.takeUnless { it.isBlank() || it == "none" }
                ?: "native_open_failed"
            NPLogger.w(
                "NERI-UsbExclusive",
                "native player pcm unavailable, fallback to system AudioTrack: $openError"
            )
            configureFallback(openError)
            if (shouldRetryNativeFailure(openError)) {
                PlayerManager.scheduleUsbExclusiveTransportRecovery(openError)
            }
            return
        }

        nativeHandle = openedHandle
        usingNative = true
        resetPlaybackCounters(keepPlayState = true)
        UsbExclusiveSessionController.setPlayerVolume(nativeHandle, effectiveNativeVolume())
        AudioReactive.teeSink.flush(sampleRate, channelCount, pcmEncoding)
        UsbExclusiveAudioPathTracker.updateConfigured(
            usingNative = true,
            fallbackReason = null,
            inputFormat = inputFormatDescription(inputFormat)
        )
        UsbExclusiveAudioPathTracker.updatePlaying(playing, usingNative = true)
        PlayerManager.markUsbExclusiveNativePathActive("configure_native")
        NPLogger.d(
            "NERI-UsbExclusive",
            "configured native player pcm: sampleRate=$sampleRate channelCount=$channelCount encoding=$pcmEncoding frameBytes=$frameBytes"
        )
    }

    private fun configureFallback(fallbackReason: String?) {
        val inputFormat = configuredFormat ?: return
        closeNative()
        applyCachedFallbackState()
        fallbackSink.configure(
            inputFormat,
            configuredBufferSize,
            configuredOutputChannels
        )
        fallbackConfigured = true
        resetPlaybackCounters(keepPlayState = true)
        if (playing) {
            fallbackSink.play()
        }
        UsbExclusiveAudioPathTracker.updateConfigured(
            usingNative = false,
            fallbackReason = fallbackReason,
            inputFormat = inputFormatDescription(inputFormat)
        )
        UsbExclusiveAudioPathTracker.updatePlaying(playing, usingNative = false)
        NPLogger.d(
            "NERI-UsbExclusive",
            "configured system fallback: reason=${fallbackReason ?: "system_requested"}, format=${inputFormatDescription(inputFormat)}"
        )
    }

    private fun applyCachedFallbackState() {
        audioAttributes?.let(fallbackSink::setAudioAttributes)
        audioSessionId?.let(fallbackSink::setAudioSessionId)
        fallbackSink.setAuxEffectInfo(auxEffectInfo)
        fallbackSink.setPlaybackParameters(playbackParameters)
        fallbackSink.setSkipSilenceEnabled(skipSilenceEnabled)
        fallbackSink.setVolume(volume)
        if (preferredDeviceWasSet) {
            fallbackSink.setPreferredDevice(preferredDevice)
        }
        if (outputStreamOffsetWasSet) {
            fallbackSink.setOutputStreamOffsetUs(outputStreamOffsetUs)
        }
        if (tunnelingEnabled) {
            fallbackSink.enableTunnelingV21()
        } else {
            fallbackSink.disableTunneling()
        }
    }

    private fun nativeCompatibilityFailure(
        inputFormat: Format,
        outputChannels: IntArray?
    ): String? {
        UsbExclusiveAudioPathTracker.forcedSystemFallbackReason()?.let { return it }
        if (!isNativePcmFormat(inputFormat)) return "unsupported_input_format"
        if (outputChannels != null) return "channel_mapping_requires_system_processor"
        if (!hasDefaultPlaybackParameters(playbackParameters)) {
            return "playback_parameters_require_system_processor"
        }
        if (skipSilenceEnabled) return "skip_silence_requires_system_processor"
        if (tunnelingEnabled) return "tunneling_requires_system_audio_track"
        if (auxEffectInfo.effectId != AuxEffectInfo.NO_AUX_EFFECT_ID) {
            return "aux_effect_requires_system_audio_track"
        }
        val soundConfig = resolvePlaybackSoundConfigForEngine(
            baseConfig = PlayerManager.playbackSoundConfig,
            listenTogetherSyncPlaybackRate = PlayerManager.listenTogetherSyncPlaybackRate,
            usbExclusivePlaybackEnabled = true
        )
        if (soundConfig.equalizerEnabled) return "equalizer_requires_system_audio_session"
        if (soundConfig.loudnessGainMb > 0) return "loudness_requires_system_audio_session"
        if (
            abs(soundConfig.speed - 1f) > PARAMETER_EPSILON ||
            abs(soundConfig.pitch - 1f) > PARAMETER_EPSILON ||
            abs(PlayerManager.listenTogetherSyncPlaybackRate - 1f) > PARAMETER_EPSILON
        ) {
            return "playback_parameters_require_system_processor"
        }
        return null
    }

    private fun hasDefaultPlaybackParameters(parameters: PlaybackParameters): Boolean {
        return abs(parameters.speed - 1f) <= PARAMETER_EPSILON &&
            abs(parameters.pitch - 1f) <= PARAMETER_EPSILON
    }

    private fun isNativePcmFormat(format: Format): Boolean {
        return MimeTypes.AUDIO_RAW == format.sampleMimeType &&
            format.sampleRate > 0 &&
            format.channelCount > 0 &&
            format.channelCount <= 8 &&
            pcmFrameBytes(format.pcmEncoding, format.channelCount) > 0
    }

    private fun inputFormatDescription(format: Format): String {
        return "mime=${format.sampleMimeType ?: "unknown"} sampleRate=${format.sampleRate} " +
            "channels=${format.channelCount} encoding=${format.pcmEncoding}"
    }

    private fun writeNative(buffer: ByteBuffer, size: Int): Int {
        if (nativeHandle == 0L || size <= 0) return 0
        if (buffer.isDirect) {
            return UsbExclusiveSessionController.writePlayerPcm(
                handle = nativeHandle,
                buffer = buffer,
                offset = buffer.position(),
                size = size,
                volume = effectiveNativeVolume()
            )
        }

        val scratch = directScratch?.takeIf { it.capacity() >= size }
            ?: ByteBuffer.allocateDirect(size).also { directScratch = it }
        val duplicate = buffer.duplicate()
        duplicate.limit(duplicate.position() + size)
        scratch.clear()
        scratch.put(duplicate)
        scratch.flip()
        return UsbExclusiveSessionController.writePlayerPcm(
            handle = nativeHandle,
            buffer = scratch,
            offset = 0,
            size = size,
            volume = effectiveNativeVolume()
        )
    }

    private fun effectiveNativeVolume(): Float {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStreamVolumePollMs > STREAM_VOLUME_POLL_INTERVAL_MS) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            cachedStreamVolume = audioManager?.musicStreamVolumeScalar() ?: 1f
            lastStreamVolumePollMs = now
        }
        val effectiveVolume = (volume * cachedStreamVolume).coerceIn(0f, 1f)
        UsbExclusiveAudioPathTracker.updateVolume(effectiveVolume)
        return effectiveVolume
    }

    private fun AudioManager.musicStreamVolumeScalar(): Float {
        val maxVolume = getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            .div(maxVolume.toFloat())
            .coerceIn(0f, 1f)
    }

    private fun currentNativePositionUs(): Long {
        val nativeOutputSampleRate = UsbExclusiveSessionController.state.value.outputSampleRate
        if (startMediaTimeUs != C.TIME_UNSET && nativeOutputSampleRate > 0 && nativeHandle != 0L) {
            val completedFrames = UsbExclusiveSessionController.completedAudioFrames(nativeHandle)
            if (completedFrames < completedFramesAtTimelineStart) {
                return lastPositionUs
            }
            return startMediaTimeUs +
                (completedFrames - completedFramesAtTimelineStart) * 1_000_000L /
                nativeOutputSampleRate
        }
        if (!playing || !nativeTransportStarted || playAnchorElapsedNs == 0L) {
            return playAnchorPositionUs
        }
        val elapsedUs = (SystemClock.elapsedRealtimeNanos() - playAnchorElapsedNs) / 1000L
        return playAnchorPositionUs + elapsedUs
    }

    private fun resetPlaybackCounters(keepPlayState: Boolean) {
        inputEnded = false
        if (!keepPlayState) {
            playing = false
        }
        nativeTransportStarted = false
        nativeHasQueuedPcm = false
        startMediaTimeUs = C.TIME_UNSET
        writtenFrames = 0L
        writtenFramesAtTimelineStart = 0L
        completedFramesAtTimelineStart = 0L
        playAnchorPositionUs = 0L
        playAnchorElapsedNs = 0L
        lastPositionUs = 0L
        discontinuityExpected = true
    }

    private fun closeNative() {
        if (nativeHandle != 0L) {
            UsbExclusiveSessionController.closePlayerPcm(nativeHandle)
            nativeHandle = 0L
        }
        usingNative = false
        nativeTransportStarted = false
        nativeHasQueuedPcm = false
    }

    private fun startNativeTransportIfReady(): Boolean {
        if (!usingNative || !playing || !nativeHasQueuedPcm) return true
        if (nativeTransportStarted) return true
        val started = UsbExclusiveSessionController.playPlayerPcm(nativeHandle)
        if (!started) return false
        playAnchorElapsedNs = SystemClock.elapsedRealtimeNanos()
        nativeTransportStarted = true
        listener?.onPositionAdvancing(System.currentTimeMillis())
        UsbExclusiveAudioPathTracker.updatePlaying(playing = true, usingNative = true)
        return true
    }

    private fun pauseNativeTransport() {
        if (nativeHandle != 0L) {
            val paused = UsbExclusiveSessionController.pausePlayerPcm(nativeHandle)
            if (!paused && !failoverRequested) {
                requestSystemFailover("native_pause_failed")
            }
        }
        nativeTransportStarted = false
        playAnchorElapsedNs = 0L
        UsbExclusiveAudioPathTracker.updateNativePaused(
            paused = usingNative,
            sinkPlaying = playing
        )
    }

    private fun requestSystemFailover(reason: String) {
        if (failoverRequested) return
        failoverRequested = true
        UsbExclusiveAudioPathTracker.forceSystemFallback(reason)
        if (nativeHandle != 0L) {
            UsbExclusiveSessionController.pausePlayerPcm(nativeHandle)
        }
        nativeTransportStarted = false
        PlayerManager.scheduleUsbAudioSinkReconfiguration(reason)
        PlayerManager.scheduleUsbExclusiveTransportRecovery(reason)
        NPLogger.e("NERI-UsbExclusive", "requesting controlled system fallback: $reason")
    }

    private fun isFatalNativeRuntime(runtimeReport: String): Boolean {
        if (runtimeReport.contains("transportFailed=true")) return true
        val lastError = runtimeReport.substringAfter("lastError=", missingDelimiterValue = "none")
            .substringBefore(' ')
        return lastError != "none" && lastError.isNotBlank()
    }

    private fun shouldRetryNativeFailure(reason: String): Boolean {
        return !reason.startsWith("sample_rate_unsupported") &&
            !reason.startsWith("bit_depth_unsupported") &&
            !reason.startsWith("channel_count_unsupported") &&
            !reason.startsWith("unsupported_input") &&
            !reason.startsWith("no_") &&
            !reason.contains("permission", ignoreCase = true)
    }

    private fun framesToDurationUs(frames: Long): Long {
        return if (sampleRate > 0) frames * 1_000_000L / sampleRate else 0L
    }

    private fun pcmFrameBytes(encoding: Int, channels: Int): Int {
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN,
            C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
        return bytesPerSample * max(0, channels)
    }
}
