package moe.ouom.neriplayer.core.player.dlna.session

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.VolumeProvider
import android.media.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveBackgroundAudioAnchorSpec
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveBackgroundAudioAnchorTransferMode
import moe.ouom.neriplayer.core.player.policy.usb.isUsbExclusiveBackgroundAudioAnchorBuiltInRoute
import moe.ouom.neriplayer.core.player.policy.usb.shouldWriteUsbExclusiveBackgroundAudioAnchorCarrier
import moe.ouom.neriplayer.core.player.policy.usb.usbExclusiveBackgroundAudioAnchorCarrier
import moe.ouom.neriplayer.core.player.policy.usb.usbExclusiveBackgroundAudioAnchorSpecs
import moe.ouom.neriplayer.core.player.service.usb.UsbExclusiveKeepAliveServiceHost
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaCastState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps DLNA remote playback alive across aggressive OEM screen-off / Doze policies
 * (such as Samsung One UI MARs / Freecess on Galaxy Z Fold6):
 * 1. Switches MediaSession between PLAYBACK_TYPE_REMOTE (VolumeProvider) and PLAYBACK_TYPE_LOCAL.
 * 2. Maintains a dedicated background media AudioTrack anchor (inaudible 24 kHz Nyquist carrier on
 *    built-in speaker, pure silence on Bluetooth/headset, without requesting AudioFocus) while the
 *    app is backgrounded or screen-locked during active DLNA playback.
 * 3. Periodically renews timed WakeLock/WifiLock leases and reasserts mediaPlayback foreground state.
 */
