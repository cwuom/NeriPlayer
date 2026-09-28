@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.usb.route

import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.lifecycle.cancelUsbExclusiveRecovery
import moe.ouom.neriplayer.core.player.lifecycle.clearUsbExclusiveInterruptedPlaybackIntent
import moe.ouom.neriplayer.core.player.lifecycle.resumeInterruptedUsbExclusivePlaybackIfNeeded
import moe.ouom.neriplayer.core.player.lifecycle.scheduleUsbAudioSinkReconfiguration
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.playback.clearAudioRouteMuteSuppression
import moe.ouom.neriplayer.core.player.playback.restorePlaybackAfterTransientAudioRouteLoss
import moe.ouom.neriplayer.core.player.audio.focus.StartupAudioFocusController
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemSoundGuard

internal object PlayerManagerUsbSystemAudioRoutePort : UsbSystemAudioRoutePort {
    override fun snapshot(): UsbSystemAudioSnapshot {
        val media = PlayerManager.usbRoutePlayerSnapshot(includeTransportState = true)
        return UsbSystemAudioSnapshot(
            usbEnabled = PlayerManager.usbExclusivePlaybackEnabled,
            playerInitialized = media.initialized,
            mediaItemCount = media.mediaItemCount,
            hasMediaItem = media.hasMediaItem,
            mediaItemIndex = media.mediaItemIndex,
            positionMs = media.positionMs,
            playWhenReady = media.playWhenReady,
            isPlaying = media.isPlaying,
            playbackState = media.playbackState
        )
    }

    override fun interruptedPositionMs(): Long? = PlayerManager.usbExclusiveInterruptedPlaybackIntent?.positionMs

    override fun cancelRecovery(reason: String) {
        PlayerManager.cancelUsbExclusiveRecovery(reason)
    }

    override fun cancelSinkReconfiguration() {
        PlayerManager.cancelUsbAudioSinkReconfiguration()
    }

    override fun prepareForDisable(reason: String) {
        PlayerManager.cancelPendingPauseRequest(resetVolumeToFull = true)
        PlayerManager.cancelVolumeFade(resetToFull = true)
        PlayerManager.clearAudioRouteMuteSuppression(reason = "usb_exclusive_release_start:$reason")
        PlayerManager.updateResumePlaybackRequested(false)
        PlayerManager.clearUsbExclusiveInterruptedPlaybackIntent("usb_exclusive_disabled")
    }

    override fun deferNativeOpen(reason: String, delayMs: Long) {
        UsbExclusiveSessionController.deferPlayerPcmOpen(reason, delayMs)
    }

    override fun updatePathAfterRelease(reason: String, disabling: Boolean, playbackShouldContinue: Boolean) {
        setFallbackAfterRelease(reason, disabling)
        UsbExclusiveAudioPathTracker.updateConfigured(
            usingNative = false,
            fallbackReason = reason.takeUnless { disabling },
            inputFormat = "none"
        )
        UsbExclusiveAudioPathTracker.updatePlaying(playing = playbackShouldContinue, usingNative = false)
    }

    private fun setFallbackAfterRelease(reason: String, disabling: Boolean) {
        if (disabling) UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
        else UsbExclusiveAudioPathTracker.forceSystemFallback(reason)
    }

    override fun stopNativeSessions(reason: String, disabling: Boolean) {
        UsbExclusiveSessionController.stopGeneratedTone()
        if (disabling) UsbExclusiveSessionController.forceStopAllSessions(reason)
        else UsbExclusiveSessionController.stopPlayerPcmSession(reason)
    }

    override fun releaseSystemSound(reason: String) {
        UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(PlayerManager.application, reason)
    }

    override fun releaseAudioFocus(reason: String) {
        StartupAudioFocusController.forceRelease(reason)
    }

    override fun nativeCloseInFlightCount(): Int = UsbExclusiveSessionController.nativeCloseInFlightCount()

    override fun clearPreferredDevice(reason: String) {
        runCatching { PlayerManager.player.setPreferredAudioDevice(null) }.onFailure { error ->
            NPLogger.w("NERI-UsbExclusive", "release route failed to clear preferred device: reason=$reason", error)
        }
    }

