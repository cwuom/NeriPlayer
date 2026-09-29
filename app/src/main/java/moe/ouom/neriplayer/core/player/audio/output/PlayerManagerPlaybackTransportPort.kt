package moe.ouom.neriplayer.core.player.audio.output

import moe.ouom.neriplayer.core.player.usb.transport.hasHealthyTransport

import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import kotlinx.coroutines.Job
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.audio.focus.StartupAudioFocusController
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDebugLogger
import moe.ouom.neriplayer.core.player.playback.pauseImpl
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.transport.usbRuntimeMetrics
import moe.ouom.neriplayer.data.model.SongItem

internal object PlayerManagerPlaybackTransportPort : PlaybackTransportPort {
    override fun ensureInitialized() = PlayerManager.ensureInitialized()

    override fun transportSnapshot(includePosition: Boolean, includeRestore: Boolean): PlaybackTransportSnapshot {
        val manager = PlayerManager
        val song = manager.currentSongFlow.value
        return PlaybackTransportSnapshot(
            initialized = manager.initialized,
            hasCurrentSong = hasCurrentSong(song),
            resumeRequested = manager.resumePlaybackRequested,
            playJobActive = jobIsActive(manager.playJob),
            pendingPauseJobActive = jobIsActive(manager.pendingPauseJob),
            playWhenReady = manager.playWhenReadyFlow.value,
            isPlaying = manager.isPlayingFlow.value,
            playerPlaybackState = manager.playerPlaybackStateFlow.value,
            currentPositionMs = readPositionIfRequested(includePosition),
            restoredPlaybackCanAutoResume = canResumeRestoredPlayback(includeRestore, song),
            playerInitialized = manager.isPlayerInitialized()
        )
    }

    private fun jobIsActive(job: Job?): Boolean = job?.isActive == true

    private fun hasCurrentSong(song: SongItem?): Boolean = song != null

    private fun canResumeRestoredPlayback(includeRestore: Boolean, song: SongItem?): Boolean =
        restoreRequested(includeRestore) && isRestorableSong(song)

    private fun restoreRequested(includeRestore: Boolean): Boolean =
        if (includeRestore) PlayerManager.restoredShouldResumePlayback else false

    private fun readPositionIfRequested(includePosition: Boolean): Long =
        if (includePosition) runCatching {
            PlayerManager.player.currentPosition.coerceAtLeast(0L)
        }.getOrDefault(Long.MAX_VALUE) else Long.MAX_VALUE

    private fun isRestorableSong(song: SongItem?): Boolean {
        val candidate = song ?: return false
        return isRestorableCandidate(candidate)
    }

    private fun isRestorableCandidate(song: SongItem): Boolean {
        if (!PlayerManager.isLocalSong(song)) return true
        return PlayerManager.isRestorableLocalSong(song)
    }

    override fun usbFocusSnapshot(includeHealth: Boolean, includeOpenGate: Boolean): UsbFocusSnapshot {
        val path = UsbExclusiveAudioPathTracker.state.value
        val native = UsbExclusiveSessionController.state.value
        return UsbFocusSnapshot(
            enabled = PlayerManager.usbExclusivePlaybackEnabled,
            mixedPlayback = PlayerManager.allowMixedPlaybackEnabled,
            effectiveNativePath = path.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB,
            requestedNativePath = path.requestedPath == UsbExclusiveAudioPathState.REQUESTED_NATIVE_USB,
            sinkPlaying = path.sinkPlaying,
            fallbackReason = path.fallbackReason,
            nativeSource = native.source,
            nativeOpened = native.opened,
            nativeStreaming = native.streaming,
            nativeTransitioning = native.transitioning,
            nativeTransportHealthy = readTransportHealthIfRequested(includeHealth, native.runtimeReport),
            openGateReason = readOpenGateIfRequested(includeOpenGate)
        )
    }

    private fun readTransportHealthIfRequested(includeHealth: Boolean, runtimeReport: String): Boolean {
        if (!includeHealth) return false
        return runtimeReport.usbRuntimeMetrics().hasHealthyTransport
    }

    private fun readOpenGateIfRequested(includeOpenGate: Boolean): String? =
        if (includeOpenGate) UsbExclusiveSessionController.playerPcmOpenGateReason() else null

    override fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    override fun logFocusPolicy(usbEnabled: Boolean, mixedPlayback: Boolean, handleFocus: Boolean) {
        UsbExclusiveDebugLogger.logFocusPolicy(usbEnabled, mixedPlayback, handleFocus)
    }

    override fun setAudioAttributes(handleFocus: Boolean) {
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        PlayerManager.player.setAudioAttributes(attributes, handleFocus)
    }

    override fun updateForegroundFocusGuard(
        enabled: Boolean,
        usbEnabled: Boolean,
        mixedPlayback: Boolean,
        transportActive: Boolean
    ) {
        StartupAudioFocusController.updateForForeground(
            context = PlayerManager.application,
            enabled = enabled,
            allowMixedPlayback = mixedPlayback,
            usbExclusivePlayback = usbEnabled,
            usbExclusiveNativeActive = enabled,
            transportActive = transportActive,
            reason = "apply_audio_focus_policy"
        )
    }

    override fun pauseForUsbFocusLoss(change: Int) {
        PlayerManager.pauseImpl(
            forcePersist = false,
            commandSource = PlaybackCommandSource.REMOTE_SYNC,
            allowFadeOut = false,
            debugReason = "usb_focus_loss:$change"
        )
    }

    override fun setWakeMode(wakeMode: Int) {
        PlayerManager.player.setWakeMode(wakeMode)
    }
}