internal class DlnaServiceKeepAliveController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: UsbExclusiveKeepAliveServiceHost
) {
    companion object {
        private const val TAG = "NERI-DlnaKeepAlive"
        private const val KEEPALIVE_INTERVAL_MS = 5_000L
        private const val VOLUME_STEP_PERCENT = 5
    }

    @Volatile
    private var screenInteractive = true
    private var loopJob: Job? = null
    private var remoteSessionConfigured = false
    private var remoteVolumeControlMode = -1
    private var remoteVolumeProvider: VolumeProvider? = null
    private val anchor = DlnaBackgroundAudioAnchor()

    fun onScreenInteractiveChanged(interactive: Boolean) {
        screenInteractive = interactive
        if (interactive) {
            DlnaCastManager.nudgePolling()
        }
        update(if (interactive) "screen_on" else "screen_off")
        if (!interactive && shouldHoldKeepAlive()) {
            DlnaCastManager.renewCastLocks(forceRenew = true)
            val reasserted = if (host.foregroundStarted()) {
                host.reassertForeground("dlna_screen_off")
            } else {
                host.ensureForeground()
            }
            if (reasserted) {
                updateAnchor("dlna_screen_off_reassert")
            }
        }
    }

    fun onCastStateChanged(
        state: DlnaCastState,
        session: MediaSession?,
        localAudioAttributes: AudioAttributes
    ) {
        syncMediaSessionPlaybackType(state, session, localAudioAttributes)
        update("dlna_cast_state")
    }

    fun update(reason: String) {
        if (!shouldHoldKeepAlive()) {
            anchor.stop("inactive:$reason")
            loopJob?.cancel()
            loopJob = null
            return
        }
        updateAnchor(reason)
        ensureLoop()
    }

    fun cancelLoop() {
        loopJob?.cancel()
        loopJob = null
        anchor.stop("cancel_loop")
    }

    fun close() {
        cancelLoop()
        anchor.stop("service_destroy")
        remoteVolumeProvider = null
        remoteSessionConfigured = false
        remoteVolumeControlMode = -1
    }

    private fun shouldHoldKeepAlive(): Boolean =
        DlnaCastManager.shouldHoldBackgroundKeepAlive() &&
            !PlayerManager.isUsbExclusivePlaybackActiveForForegroundService()

    private fun isBackgroundOrScreenOff(): Boolean =
        !PlayerManager.usbExclusiveAppInForeground || !screenInteractive

    private fun updateAnchor(reason: String) {
        val shouldRunAnchor = shouldHoldKeepAlive() &&
            isBackgroundOrScreenOff() &&
            host.foregroundStarted()
        if (shouldRunAnchor) {
            anchor.start(context, reason)
        } else {
            anchor.stop("idle:$reason")
        }
    }

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (!shouldHoldKeepAlive()) {
                    anchor.stop("loop_inactive")
                    loopJob = null
                    return@launch
                }
                DlnaCastManager.renewCastLocks()
                if (isBackgroundOrScreenOff()) {
                    val ready = if (host.foregroundStarted()) {
                        host.reassertForeground("dlna_keepalive")
                    } else {
                        host.ensureForeground()
                    }
                    if (!ready) {
                        host.onForegroundFailure("dlna_keepalive")
                        return@launch
                    }
                    updateAnchor("dlna_keepalive_tick")
                    host.updatePlaybackPresentation()
                } else {
                    anchor.stop("foreground_active")
                }
            }
        }
    }

    private fun syncMediaSessionPlaybackType(
        state: DlnaCastState,
        session: MediaSession?,
        localAudioAttributes: AudioAttributes
    ) {
        if (state.isCastingActive) {
            if (session == null) return
            val controlType = if (state.volumeSupported) {
                VolumeProvider.VOLUME_CONTROL_ABSOLUTE
            } else {
                VolumeProvider.VOLUME_CONTROL_FIXED
            }
            val currentVol = (state.remoteVolume ?: 50).coerceIn(0, 100)
            if (!remoteSessionConfigured ||
                remoteVolumeControlMode != controlType ||
                remoteVolumeProvider == null
            ) {
                val provider = object : VolumeProvider(controlType, 100, currentVol) {
                    override fun onSetVolumeTo(volume: Int) {
                        val clamped = DlnaCastStateMachine.clampRemoteVolume(volume)
                        currentVolume = clamped
                        DlnaCastManager.setRemoteVolume(clamped)
                    }

                    override fun onAdjustVolume(direction: Int) {
                        if (direction == 0) return
                        val base = DlnaCastManager.castStateFlow.value.remoteVolume ?: currentVolume
                        val next = DlnaCastStateMachine.clampRemoteVolume(
                            base + direction * VOLUME_STEP_PERCENT
                        )
                        currentVolume = next
                        DlnaCastManager.setRemoteVolume(next)
                    }
                }
                runCatching {
                    session.setPlaybackToRemote(provider)
                    remoteVolumeProvider = provider
                    remoteSessionConfigured = true
                    remoteVolumeControlMode = controlType
                }.onFailure { err ->
                    NPLogger.w(TAG, "setPlaybackToRemote failed: ${err.message}")
                }
            } else if (remoteVolumeProvider?.currentVolume != currentVol) {
                runCatching { remoteVolumeProvider?.currentVolume = currentVol }
            }
        } else if (remoteSessionConfigured) {
            remoteSessionConfigured = false
            remoteVolumeControlMode = -1
            remoteVolumeProvider = null
            if (session != null) {
                runCatching { session.setPlaybackToLocal(localAudioAttributes) }
                    .onFailure { err ->
                        NPLogger.w(TAG, "setPlaybackToLocal restore failed: ${err.message}")
                    }
            }
        }
    }
}

private class DlnaBackgroundAudioAnchor {
    companion object {
        private const val TAG = "NERI-DlnaAnchor"
        private const val BYTES_PER_SAMPLE = 2
        private const val STREAM_BUFFER_MULTIPLIER = 2
    }

    private class ActiveTrack(
        val track: AudioTrack,
        val spec: UsbExclusiveBackgroundAudioAnchorSpec,
        val silence: ByteArray,
        val carrier: ByteArray
    ) {
        var writerRunning: AtomicBoolean? = null
        var writerThread: Thread? = null
    }

    private val lock = Any()
    private var active: ActiveTrack? = null

    fun start(context: Context, reason: String): Boolean {
        val _unused = context.applicationContext
        synchronized(lock) {
            val existing = active
            if (existing != null && existing.track.state == AudioTrack.STATE_INITIALIZED) {
                return ensurePlaying(existing, reason)
            }
            releaseLocked("recreate:$reason")
            val created = createFirstSupportedTrack(reason) ?: return false
            active = created
            return ensurePlaying(created, reason)
        }
    }