    override fun clearReleaseMute(reason: String) {
        PlayerManager.cancelVolumeFade(resetToFull = true)
        PlayerManager.clearAudioRouteMuteSuppression(reason = "usb_exclusive_release:$reason")
    }

    override fun restoreAudioFocus() {
        PlayerManager.applyAudioFocusPolicyOnMainThread()
    }

    override fun restoreTransientPlayback(reason: String) {
        PlayerManager.restorePlaybackAfterTransientAudioRouteLoss(reason)
    }

    override fun scheduleSinkReconfiguration(reason: String) {
        PlayerManager.scheduleUsbAudioSinkReconfiguration(
            reason = reason,
            allowWhilePlaybackActive = true,
            bypassCooldown = true
        )
    }

    override fun resumeInterruptedPlayback(reason: String) {
        PlayerManager.resumeInterruptedUsbExclusivePlaybackIfNeeded(reason)
    }

    override fun resetSystemAudioPlayer(
        mediaItemIndex: Int,
        positionMs: Long,
        resumePlayback: Boolean,
        reason: String
    ): Boolean {
        PlayerManager.cancelPendingPauseRequest(resetVolumeToFull = true)
        PlayerManager.cancelVolumeFade(resetToFull = true)
        PlayerManager.clearAudioRouteMuteSuppression(reason = "usb_system_audio_reset:$reason")
        PlayerManager.playbackRequestToken += 1
        PlayerManager.playJob?.cancel()
        PlayerManager.playJob = null
        return tryResetSystemAudioPlayer(mediaItemIndex, positionMs, resumePlayback, reason)
    }

    private fun tryResetSystemAudioPlayer(
        mediaItemIndex: Int,
        positionMs: Long,
        resumePlayback: Boolean,
        reason: String
    ): Boolean = try {
        performSystemAudioPlayerReset(mediaItemIndex, positionMs, resumePlayback)
        true
    } catch (error: Throwable) {
        runCatching { PlayerManager.player.playWhenReady = resumePlayback }
        NPLogger.e("NERI-UsbExclusive", "force system audio reset after USB release failed: reason=$reason", error)
        false
    }

    private fun performSystemAudioPlayerReset(mediaItemIndex: Int, positionMs: Long, resumePlayback: Boolean) {
        val player = PlayerManager.player
        player.setPreferredAudioDevice(null)
        player.volume = 1f
        PlayerManager.updateResumePlaybackRequested(resumePlayback)
        PlayerManager.applyAudioFocusPolicyOnMainThread()
        player.playWhenReady = false
        player.stop()
        player.seekTo(mediaItemIndex, positionMs)
        player.prepare()
        player.playWhenReady = resumePlayback
        finishSystemAudioPlaybackReset(resumePlayback, positionMs)
    }

    private fun finishSystemAudioPlaybackReset(resumePlayback: Boolean, positionMs: Long) {
        if (resumePlayback) resumeSystemAudioPlayback(positionMs)
        else leaveSystemAudioPlaybackIdle(positionMs)
    }

    private fun resumeSystemAudioPlayback(positionMs: Long) {
        PlayerManager._playWhenReadyFlow.value = true
        PlayerManager.scheduleStatePersist(positionMs = positionMs, shouldResumePlayback = true)
        PlayerManager.player.play()
    }

    private fun leaveSystemAudioPlaybackIdle(positionMs: Long) {
        PlayerManager.player.playWhenReady = false
        PlayerManager._isPlayingFlow.value = false
        PlayerManager._playWhenReadyFlow.value = false
        PlayerManager._playbackPositionMs.value = positionMs
        PlayerManager.stopProgressUpdates()
        PlayerManager.scheduleStatePersist(positionMs = positionMs, shouldResumePlayback = false)
    }

    override fun clearForcedSystemFallback() {
        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    }

    override fun clearInterruptedIntent(reason: String) {
        PlayerManager.clearUsbExclusiveInterruptedPlaybackIntent(reason)
    }

    override fun finishToggle(reason: String) {
        PlayerManager.usbRouteTransitionOwner.clearToggle()
        PlayerManager.markUsbExclusivePlaybackPreparing(false, reason)
    }
}
