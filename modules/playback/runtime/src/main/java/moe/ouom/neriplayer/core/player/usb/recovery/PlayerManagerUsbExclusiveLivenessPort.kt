package moe.ouom.neriplayer.core.player.usb.recovery

import androidx.media3.common.Player
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.lifecycle.applyUsbExclusivePlaybackPolicy
import moe.ouom.neriplayer.core.player.lifecycle.recoverUsbExclusivePlaybackIfUnhealthy
import moe.ouom.neriplayer.core.player.playback.playImpl
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveWakeLock
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState
import moe.ouom.neriplayer.core.player.host.PlayerDependencies

internal object PlayerManagerUsbExclusiveLivenessPort : UsbExclusiveLivenessPort {
    override fun snapshot(): UsbExclusiveLivenessSnapshot {
        val playbackEnabled = PlayerManager.usbExclusivePlaybackEnabled
        val initialized = PlayerManager.isPlayerInitialized()
        val player = playerSnapshot(shouldReadPlayer(playbackEnabled, initialized))
        return UsbExclusiveLivenessSnapshot(
            playbackEnabled = playbackEnabled,
            playerInitialized = initialized,
            routeGeneration = PlayerManager.usbRouteTransitionOwner.generation,
            transportActive = PlayerManager.isTransportActiveWithoutInitialization(),
            playerPositionMs = player.positionMs,
            playerState = player.state,
            playWhenReady = player.playWhenReady,
            isPlaying = player.isPlaying
        )
    }

    private data class PlayerSnapshot(
        val positionMs: Long,
        val state: Int,
        val playWhenReady: Boolean,
        val isPlaying: Boolean
    )

    private fun shouldReadPlayer(playbackEnabled: Boolean, initialized: Boolean): Boolean {
        if (!playbackEnabled) return false
        return initialized
    }

    private fun playerSnapshot(initialized: Boolean): PlayerSnapshot {
        if (!initialized) return PlayerSnapshot(
            positionMs = -1L,
            state = Player.STATE_IDLE,
            playWhenReady = false,
            isPlaying = false
        )
        val player = PlayerManager.player
        return PlayerSnapshot(
            positionMs = readPlayerPosition(player),
            state = readPlayerState(player),
            playWhenReady = player.playWhenReady,
            isPlaying = player.isPlaying
        )
    }

    private fun readPlayerPosition(player: Player): Long =
        runCatching { player.currentPosition }.getOrDefault(-1L)

    private fun readPlayerState(player: Player): Int =
        runCatching { player.playbackState }.getOrDefault(Player.STATE_IDLE)

    override fun pathState(): UsbExclusiveAudioPathState = UsbExclusiveAudioPathTracker.state.value

    override fun nativeState(): UsbExclusiveNativeState = UsbExclusiveSessionController.state.value

    override fun refreshNativeState() {
        UsbExclusiveSessionController.refresh(PlayerManager.application)
    }

    override fun nativeOpenGateReason(): String? = UsbExclusiveSessionController.playerPcmOpenGateReason()

    override fun backgroundAuditContext(): String {
        val allowance = PlayerDependencies.presentation.backgroundBehaviorAllowance(PlayerManager.application)
        return "serviceInstance=${AudioPlayerService.isInstanceActiveForDiagnostics()} " +
            "serviceForeground=${AudioPlayerService.isForegroundActiveForDiagnostics()} " +
            "wakeLock=${UsbExclusiveWakeLock.isHeld()} " +
            "backgroundAllowance=battery=${allowance.ignoringBatteryOptimizations} " +
            "appOps=${allowance.backgroundAppOpsAllowed}"
    }

    override fun reassertServiceForeground(reason: String) {
        AudioPlayerService.reassertForegroundForActiveUsbExclusivePlayback(reason)
    }

    override fun updateBackgroundAnchor(reason: String) {
        AudioPlayerService.updateUsbExclusiveBackgroundAudioAnchor(reason)
    }

    override fun recoverRoute(reason: String, forceRecovery: Boolean): Boolean =
        PlayerManager.recoverUsbExclusivePlaybackIfUnhealthy(reason, forceRecovery)

    override fun applyAudioFocus() {
        PlayerManager.applyAudioFocusPolicyOnMainThread()
    }

    override fun applyPlaybackPolicy() {
        PlayerManager.applyUsbExclusivePlaybackPolicy(reconfigureAudioSink = false)
    }

    override fun restorePlaybackIntent(reason: String) {
        if (PlayerManager.isTransportActiveWithoutInitialization()) return
        NPLogger.i("NERI-UsbExclusive", "restore USB playback for foreground recovery: reason=$reason")
        PlayerManager.playImpl(
            commandSource = PlaybackCommandSource.LOCAL,
            bypassLoudVolumeWarning = true
        )
    }

    override fun markForegroundStable() {
        PlayerManager.usbRouteTransitionOwner.clearToggle()
        PlayerManager.markUsbExclusivePlaybackPreparing(false, "usb_foreground_stable")
    }

    override fun targetBufferDurationMs(foreground: Boolean): Int =
        PlayerManager.usbExclusivePreferences.bufferDurationMs(foreground)

    override fun reservedBufferDurationMs(): Int =
        PlayerManager.usbExclusivePreferences.reservedBufferDurationMs()

    override fun configureTransferWindow(durationMs: Int, foreground: Boolean): Boolean =
        UsbExclusiveSessionController.configureActivePlayerTransferWindow(durationMs, foreground)

    override fun configureBufferDuration(durationMs: Int, foreground: Boolean): Boolean =
        UsbExclusiveSessionController.configureActivePlayerBufferDuration(durationMs, foreground)
}
