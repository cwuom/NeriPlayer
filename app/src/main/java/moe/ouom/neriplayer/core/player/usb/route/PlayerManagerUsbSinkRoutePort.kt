package moe.ouom.neriplayer.core.player.usb.route

import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.playback.restorePlaybackAfterTransientAudioRouteLoss
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController

internal object PlayerManagerUsbSinkRoutePort : UsbSinkRoutePort {
    override fun routeGeneration(): Long = PlayerManager.usbRouteTransitionOwner.generation

    override fun snapshot(): UsbSinkRouteSnapshot {
        val media = PlayerManager.usbRoutePlayerSnapshot()
        return UsbSinkRouteSnapshot(
            routeGeneration = PlayerManager.usbRouteTransitionOwner.generation,
            enabled = PlayerManager.usbExclusivePlaybackEnabled,
            appInForeground = PlayerManager.usbExclusiveAppInForeground,
            playbackActive = PlayerManagerUsbPlaybackRoutePort.isPlaybackActiveForSwitch(),
            playerInitialized = media.initialized,
            hasMediaItem = media.hasMediaItem,
            mediaItemCount = media.mediaItemCount,
            mediaItemIndex = media.mediaItemIndex,
            positionMs = media.positionMs,
            resumePlayback = PlayerManagerUsbPlaybackRoutePort.shouldKeepPlaybackActiveForSwitch()
        )
    }

    override fun hasHealthyNativePlayerSession(): Boolean =
        UsbExclusiveSessionController.hasHealthyPlayerPcmSession()

    override fun stopCurrentSink(reason: String, resumePlayback: Boolean): Boolean =
        runCatching {
            PlayerManager.restorePlaybackAfterTransientAudioRouteLoss(reason = "usb_reconfigure:$reason")
            PlayerManager.player.playWhenReady = false
            PlayerManager.player.stop()
        }.onFailure { error ->
            runCatching { PlayerManager.player.playWhenReady = resumePlayback }
            NPLogger.e("NERI-UsbExclusive", "reconfigureAudioSink() failed to stop current sink: reason=$reason", error)
        }.isSuccess

    override fun prepareSink(
        mediaItemIndex: Int,
        positionMs: Long,
        resumePlayback: Boolean,
        reason: String
    ): Boolean = runCatching {
        PlayerManager.player.seekTo(mediaItemIndex, positionMs)
        PlayerManager.player.prepare()
        PlayerManager.updateResumePlaybackRequested(resumePlayback)
        PlayerManager.player.playWhenReady = resumePlayback
        resumePreparedSinkIfRequested(resumePlayback)
    }.onFailure { error ->
        runCatching { PlayerManager.player.playWhenReady = resumePlayback }
        NPLogger.e("NERI-UsbExclusive", "reconfigureAudioSink() failed: reason=$reason", error)
    }.isSuccess

    private fun resumePreparedSinkIfRequested(resumePlayback: Boolean) {
        if (resumePlayback) PlayerManager.player.play()
    }

    override fun finishToggleTransition(preparingReason: String) {
        PlayerManager.usbRouteTransitionOwner.clearToggle()
        PlayerManager.markUsbExclusivePlaybackPreparing(false, preparingReason)
    }

    override fun clearForcedSystemFallback() {
        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    }
}