    fun stop(reason: String) {
        synchronized(lock) {
            releaseLocked(reason)
        }
    }

    private fun createFirstSupportedTrack(reason: String): ActiveTrack? {
        for (spec in usbExclusiveBackgroundAudioAnchorSpecs()) {
            val candidate = createTrackForSpec(reason, spec)
            if (candidate != null) return candidate
        }
        return null
    }

    private fun createTrackForSpec(
        reason: String,
        spec: UsbExclusiveBackgroundAudioAnchorSpec
    ): ActiveTrack? {
        val channelMask = when (spec.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> return null
        }
        val requestedBytes = spec.bufferFrames * spec.channelCount * BYTES_PER_SAMPLE
        val bufferBytes = if (spec.transferMode == UsbExclusiveBackgroundAudioAnchorTransferMode.StaticLoop) {
            requestedBytes
        } else {
            val minBytes = runCatching {
                AudioTrack.getMinBufferSize(spec.sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            }.getOrDefault(0).coerceAtLeast(0)
            maxOf(requestedBytes, minBytes * STREAM_BUFFER_MULTIPLIER)
        }
        val transferMode = when (spec.transferMode) {
            UsbExclusiveBackgroundAudioAnchorTransferMode.StaticLoop -> AudioTrack.MODE_STATIC
            UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming -> AudioTrack.MODE_STREAM
        }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(spec.sampleRateHz)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(transferMode)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }.getOrNull() ?: return null

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return null
        }
        val silence = ByteArray(bufferBytes)
        val carrier = usbExclusiveBackgroundAudioAnchorCarrier(
            bufferBytes = bufferBytes,
            channelCount = spec.channelCount
        )
        if (spec.transferMode == UsbExclusiveBackgroundAudioAnchorTransferMode.StaticLoop) {
            val ok = runCatching {
                val written = track.write(silence, 0, silence.size)
                val frames = track.bufferSizeInFrames.coerceAtLeast(0)
                written == silence.size &&
                    frames > 0 &&
                    track.setLoopPoints(0, frames, -1) == AudioTrack.SUCCESS
            }.getOrDefault(false)
            if (!ok) {
                runCatching { track.release() }
                return null
            }
        }
        NPLogger.d(TAG, "prepared DLNA background anchor reason=$reason spec=${spec.name}")
        return ActiveTrack(track = track, spec = spec, silence = silence, carrier = carrier)
    }

    private fun ensurePlaying(holder: ActiveTrack, reason: String): Boolean {
        val playing = runCatching {
            if (holder.track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                holder.track.play()
            }
            holder.track.playState == AudioTrack.PLAYSTATE_PLAYING
        }.getOrDefault(false)
        if (!playing) {
            releaseLocked("play_failed:$reason")
            return false
        }
        if (holder.spec.transferMode == UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming &&
            holder.writerThread?.isAlive != true
        ) {
            val running = AtomicBoolean(true)
            holder.writerRunning = running
            holder.writerThread = Thread(
                {
                    while (running.get()) {
                        val routedType = runCatching { holder.track.routedDevice?.type }.getOrNull()
                        val useCarrier = shouldWriteUsbExclusiveBackgroundAudioAnchorCarrier(
                            transferMode = holder.spec.transferMode,
                            routedToBuiltInOutput = isUsbExclusiveBackgroundAudioAnchorBuiltInRoute(routedType),
                            routedToClaimedUsbOutput = false
                        )
                        val payload = if (useCarrier) holder.carrier else holder.silence
                        val written = runCatching {
                            holder.track.write(payload, 0, payload.size, AudioTrack.WRITE_BLOCKING)
                        }.getOrDefault(-1)
                        if (written < 0) break
                        if (written == 0) Thread.yield()
                    }
                    running.set(false)
                },
                "NeriDlnaAudioAnchor"
            ).apply {
                isDaemon = true
                start()
            }
        }
        return true
    }

    private fun releaseLocked(reason: String) {
        val current = active ?: return
        active = null
        current.writerRunning?.set(false)
        runCatching { current.track.pause() }
        runCatching { current.track.flush() }
        runCatching { current.track.stop() }
        runCatching { current.track.release() }
        NPLogger.d(TAG, "stopped DLNA background anchor reason=$reason")
    }
}
