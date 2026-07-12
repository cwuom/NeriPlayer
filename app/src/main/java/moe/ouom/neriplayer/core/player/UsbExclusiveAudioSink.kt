@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player

import android.content.Context
import android.database.ContentObserver
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
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
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.usb.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.UsbExclusivePcmWritePlanner
import moe.ouom.neriplayer.core.player.usb.UsbExclusiveRuntimeMetrics
import moe.ouom.neriplayer.core.player.usb.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.model.PlayerEvent
import moe.ouom.neriplayer.core.player.policy.isTransientUsbExclusiveOpenGate
import moe.ouom.neriplayer.core.player.policy.resolvePlaybackSoundConfigForEngine
import moe.ouom.neriplayer.core.player.policy.shouldSuppressSystemFallbackForUsbExclusiveFailure
import moe.ouom.neriplayer.core.player.usb.usbRuntimeMetrics
import moe.ouom.neriplayer.core.player.usb.valueAfter
import moe.ouom.neriplayer.util.NPLogger

@UnstableApi
internal class UsbExclusiveAudioSink(
    private val context: Context,
    private val fallbackSink: AudioSink,
    private val observeSystemVolume: Boolean = true
) : ForwardingAudioSink(fallbackSink) {
    private companion object {
        const val PARAMETER_EPSILON = 0.0001f
        const val NATIVE_TRANSIENT_FAILOVER_REOPEN_COOLDOWN_MS = 8_000L
        const val NATIVE_TRANSPORT_FAILOVER_REOPEN_COOLDOWN_MS = 8_000L
        const val SHORT_FOCUS_NATIVE_FAILURE_HOLD_MS = 700L
        const val SHORT_FOCUS_NATIVE_RESTART_MIN_INTERVAL_MS = 700L
        const val SHORT_FOCUS_NATIVE_RESTART_MAX_ATTEMPTS = 2
        const val NATIVE_OPEN_GATE_RETRY_MAX_ATTEMPTS = 2
        const val FIRST_COMPLETION_STALL_RECOVERY_MIN_MS = 220L
        const val FIRST_COMPLETION_STALL_RECOVERY_MAX_ATTEMPTS = 1
        const val NATIVE_START_PREROLL_MS = 300L
        const val DIRECT_SCRATCH_CAPACITY_BYTES = 256 * 1024
        const val NATIVE_BACKPRESSURE_REFRESH_INTERVAL_MS = 250L
        const val NATIVE_BACKPRESSURE_LOG_INTERVAL_MS = 2_000L
        const val NATIVE_BACKPRESSURE_PARK_MAX_US = 4_000L
        val audioThreadPriorityConfigured = ThreadLocal<Boolean>()
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    @Volatile
    private var cachedMusicVolumeFraction = 1f
    private val systemVolumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            applySystemVolumeChange()
        }
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
    private var shortFocusNativeFailureStartedAtMs = 0L
    private var shortFocusNativeRestartAttempts = 0
    private var lastShortFocusNativeRestartAtMs = 0L
    private var nativeTransportStartedAtMs = 0L
    private var firstCompletionStallRecoveryAttempts = 0
    private var nativeOpenGateRetryAttempts = 0
    private var suppressSystemFallbackPlayback = false
    private var suppressedSystemFallbackReason: String? = null
    private var lastSuppressedFallbackStopRequestAtMs = 0L
    private var lastNativeWriteFailureLogAtMs = 0L
    private var lastNativeBackpressureLogAtMs = 0L
    private var lastNativeBackpressureRefreshAtMs = 0L
    private var nativeBackpressureStartedAtMs = 0L
    private var lastReleaseBarrierHoldLogAtMs = 0L
    private var systemVolumeObserverRegistered = false
    private var lastReportedNativeVolume = Float.NaN
    private var lastSystemVolumeReadFailureLogAtMs = 0L

    init {
        cachedMusicVolumeFraction = readMusicVolumeFractionFromSystem()
        if (observeSystemVolume) {
            registerSystemVolumeObserver()
        }
    }

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
        NPLogger.d(
            "NERI-UsbExclusive",
            "configure audio sink: usbEnabled=${PlayerManager.usbExclusivePlaybackEnabled} " +
                "fallbackReason=${fallbackReason ?: "none"} " +
                "format=${inputFormatDescription(inputFormat)} bufferSize=$specifiedBufferSize"
        )
        if (!PlayerManager.usbExclusivePlaybackEnabled) {
            clearSystemFallbackPlaybackSuppression()
        }
        if (shouldHoldSystemAudioForUsbReleaseBarrier()) {
            holdSystemAudioUntilUsbRelease("configure")
            return
        }
        if (PlayerManager.usbExclusivePlaybackEnabled && fallbackReason == null) {
            prepareDirectScratch()
            configureNative(inputFormat)
        } else {
            configureFallback(fallbackReason)
        }
    }

    override fun play() {
        playing = true
        if (shouldHoldSystemAudioForUsbReleaseBarrier()) {
            playing = false
            holdSystemAudioUntilUsbRelease("play")
            return
        }
        if (usingNative && !PlayerManager.usbExclusivePlaybackEnabled) {
            switchToSystemFallback("usb_exclusive_disabled")
        }
        UsbExclusiveAudioPathTracker.updatePlaying(playing = true, usingNative = usingNative)
        if (!usingNative) {
            if (!fallbackConfigured) {
                NPLogger.d(
                    "NERI-UsbExclusive",
                    "defer system fallback play until AudioTrack is configured"
                )
                return
            }
            if (shouldSuppressSystemFallbackPlayback()) {
                playing = false
                UsbExclusiveAudioPathTracker.updatePlaying(playing = false, usingNative = false)
                fallbackSink.pause()
                requestStopAfterSuppressedFallback()
                return
            }
            fallbackSink.play()
            return
        }
        nativeHasQueuedPcm = UsbExclusiveSessionController.queuedPlayerFrames(nativeHandle) > 0L
        if (!startNativeTransportIfReady()) {
            requestSystemFailover("native_play_failed")
        }
    }

    override fun handleDiscontinuity() {
        if (!usingNative) {
            fallbackSink.handleDiscontinuity()
            return
        }
        if (nativeHandle != 0L) {
            UsbExclusiveSessionController.pausePlayerPcm(nativeHandle)
            if (!UsbExclusiveSessionController.flushPlayerPcm(nativeHandle)) {
                requestSystemFailover("native_discontinuity_flush_failed")
                return
            }
        }
        resetPlaybackCounters(keepPlayState = true)
        discontinuityExpected = true
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (shouldHoldSystemAudioForUsbReleaseBarrier()) {
            holdSystemAudioUntilUsbRelease("handle_buffer")
            return false
        }
        if (usingNative && !PlayerManager.usbExclusivePlaybackEnabled) {
            switchToSystemFallback("usb_exclusive_disabled")
        }
        if (!usingNative) {
            if (!fallbackConfigured) return false
            if (shouldSuppressSystemFallbackPlayback()) {
                requestStopAfterSuppressedFallback()
                return false
            }
            return fallbackSink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (!buffer.hasRemaining()) {
            return true
        }
        if (!playing && PlayerManager.shouldStartUsbExclusiveTransportFromSink()) {
            playing = true
            UsbExclusiveAudioPathTracker.updatePlaying(playing = true, usingNative = true)
        }
        ensureUrgentAudioThreadPriority()
        if (!resumeQueuedNativeTransportBeforeWrite()) {
            requestSystemFailover("native_resume_before_write_failed")
            return false
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
        val requestedWriteSize = nativeWriteSizeForAvailablePcmSpace(
            remaining = remaining,
            directBuffer = buffer.isDirect
        )
        val nativeVolume = effectiveNativeVolume()
        val written = if (requestedWriteSize > 0) {
            writeNative(buffer, requestedWriteSize, nativeVolume)
        } else {
            0
        }
        if (written <= 0) {
            val nowMs = SystemClock.elapsedRealtime()
            val runtimeReport = refreshRuntimeAfterStalledWrite(nowMs)
            val metrics = runtimeReport.usbRuntimeMetrics()
            if (shouldFlushIdleNativeQueueAfterStalledWrite(runtimeReport)) {
                flushIdleNativeQueueAfterStalledWrite(runtimeReport)
                return false
            }
            if (shouldRecoverNativeTransportBeforeFirstCompletion(runtimeReport, nowMs)) {
                firstCompletionStallRecoveryAttempts += 1
                requestNativeFailureStop(
                    reason = "native_transport_failed",
                    runtimeReport = "$runtimeReport earlyFirstCompletionRecovery=event_loop_first_completion_timeout"
                )
                return false
            }
            if (metrics.isBenignBackpressure) {
                recordBenignNativeBackpressure(
                    nowMs = nowMs,
                    pendingBytes = remaining,
                    attemptedBytes = requestedWriteSize,
                    runtimeReport = runtimeReport
                )
                return false
            }
            if (nowMs - lastNativeWriteFailureLogAtMs >= 1_000L) {
                lastNativeWriteFailureLogAtMs = nowMs
                NPLogger.w(
                    "NERI-UsbExclusive",
                    "native PCM write stalled: pending=$remaining requested=$requestedWriteSize " +
                        "written=$written " +
                        "playing=$playing transportStarted=$nativeTransportStarted " +
                        "hasQueued=$nativeHasQueuedPcm runtime=$runtimeReport"
                )
            }
            if (isFatalNativeRuntime(runtimeReport)) {
                requestSystemFailover("native_transport_failed")
            }
            return false
        }
        clearNativeBackpressureState()
        shortFocusNativeFailureStartedAtMs = 0L
        shortFocusNativeRestartAttempts = 0
        lastShortFocusNativeRestartAtMs = 0L
        nativeHasQueuedPcm = true

        if (playing && !startNativeTransportIfReady()) {
            requestSystemFailover("native_start_failed")
            return false
        }

        buffer.position(buffer.position() + written)
        writtenFrames += written / max(1, frameBytes)
        inputEnded = false
        original.limit(original.position() + written)
        // usb native 在后级才乘系统音量，这里同步视觉采样增益
        AudioReactive.handlePcmBuffer(original, effectiveVolume = nativeVolume)
        return written == remaining
    }

    override fun playToEndOfStream() {
        if (!usingNative) {
            fallbackSink.playToEndOfStream()
            return
        }
        inputEnded = true
        if (playing && !startNativeTransportIfReady(allowShortPreroll = true)) {
            requestSystemFailover("native_end_of_stream_start_failed")
        }
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
        if (audioDeviceInfo == null && usingNative) {
            if (PlayerManager.usbExclusivePlaybackEnabled) {
                NPLogger.d(
                    "NERI-UsbExclusive",
                    "ignore stale preferred device clear while native USB is enabled"
                )
                return
            }
            switchToSystemFallback("usb_exclusive_disabled")
            return
        }
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
        if (!usingNative) {
            lastReportedNativeVolume = Float.NaN
            UsbExclusiveAudioPathTracker.updateVolume(this.volume)
            fallbackSink.setVolume(this.volume)
        } else {
            applyEffectiveNativeVolume()
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
            nativeTransportStartedAtMs = 0L
        } else if (fallbackConfigured) {
            fallbackSink.flush()
        }
        resetPlaybackCounters(keepPlayState = true)
    }

    override fun reset() {
        clearSystemFallbackPlaybackSuppression()
        val retainedNative = retainNativeSessionForReset()
        if (!retainedNative) {
            closeNative()
        }
        if (fallbackConfigured) {
            fallbackSink.reset()
        }
        fallbackConfigured = false
        configuredFormat = null
        configuredOutputChannels = null
        configuredBufferSize = 0
        resetPlaybackCounters(keepPlayState = false)
        UsbExclusiveAudioPathTracker.updateConfigured(
            usingNative = retainedNative,
            fallbackReason = null,
            inputFormat = "none"
        )
        UsbExclusiveAudioPathTracker.updatePlaying(
            playing = false,
            usingNative = retainedNative
        )
    }

    override fun release() {
        clearSystemFallbackPlaybackSuppression()
        unregisterSystemVolumeObserver()
        closeNative()
        directScratch = null
        fallbackConfigured = false
        fallbackSink.release()
        UsbExclusiveAudioPathTracker.updateConfigured(
            usingNative = false,
            fallbackReason = null,
            inputFormat = "none"
        )
        UsbExclusiveAudioPathTracker.updatePlaying(playing = false, usingNative = false)
    }

    private fun configureNative(
        inputFormat: Format,
        resetShortFocusState: Boolean = true
    ) {
        clearSystemFallbackPlaybackSuppression()
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
            if (isTransientUsbExclusiveOpenGate(openError)) {
                holdSystemFallbackForTransientOpenGate(openError, inputFormat)
                return
            }
            PlayerManager.markUsbExclusivePlaybackPreparing(false, "native_open_failed:$openError")
            NPLogger.w(
                "NERI-UsbExclusive",
                "native player pcm unavailable, fallback to system AudioTrack: $openError"
            )
            postNativeFormatWarning(openError)
            val holdSystemFallback = shouldHoldSystemFallbackForNativeFailure(openError)
            if (holdSystemFallback) {
                suppressSystemFallbackAfterNativeFailure(openError)
            }
            configureFallback(openError)
            if (holdSystemFallback) {
                return
            }
            scheduleNativeOpenGateRetryIfNeeded(openError)
            if (shouldRetryNativeFailure(openError)) {
                PlayerManager.scheduleUsbExclusiveTransportRecovery(openError)
            }
            return
        }

        nativeHandle = openedHandle
        usingNative = true
        failoverRequested = false
        nativeOpenGateRetryAttempts = 0
        resetPlaybackCounters(
            keepPlayState = true,
            resetShortFocusState = resetShortFocusState
        )
        if (PlayerManager.shouldStartUsbExclusiveTransportFromSink()) {
            playing = true
        }
        applyEffectiveNativeVolume()
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
        val inputFormat = configuredFormat
        if (isTransientUsbExclusiveOpenGate(fallbackReason.orEmpty())) {
            holdSystemFallbackForTransientOpenGate(fallbackReason.orEmpty(), inputFormat)
            return
        }
        val suppressSystemFallback = shouldHoldSystemFallbackForNativeFailure(
            fallbackReason.orEmpty()
        )
        if (suppressSystemFallback) {
            suppressSystemFallbackAfterNativeFailure(fallbackReason.orEmpty())
        } else {
            clearSystemFallbackPlaybackSuppression()
        }
        if (suppressSystemFallback) {
            closeNative()
            if (fallbackConfigured) {
                fallbackSink.pause()
                fallbackSink.reset()
                fallbackConfigured = false
            }
            playing = false
            UsbExclusiveAudioPathTracker.updateConfigured(
                usingNative = false,
                fallbackReason = fallbackReason,
                inputFormat = inputFormat?.let(::inputFormatDescription) ?: "none"
            )
            UsbExclusiveAudioPathTracker.updatePlaying(playing = false, usingNative = false)
            PlayerManager.applyAudioFocusPolicy()
            return
        }
        if (inputFormat == null) {
            closeNative()
            fallbackConfigured = false
            UsbExclusiveAudioPathTracker.updateConfigured(
                usingNative = false,
                fallbackReason = fallbackReason,
                inputFormat = "none"
            )
            UsbExclusiveAudioPathTracker.updatePlaying(playing, usingNative = false)
            PlayerManager.applyAudioFocusPolicy()
            return
        }
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
        PlayerManager.applyAudioFocusPolicy()
        NPLogger.d(
            "NERI-UsbExclusive",
            "configured system fallback: reason=${fallbackReason ?: "system_requested"}, format=${inputFormatDescription(inputFormat)}"
        )
    }

    private fun holdSystemFallbackForTransientOpenGate(
        reason: String,
        inputFormat: Format?
    ) {
        closeNative()
        if (fallbackConfigured) {
            fallbackSink.pause()
            fallbackSink.reset()
            fallbackConfigured = false
        }
        playing = false
        nativeTransportStarted = false
        nativeHasQueuedPcm = false
        nativeTransportStartedAtMs = 0L
        UsbExclusiveAudioPathTracker.updateConfigured(
            usingNative = false,
            fallbackReason = reason,
            inputFormat = inputFormat?.let(::inputFormatDescription) ?: "none"
        )
        UsbExclusiveAudioPathTracker.updatePlaying(playing = false, usingNative = false)
        PlayerManager.markUsbExclusivePlaybackPreparing(true, "native_open_wait:$reason")
        scheduleNativeOpenGateRetryIfNeeded(reason)
        PlayerManager.applyAudioFocusPolicy()
        NPLogger.i(
            "NERI-UsbExclusive",
            "hold playback while native USB open gate is active: reason=$reason"
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

    private fun writeNative(buffer: ByteBuffer, size: Int, nativeVolume: Float): Int {
        if (nativeHandle == 0L || size <= 0) return 0
        publishNativeVolume(nativeVolume)
        if (buffer.isDirect) {
            return UsbExclusiveSessionController.writePlayerPcm(
                handle = nativeHandle,
                buffer = buffer,
                offset = buffer.position(),
                size = size,
                volume = nativeVolume
            )
        }

        val scratch = directScratch?.takeIf { it.capacity() >= size } ?: return 0
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
            volume = nativeVolume
        )
    }

    private fun nativeWriteSizeForAvailablePcmSpace(
        remaining: Int,
        directBuffer: Boolean
    ): Int {
        val cachedMetrics = UsbExclusiveSessionController.state.value.runtimeReport
            .usbRuntimeMetrics()
        var size = planNativeWriteSize(remaining, cachedMetrics)
        if (size <= 0 && cachedMetrics.hasPcmQueue && cachedMetrics.hasHealthyTransport) {
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastNativeBackpressureRefreshAtMs >= NATIVE_BACKPRESSURE_REFRESH_INTERVAL_MS) {
                UsbExclusiveSessionController.refreshRuntime(nativeHandle)
                lastNativeBackpressureRefreshAtMs = nowMs
                size = planNativeWriteSize(
                    remaining,
                    UsbExclusiveSessionController.state.value.runtimeReport.usbRuntimeMetrics()
                )
            }
        }
        if (!directBuffer) {
            size = size.coerceAtMost(directScratch?.capacity() ?: 0)
        }
        return alignToInputFrame(size)
    }

    private fun planNativeWriteSize(
        remaining: Int,
        metrics: UsbExclusiveRuntimeMetrics
    ): Int {
        return UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = remaining,
            inputSampleRate = sampleRate,
            inputFrameBytes = frameBytes,
            nativeTransportStarted = nativeTransportStarted,
            playing = playing,
            prerollMs = NATIVE_START_PREROLL_MS,
            metrics = metrics
        )
    }

    private fun alignToInputFrame(size: Int): Int {
        if (size <= 0 || frameBytes <= 1) return size.coerceAtLeast(0)
        return size - size % frameBytes
    }

    private fun prepareDirectScratch() {
        if (directScratch?.capacity() == DIRECT_SCRATCH_CAPACITY_BYTES) return
        directScratch = runCatching {
            ByteBuffer.allocateDirect(DIRECT_SCRATCH_CAPACITY_BYTES)
        }.onFailure { error ->
            NPLogger.w("NERI-UsbExclusive", "direct scratch allocation failed", error)
        }.getOrNull()
    }

    private fun refreshRuntimeAfterStalledWrite(nowMs: Long): String {
        val cachedReport = UsbExclusiveSessionController.state.value.runtimeReport
        val cachedMetrics = cachedReport.usbRuntimeMetrics()
        val shouldRefresh = !cachedMetrics.isBenignBackpressure ||
            nowMs - lastNativeBackpressureRefreshAtMs >= NATIVE_BACKPRESSURE_REFRESH_INTERVAL_MS
        if (!shouldRefresh) {
            return cachedReport
        }
        UsbExclusiveSessionController.refreshRuntime(nativeHandle)
        lastNativeBackpressureRefreshAtMs = nowMs
        return UsbExclusiveSessionController.state.value.runtimeReport
    }

    private fun recordBenignNativeBackpressure(
        nowMs: Long,
        pendingBytes: Int,
        attemptedBytes: Int,
        runtimeReport: String
    ) {
        if (nativeBackpressureStartedAtMs == 0L) {
            nativeBackpressureStartedAtMs = nowMs
        }
        val heldMs = nowMs - nativeBackpressureStartedAtMs
        if (nowMs - lastNativeBackpressureLogAtMs >= NATIVE_BACKPRESSURE_LOG_INTERVAL_MS) {
            lastNativeBackpressureLogAtMs = nowMs
            NPLogger.i(
                "NERI-UsbExclusive",
                "native PCM queue full, applying backpressure: pending=$pendingBytes " +
                    "requested=$attemptedBytes " +
                    "heldMs=$heldMs playing=$playing transportStarted=$nativeTransportStarted " +
                    "hasQueued=$nativeHasQueuedPcm runtime=$runtimeReport"
            )
        }
        parkForNativeBackpressure(runtimeReport)
    }

    private fun clearNativeBackpressureState() {
        nativeBackpressureStartedAtMs = 0L
        lastNativeBackpressureRefreshAtMs = 0L
    }

    private fun parkForNativeBackpressure(runtimeReport: String) {
        if (Thread.currentThread() === Looper.getMainLooper().thread) return
        val metrics = runtimeReport.usbRuntimeMetrics()
        val freeBytes = metrics.pcmFreeBytes ?: return
        if (freeBytes > 0L || sampleRate <= 0 || frameBytes <= 0) return
        val backpressureUs = metrics.pcmBackpressureCurrentMs
            ?.coerceAtLeast(0L)
            ?.times(1_000L)
            ?: 0L
        val oneFrameUs = 1_000_000L / sampleRate.coerceAtLeast(1)
        val parkUs = max(oneFrameUs, backpressureUs / 8L)
            .coerceIn(500L, NATIVE_BACKPRESSURE_PARK_MAX_US)
        LockSupport.parkNanos(parkUs * 1_000L)
    }

    private fun ensureUrgentAudioThreadPriority() {
        if (audioThreadPriorityConfigured.get() == true) return
        runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        }.onSuccess {
            NPLogger.d(
                "NERI-UsbExclusive",
                "USB writer thread priority configured tid=${Process.myTid()}"
            )
        }.onFailure { error ->
            NPLogger.w("NERI-UsbExclusive", "USB writer thread priority setup failed", error)
        }
        audioThreadPriorityConfigured.set(true)
    }

    private fun effectiveNativeVolume(): Float {
        return usbExclusiveEffectiveNativeVolume(
            playerVolume = volume,
            systemVolumeFraction = cachedMusicVolumeFraction
        )
    }

    private fun applyEffectiveNativeVolume(): Float {
        val effectiveVolume = effectiveNativeVolume()
        publishNativeVolume(effectiveVolume)
        if (usingNative && nativeHandle != 0L) {
            UsbExclusiveSessionController.setPlayerVolume(nativeHandle, effectiveVolume)
        }
        return effectiveVolume
    }

    private fun publishNativeVolume(effectiveVolume: Float) {
        if (
            lastReportedNativeVolume.isNaN() ||
            abs(lastReportedNativeVolume - effectiveVolume) > PARAMETER_EPSILON
        ) {
            lastReportedNativeVolume = effectiveVolume
            UsbExclusiveAudioPathTracker.updateVolume(effectiveVolume)
        }
    }

    private fun readMusicVolumeFractionFromSystem(): Float {
        val manager = audioManager ?: return 1f
        return runCatching {
            val minVolume = manager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val range = maxVolume - minVolume
            if (range <= 0) {
                1f
            } else {
                ((currentVolume - minVolume).toFloat() / range.toFloat()).coerceIn(0f, 1f)
            }
        }.getOrElse { error ->
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastSystemVolumeReadFailureLogAtMs >= 5_000L) {
                lastSystemVolumeReadFailureLogAtMs = nowMs
                NPLogger.w("NERI-UsbExclusive", "failed to read system media volume", error)
            }
            cachedMusicVolumeFraction
        }
    }

    private fun registerSystemVolumeObserver() {
        if (systemVolumeObserverRegistered) return
        runCatching {
            appContext.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                systemVolumeObserver
            )
            systemVolumeObserverRegistered = true
        }.onFailure { error ->
            NPLogger.w("NERI-UsbExclusive", "system volume observer registration failed", error)
        }
    }

    private fun unregisterSystemVolumeObserver() {
        if (!systemVolumeObserverRegistered) return
        runCatching {
            appContext.contentResolver.unregisterContentObserver(systemVolumeObserver)
        }.onFailure { error ->
            NPLogger.w("NERI-UsbExclusive", "system volume observer unregistration failed", error)
        }
        systemVolumeObserverRegistered = false
    }

    private fun applySystemVolumeChange() {
        cachedMusicVolumeFraction = readMusicVolumeFractionFromSystem()
        if (!usingNative || nativeHandle == 0L) return
        applyEffectiveNativeVolume()
    }

    private fun currentNativePositionUs(): Long {
        val clockPositionUs = if (playing && nativeTransportStarted && playAnchorElapsedNs != 0L) {
            playAnchorPositionUs +
                (SystemClock.elapsedRealtimeNanos() - playAnchorElapsedNs) / 1_000L
        } else {
            playAnchorPositionUs
        }
        val nativeOutputSampleRate = UsbExclusiveSessionController.state.value.outputSampleRate
        if (startMediaTimeUs != C.TIME_UNSET && nativeOutputSampleRate > 0 && nativeHandle != 0L) {
            val completedFrames = UsbExclusiveSessionController.completedAudioFrames(nativeHandle)
            if (completedFrames < completedFramesAtTimelineStart) {
                return max(lastPositionUs, clockPositionUs)
            }
            val completedPositionUs = startMediaTimeUs +
                (completedFrames - completedFramesAtTimelineStart) * 1_000_000L /
                nativeOutputSampleRate
            return max(completedPositionUs, clockPositionUs)
        }
        return clockPositionUs
    }

    private fun resetPlaybackCounters(
        keepPlayState: Boolean,
        resetShortFocusState: Boolean = true
    ) {
        inputEnded = false
        if (!keepPlayState) {
            playing = false
        }
        nativeTransportStarted = false
        nativeHasQueuedPcm = false
        nativeTransportStartedAtMs = 0L
        startMediaTimeUs = C.TIME_UNSET
        if (resetShortFocusState) {
            shortFocusNativeFailureStartedAtMs = 0L
            shortFocusNativeRestartAttempts = 0
            lastShortFocusNativeRestartAtMs = 0L
        }
        firstCompletionStallRecoveryAttempts = 0
        writtenFrames = 0L
        writtenFramesAtTimelineStart = 0L
        completedFramesAtTimelineStart = 0L
        playAnchorPositionUs = 0L
        playAnchorElapsedNs = 0L
        lastPositionUs = 0L
        discontinuityExpected = true
    }

    private fun closeNative(updateFocus: Boolean = true) {
        if (nativeHandle != 0L) {
            NPLogger.d(
                "NERI-UsbExclusive",
                "closing native USB path: handle=$nativeHandle playing=$playing " +
                    "transportStarted=$nativeTransportStarted queued=$nativeHasQueuedPcm"
            )
            UsbExclusiveSessionController.closePlayerPcm(nativeHandle)
            if (!PlayerManager.usbExclusivePlaybackEnabled) {
                UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(
                    PlayerManager.application,
                    "audio_sink_close_native"
                )
            }
            nativeHandle = 0L
        }
        usingNative = false
        nativeTransportStarted = false
        nativeHasQueuedPcm = false
        nativeTransportStartedAtMs = 0L
        if (updateFocus) {
            PlayerManager.applyAudioFocusPolicy()
        }
    }

    private fun retainNativeSessionForReset(): Boolean {
        if (!shouldRetainNativeSessionForReset()) return false
        val retainedHandle = nativeHandle
        val hadActiveNativePcm = nativeTransportStarted || nativeHasQueuedPcm
        if (hadActiveNativePcm) {
            pauseNativeTransport()
        }
        val flushed = UsbExclusiveSessionController.flushPlayerPcm(retainedHandle)
        if (!flushed || !shouldRetainNativeSessionForReset(retainedHandle)) {
            NPLogger.w(
                "NERI-UsbExclusive",
                "native USB session cannot be retained across reset: handle=$retainedHandle " +
                    "runtime=${UsbExclusiveSessionController.state.value.runtimeReport}"
            )
            return false
        }
        usingNative = true
        nativeTransportStarted = false
        nativeHasQueuedPcm = false
        nativeTransportStartedAtMs = 0L
        NPLogger.d(
            "NERI-UsbExclusive",
            "retained native USB session across sink reset: handle=$retainedHandle"
        )
        return true
    }

    private fun shouldRetainNativeSessionForReset(
        expectedHandle: Long = nativeHandle
    ): Boolean {
        if (!PlayerManager.usbExclusivePlaybackEnabled) return false
        if (expectedHandle == 0L || !usingNative) return false
        val state = UsbExclusiveSessionController.state.value
        if (state.handle != expectedHandle) return false
        if (state.source != "player_pcm" || !state.opened || state.transitioning) return false
        return !isFatalNativeRuntime(state.runtimeReport)
    }

    private fun startNativeTransportIfReady(allowShortPreroll: Boolean = false): Boolean {
        if (!usingNative || !playing || !nativeHasQueuedPcm) return true
        if (nativeTransportStarted) return true
        val queuedFrames = UsbExclusiveSessionController.queuedPlayerFrames(nativeHandle)
        val outputSampleRate = UsbExclusiveSessionController.state.value.outputSampleRate
            .takeIf { it > 0 }
            ?: sampleRate
        val requiredPrerollFrames = outputSampleRate * NATIVE_START_PREROLL_MS / 1_000L
        if (!allowShortPreroll && queuedFrames < requiredPrerollFrames) {
            return true
        }
        val focusGranted = StartupAudioFocusController.acquireUsbExclusiveTransportFocus(
            context = context,
            enabled = !PlayerManager.allowMixedPlaybackEnabled,
            reason = "native_transport_start"
        )
        if (!focusGranted) {
            NPLogger.w(
                "NERI-UsbExclusive",
                "native transport start blocked because exclusive audio focus was denied"
            )
            return false
        }
        val started = UsbExclusiveSessionController.playPlayerPcm(nativeHandle)
        if (!started) {
            StartupAudioFocusController.forceRelease("native_transport_start_failed")
            NPLogger.w(
                "NERI-UsbExclusive",
                "native transport start failed: handle=$nativeHandle " +
                    "queuedFrames=$queuedFrames requiredPrerollFrames=$requiredPrerollFrames " +
                    "runtime=${UsbExclusiveSessionController.state.value.runtimeReport}"
            )
            return false
        }
        shortFocusNativeFailureStartedAtMs = 0L
        playAnchorElapsedNs = SystemClock.elapsedRealtimeNanos()
        nativeTransportStartedAtMs = SystemClock.elapsedRealtime()
        nativeTransportStarted = true
        listener?.onPositionAdvancing(System.currentTimeMillis())
        UsbExclusiveAudioPathTracker.updatePlaying(playing = true, usingNative = true)
        PlayerManager.applyAudioFocusPolicy()
        PlayerManager.markUsbExclusivePlaybackPreparing(false, "native_transport_started")
        NPLogger.d(
            "NERI-UsbExclusive",
            "native transport started: handle=$nativeHandle queuedFrames=" +
                UsbExclusiveSessionController.queuedPlayerFrames(nativeHandle)
        )
        return true
    }

    private fun pauseNativeTransport() {
        NPLogger.d(
            "NERI-UsbExclusive",
            "pause native transport: handle=$nativeHandle playing=$playing " +
                "transportStarted=$nativeTransportStarted queued=$nativeHasQueuedPcm"
        )
        if (nativeHandle != 0L) {
            val paused = UsbExclusiveSessionController.pausePlayerPcm(nativeHandle)
            if (!paused && !failoverRequested) {
                requestSystemFailover("native_pause_failed")
            }
        }
        nativeTransportStarted = false
        nativeHasQueuedPcm = UsbExclusiveSessionController.queuedPlayerFrames(nativeHandle) > 0L
        playAnchorElapsedNs = 0L
        nativeTransportStartedAtMs = 0L
        UsbExclusiveAudioPathTracker.updateNativePaused(
            paused = usingNative,
            sinkPlaying = playing
        )
        StartupAudioFocusController.forceRelease("native_transport_paused")
        PlayerManager.applyAudioFocusPolicy()
    }

    private fun resumeQueuedNativeTransportBeforeWrite(): Boolean {
        if (!usingNative || !playing || nativeHandle == 0L) return true
        val nativeState = UsbExclusiveSessionController.state.value
        if (!nativeState.paused && nativeState.streaming && nativeTransportStarted) return true
        nativeTransportStarted = false
        nativeTransportStartedAtMs = 0L
        nativeHasQueuedPcm = UsbExclusiveSessionController.queuedPlayerFrames(nativeHandle) > 0L
        if (!nativeHasQueuedPcm) return true
        return startNativeTransportIfReady()
    }

    private fun requestSystemFailover(reason: String) {
        if (failoverRequested) return
        val runtimeReport = UsbExclusiveSessionController.state.value.runtimeReport
        if (reason.isHighRiskUsbTransferFailure() || runtimeReport.requiresNativeCloseForTransferFailure()) {
            requestNativeFailureStop(reason, runtimeReport)
            return
        }
        if (reason.isShortFocusNativeFailure() && PlayerManager.isRecentUsbExclusiveFocusDisruption()) {
            if (handleShortFocusNativeFailure(reason)) {
                return
            }
        }
        shortFocusNativeRestartAttempts = 0
        lastShortFocusNativeRestartAtMs = 0L
        shortFocusNativeFailureStartedAtMs = 0L
        failoverRequested = true
        UsbExclusiveSessionController.deferPlayerPcmOpen(
            reason = "failover:$reason",
            delayMs = if (reason.contains("transport", ignoreCase = true)) {
                NATIVE_TRANSPORT_FAILOVER_REOPEN_COOLDOWN_MS
            } else {
                NATIVE_TRANSIENT_FAILOVER_REOPEN_COOLDOWN_MS
            }
        )
        if (shouldHoldSystemFallbackForNativeFailure(reason)) {
            suppressSystemFallbackAfterNativeFailure(reason)
        }
        switchToSystemFallback(reason)
        if (reason.shouldScheduleNativeRecoveryAfterFailover()) {
            PlayerManager.scheduleUsbExclusiveTransportRecovery(reason)
        }
        NPLogger.e(
            "NERI-UsbExclusive",
            "requesting controlled system fallback: reason=$reason " +
                "playing=$playing usingNative=$usingNative handle=$nativeHandle " +
                "transportStarted=$nativeTransportStarted queued=$nativeHasQueuedPcm " +
                "runtime=${UsbExclusiveSessionController.state.value.runtimeReport}"
        )
    }

    private fun handleShortFocusNativeFailure(reason: String): Boolean {
        val nowMs = SystemClock.elapsedRealtime()
        val runtimeReport = UsbExclusiveSessionController.state.value.runtimeReport
        if (reason.isHighRiskUsbTransferFailure() || runtimeReport.requiresNativeCloseForTransferFailure()) {
            NPLogger.w(
                "NERI-UsbExclusive",
                "short focus disruption reached USB transfer failure, close native path: " +
                    "reason=$reason runtime=$runtimeReport"
            )
            return false
        }
        if (shortFocusNativeFailureStartedAtMs == 0L) {
            shortFocusNativeFailureStartedAtMs = nowMs
        }
        val heldMs = nowMs - shortFocusNativeFailureStartedAtMs
        val canRestart = nativeHandle != 0L &&
            !reason.isHighRiskUsbTransferFailure() &&
            shortFocusNativeRestartAttempts < SHORT_FOCUS_NATIVE_RESTART_MAX_ATTEMPTS &&
            nowMs - lastShortFocusNativeRestartAtMs >= SHORT_FOCUS_NATIVE_RESTART_MIN_INTERVAL_MS
        if (canRestart) {
            shortFocusNativeRestartAttempts += 1
            lastShortFocusNativeRestartAtMs = nowMs
            val restarted = restartNativeTransportForShortDisruption(reason)
            if (restarted) {
                NPLogger.w(
                    "NERI-UsbExclusive",
                    "restarted native USB transport after short disruption: reason=$reason attempt=$shortFocusNativeRestartAttempts"
                )
                return true
            }
        }
        if (heldMs <= SHORT_FOCUS_NATIVE_FAILURE_HOLD_MS) {
            NPLogger.w(
                "NERI-UsbExclusive",
                "hold native USB path during short focus disruption: reason=$reason heldMs=$heldMs"
            )
            return true
        }
        NPLogger.w(
            "NERI-UsbExclusive",
            "short focus disruption did not recover, switch to controlled fallback: reason=$reason heldMs=$heldMs attempts=$shortFocusNativeRestartAttempts"
        )
        return false
    }

    private fun restartNativeTransportForShortDisruption(reason: String): Boolean {
        if (nativeHandle == 0L || !usingNative) return false
        val runtimeReport = UsbExclusiveSessionController.state.value.runtimeReport
        if (runtimeReport.requiresNativeReopenForShortDisruption()) {
            NPLogger.w(
                "NERI-UsbExclusive",
                "native USB reopen suppressed during short disruption: reason=$reason " +
                    "runtime=$runtimeReport"
            )
            return false
        }
        val flushed = UsbExclusiveSessionController.flushPlayerPcm(nativeHandle)
        if (!flushed) return false
        nativeTransportStarted = false
        nativeHasQueuedPcm = false
        nativeTransportStartedAtMs = 0L
        resetPlaybackCounters(keepPlayState = true, resetShortFocusState = false)
        UsbExclusiveAudioPathTracker.updateNativePaused(
            paused = false,
            sinkPlaying = playing
        )
        PlayerManager.applyAudioFocusPolicy()
        NPLogger.d("NERI-UsbExclusive", "native transport flush for short disruption: $reason")
        return true
    }

    private fun requestNativeFailureStop(reason: String, runtimeReport: String) {
        PlayerManager.markUsbExclusivePlaybackPreparing(false, "native_failure:$reason")
        if (PlayerManager.tryRecoverUsbExclusivePlaybackAfterNativeTransferFailure(reason, runtimeReport)) {
            requestImmediateNativeRecoveryAfterTransferFailure(reason, runtimeReport)
            return
        }
        failoverRequested = true
        shortFocusNativeRestartAttempts = 0
        lastShortFocusNativeRestartAtMs = 0L
        shortFocusNativeFailureStartedAtMs = 0L
        suppressSystemFallbackAfterNativeFailure(reason)
        closeNative()
        UsbExclusiveSessionController.forceStopAllSessions("native_failure:$reason")
        UsbExclusiveAudioPathTracker.forceSystemFallback(reason)
        StartupAudioFocusController.forceRelease("native_failure:$reason")
        PlayerManager.stopPlaybackAfterUsbExclusiveNativeFailure(reason)
        NPLogger.e(
            "NERI-UsbExclusive",
            "stop native USB playback after transfer failure: reason=$reason runtime=$runtimeReport"
        )
    }

    private fun requestImmediateNativeRecoveryAfterTransferFailure(
        reason: String,
        runtimeReport: String
    ) {
        failoverRequested = true
        shortFocusNativeRestartAttempts = 0
        lastShortFocusNativeRestartAtMs = 0L
        shortFocusNativeFailureStartedAtMs = 0L
        nativeTransportStarted = false
        nativeHasQueuedPcm = false
        nativeTransportStartedAtMs = 0L
        val inputDescription = configuredFormat?.let(::inputFormatDescription) ?: "none"
        closeNative(updateFocus = false)
        UsbExclusiveSessionController.forceStopAllSessions(
            reason = "immediate_native_recovery:$reason",
            blockOpen = false
        )
        UsbExclusiveSessionController.clearRecoverablePlayerPcmOpenBlock(
            "immediate_native_recovery:$reason"
        )
        UsbExclusiveAudioPathTracker.updateConfigured(
            usingNative = false,
            fallbackReason = "immediate_native_recovery",
            inputFormat = inputDescription
        )
        UsbExclusiveAudioPathTracker.updatePlaying(playing = false, usingNative = false)
        NPLogger.w(
            "NERI-UsbExclusive",
            "recover native USB playback after transfer failure: reason=$reason runtime=$runtimeReport"
        )
    }

    private fun scheduleNativeOpenGateRetryIfNeeded(reason: String) {
        if (!PlayerManager.usbExclusivePlaybackEnabled) return
        if (!reason.shouldRetryAfterNativeOpenGate()) return
        if (nativeOpenGateRetryAttempts >= NATIVE_OPEN_GATE_RETRY_MAX_ATTEMPTS) return
        nativeOpenGateRetryAttempts += 1
        PlayerManager.scheduleUsbAudioSinkReconfiguration(
            reason = "usb_exclusive_open_gate_retry",
            allowWhilePlaybackActive = true,
            bypassCooldown = reason.shouldUseFastNativeOpenGateRetry()
        )
        NPLogger.i(
            "NERI-UsbExclusive",
            "scheduled native USB open gate retry: reason=$reason attempt=$nativeOpenGateRetryAttempts"
        )
    }

    private fun String.isShortFocusNativeFailure(): Boolean {
        return contains("pause", ignoreCase = true) ||
            contains("play", ignoreCase = true) ||
            contains("start", ignoreCase = true) ||
            contains("transport", ignoreCase = true)
    }

    private fun String.isHighRiskUsbTransferFailure(): Boolean {
        return contains("transport", ignoreCase = true) ||
            contains("LIBUSB_ERROR_IO", ignoreCase = true) ||
            contains("transfer_status=5", ignoreCase = true) ||
            contains("resubmit_failed", ignoreCase = true) ||
            contains("submit_failed", ignoreCase = true)
    }

    private fun String.shouldScheduleNativeRecoveryAfterFailover(): Boolean {
        if (startsWith("native_open_deferred")) return false
        if (startsWith("native_reopen_cooling_down")) return false
        if (contains("transport", ignoreCase = true)) return false
        if (contains("start", ignoreCase = true)) return false
        if (contains("play", ignoreCase = true)) return false
        return true
    }

    private fun String.shouldRetryAfterNativeOpenGate(): Boolean {
        return isTransientUsbExclusiveOpenGate(this) ||
            startsWith("native_reopen_cooling_down") ||
            (
                startsWith("native_open_deferred") &&
                    contains("usb_exclusive_disabled", ignoreCase = true)
                )
    }

    private fun String.shouldUseFastNativeOpenGateRetry(): Boolean {
        return contains("native_close_in_flight", ignoreCase = true) ||
            contains("native_transition_in_flight", ignoreCase = true)
    }

    private fun switchToSystemFallback(reason: String) {
        val reportedReason = reason.takeUnless { it == "usb_exclusive_disabled" }
        if (shouldHoldSystemFallbackForNativeFailure(reason)) {
            suppressSystemFallbackAfterNativeFailure(reason)
        } else {
            clearSystemFallbackPlaybackSuppression()
        }
        if (reportedReason == null) {
            UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
        } else {
            UsbExclusiveAudioPathTracker.forceSystemFallback(reportedReason)
        }
        nativeTransportStarted = false
        nativeTransportStartedAtMs = 0L
        runCatching {
            configureFallback(reportedReason)
        }.onFailure { error ->
            closeNative()
            UsbExclusiveAudioPathTracker.updateConfigured(
                usingNative = false,
                fallbackReason = reportedReason,
                inputFormat = configuredFormat?.let(::inputFormatDescription) ?: "none"
            )
            NPLogger.e(
                "NERI-UsbExclusive",
                "failed to switch native USB path to system fallback: reason=$reason",
                error
            )
        }
    }

    private fun shouldHoldSystemAudioForUsbReleaseBarrier(): Boolean {
        return !PlayerManager.usbExclusivePlaybackEnabled &&
            PlayerManager.usbExclusiveSystemAudioReleaseInProgress
    }

    private fun holdSystemAudioUntilUsbRelease(reason: String) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastReleaseBarrierHoldLogAtMs >= 500L) {
            lastReleaseBarrierHoldLogAtMs = nowMs
            NPLogger.i(
                "NERI-UsbExclusive",
                "hold system audio until USB release: reason=$reason usingNative=$usingNative " +
                    "fallbackConfigured=$fallbackConfigured handle=$nativeHandle"
            )
        }
        if (usingNative || nativeHandle != 0L) {
            closeNative()
        }
        if (fallbackConfigured) {
            runCatching { fallbackSink.pause() }
            runCatching { fallbackSink.reset() }
            fallbackConfigured = false
        }
        nativeTransportStarted = false
        nativeHasQueuedPcm = false
        nativeTransportStartedAtMs = 0L
        UsbExclusiveAudioPathTracker.updateConfigured(
            usingNative = false,
            fallbackReason = null,
            inputFormat = configuredFormat?.let(::inputFormatDescription) ?: "none"
        )
        UsbExclusiveAudioPathTracker.updatePlaying(
            playing = false,
            usingNative = false
        )
    }

    private fun shouldSuppressSystemFallbackPlayback(): Boolean {
        return suppressSystemFallbackPlayback && PlayerManager.usbExclusivePlaybackEnabled
    }

    private fun suppressSystemFallbackAfterNativeFailure(reason: String) {
        if (!PlayerManager.usbExclusivePlaybackEnabled) return
        if (suppressSystemFallbackPlayback && suppressedSystemFallbackReason == reason) return
        suppressSystemFallbackPlayback = true
        suppressedSystemFallbackReason = reason
        playing = false
        nativeTransportStarted = false
        nativeHasQueuedPcm = false
        nativeTransportStartedAtMs = 0L
        UsbExclusiveAudioPathTracker.updatePlaying(playing = false, usingNative = false)
        requestStopAfterSuppressedFallback()
    }

    private fun clearSystemFallbackPlaybackSuppression() {
        suppressSystemFallbackPlayback = false
        suppressedSystemFallbackReason = null
        lastSuppressedFallbackStopRequestAtMs = 0L
    }

    private fun requestStopAfterSuppressedFallback() {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastSuppressedFallbackStopRequestAtMs in 0L..800L) return
        lastSuppressedFallbackStopRequestAtMs = nowMs
        PlayerManager.stopPlaybackAfterUsbExclusiveNativeFailure(
            suppressedSystemFallbackReason ?: "native_fallback_suppressed"
        )
    }

    private fun isFatalNativeRuntime(runtimeReport: String): Boolean {
        val metrics = runtimeReport.usbRuntimeMetrics()
        if (metrics.isBenignBackpressure) return false
        if (runtimeReport.contains("transportFailed=true")) return true
        val lastError = metrics.lastError
        return lastError != "none" && lastError.isNotBlank()
    }

    private fun shouldFlushIdleNativeQueueAfterStalledWrite(runtimeReport: String): Boolean {
        if (playing) return false
        if (!runtimeReport.contains("source=player_pcm")) return false
        val metrics = runtimeReport.usbRuntimeMetrics()
        return metrics.running == false && metrics.transportFailed != true && metrics.isQueueFull
    }

    private fun shouldRecoverNativeTransportBeforeFirstCompletion(
        runtimeReport: String,
        nowMs: Long
    ): Boolean {
        if (!playing || !nativeTransportStarted) return false
        if (firstCompletionStallRecoveryAttempts >= FIRST_COMPLETION_STALL_RECOVERY_MAX_ATTEMPTS) {
            return false
        }
        if (nativeTransportStartedAtMs <= 0L) return false
        if (nowMs - nativeTransportStartedAtMs < FIRST_COMPLETION_STALL_RECOVERY_MIN_MS) {
            return false
        }
        val metrics = runtimeReport.usbRuntimeMetrics()
        if (!runtimeReport.contains("source=player_pcm")) return false
        if (runtimeReport.valueAfter("completedTransfers")?.toIntOrNull() != 0) return false
        if (runtimeReport.valueAfter("inFlight")?.toIntOrNull() == 0) return false
        if (metrics.running != true) return false
        if (metrics.transportFailed == true) return false
        if (!metrics.isQueueFull) return false
        return metrics.lastError == "none"
    }

    private fun flushIdleNativeQueueAfterStalledWrite(runtimeReport: String) {
        val flushed = nativeHandle != 0L && UsbExclusiveSessionController.flushPlayerPcm(nativeHandle)
        nativeTransportStarted = false
        nativeHasQueuedPcm = false
        nativeTransportStartedAtMs = 0L
        resetPlaybackCounters(keepPlayState = true)
        NPLogger.w(
            "NERI-UsbExclusive",
            "flush idle native USB queue after stalled write: flushed=$flushed runtime=$runtimeReport"
        )
    }

    private fun String.requiresNativeReopenForShortDisruption(): Boolean {
        return contains("transfer_status=5") ||
            contains("LIBUSB_ERROR_NO_DEVICE", ignoreCase = true) ||
            contains("LIBUSB_ERROR_IO", ignoreCase = true) ||
            contains("submit_failed", ignoreCase = true) ||
            contains("resubmit_failed", ignoreCase = true)
    }

    private fun String.requiresNativeCloseForTransferFailure(): Boolean {
        if (requiresNativeReopenForShortDisruption()) return true
        if (contains("transportFailed=true")) return true
        return contains("lastError=", ignoreCase = true) &&
            !contains("lastError=none", ignoreCase = true) &&
            (
                contains("inFlight=0", ignoreCase = true) ||
                    contains("LIBUSB", ignoreCase = true) ||
                    contains("transfer", ignoreCase = true)
                )
    }

    private fun shouldRetryNativeFailure(reason: String): Boolean {
        return !reason.startsWith("native_open_deferred") &&
            !reason.startsWith("native_reopen_cooling_down") &&
            !reason.startsWith("sample_rate_unsupported") &&
            !reason.startsWith("bit_depth_unsupported") &&
            !reason.startsWith("channel_count_unsupported") &&
            !reason.startsWith("unsupported_input") &&
            !reason.startsWith("no_") &&
            !reason.contains("permission", ignoreCase = true)
    }

    private fun shouldHoldSystemFallbackForNativeFailure(reason: String): Boolean {
        return shouldSuppressSystemFallbackForUsbExclusiveFailure(
            usbExclusivePlaybackEnabled = PlayerManager.usbExclusivePlaybackEnabled,
            reason = reason
        )
    }

    private fun postNativeFormatWarning(reason: String) {
        val messageResId = when {
            reason.startsWith("sample_rate_unsupported") ->
                R.string.settings_usb_exclusive_issue_sample_rate
            reason.startsWith("bit_depth_unsupported") ->
                R.string.settings_usb_exclusive_issue_bit_depth
            reason.startsWith("channel_count_unsupported") ->
                R.string.settings_usb_exclusive_issue_device
            else -> return
        }
        PlayerManager.postPlayerEvent(
            PlayerEvent.ShowError(PlayerManager.getLocalizedString(messageResId))
        )
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
