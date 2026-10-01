@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.usb.route

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.Player
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDebugLogger
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnostics
import moe.ouom.neriplayer.core.player.lifecycle.resumeInterruptedUsbExclusivePlaybackIfNeeded
import moe.ouom.neriplayer.core.player.lifecycle.updateAudioOffloadPreferences
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.playback.clearAudioRouteMuteSuppression
import moe.ouom.neriplayer.core.player.playback.pauseImpl
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.usb.shouldSkipUsbExclusiveRouteRebuildForManualPlayback
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.core.player.audio.focus.StartupAudioFocusController
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemSoundGuard
import moe.ouom.neriplayer.core.player.audio.wake.PlaybackTransitionWakeLock
import moe.ouom.neriplayer.data.model.settings.usb.UsbExclusivePreferences

internal object PlayerManagerUsbPlaybackRoutePort : UsbPlaybackRoutePort {
    internal fun isPlaybackActiveForSwitch(): Boolean {
        if (!hasCurrentTrackForSwitch()) return false
        return transportActiveForSwitch()
    }

    private fun hasCurrentTrackForSwitch(): Boolean =
        hasCurrentTrack(PlayerManager.initialized, PlayerManager._currentSongFlow.value != null)

    private fun transportActiveForSwitch(): Boolean =
        playbackSignalsActive(PlayerManager.isTransportActiveWithoutInitialization(),
            PlayerManager._playerPlaybackStateFlow.value == Player.STATE_BUFFERING)

    internal fun shouldKeepPlaybackActiveForSwitch(): Boolean = keepPlaybackActiveForSwitch(
        PlayerManager.isPlayerInitialized(), ::pendingSwitchIntent, ::currentTransportWantsPlayback
    )

    private fun pendingSwitchIntent(): Boolean = hasPendingPlaybackIntent(
        PlayerManager.resumePlaybackRequested,
        PlayerManager.usbExclusiveInterruptedPlaybackIntent != null,
        pendingPlayJobActive()
    )

    private fun pendingPlayJobActive(): Boolean = activePlaybackJob(PlayerManager.playJob)

    private fun currentTransportWantsPlayback(): Boolean =
        if (Looper.myLooper() == Looper.getMainLooper()) {
            playbackSignalsActive(PlayerManager.player.isPlaying, PlayerManager.player.playWhenReady)
        } else {
            playbackSignalsActive(PlayerManager._isPlayingFlow.value, PlayerManager._playWhenReadyFlow.value)
        }

    override fun snapshot(): UsbPlaybackRouteSnapshot {
        val media = PlayerManager.usbRoutePlayerSnapshot()
        return UsbPlaybackRouteSnapshot(
            enabled = PlayerManager.usbExclusivePlaybackEnabled,
            mixedPlaybackEnabled = PlayerManager.allowMixedPlaybackEnabled,
            playerInitialized = media.initialized,
            playbackActive = isPlaybackActiveForSwitch(),
            hasMediaItem = media.hasMediaItem,
            mediaItemCount = media.mediaItemCount,
            mediaItemIndex = media.mediaItemIndex,
            positionMs = media.positionMs,
            resumeRequested = PlayerManager.resumePlaybackRequested
        )
    }

    override fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    override fun setPlaybackEnabled(enabled: Boolean) {
        PlayerManager.usbExclusivePlaybackEnabled = enabled
    }

    override fun currentPreferences(): UsbExclusivePreferences = PlayerManager.usbExclusivePreferences

    override fun setPreferences(preferences: UsbExclusivePreferences) {
        PlayerManager.usbExclusivePreferences = preferences
    }

    override fun markPreparing(preparing: Boolean, reason: String) {
        PlayerManager.markUsbExclusivePlaybackPreparing(preparing, reason)
    }

    override fun pauseForToggle(enabled: Boolean) {
        PlayerManager.pauseImpl(
            forcePersist = false,
            commandSource = PlaybackCommandSource.LOCAL,
            allowFadeOut = false,
            preserveMutedVolume = false,
            debugReason = if (enabled) "usb_toggle_enable_prepare" else "usb_toggle_disable_prepare"
        )
    }

    override fun applyAudioFocus() {
        PlayerManager.applyAudioFocusPolicyOnMainThread()
    }

    override fun applyOffloadPreferences() {
        PlayerManager.updateAudioOffloadPreferences("usb_exclusive_policy")
    }

    override fun prepareEnabledPolicy() {
        UsbExclusiveDiagnostics.ensureUsbPermissionIfNeeded(PlayerManager.application, "apply_policy")
    }

    override fun logPolicyBeforeSet() {
        val audioManager = PlayerManager.application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        UsbExclusiveDebugLogger.logSnapshot(
            context = PlayerManager.application,
            audioManager = audioManager,
            reason = "apply_policy_before_set",
            enabled = PlayerManager.usbExclusivePlaybackEnabled,
            preferredDevice = null
        )
    }

    override fun applyPreferredAudioDevice() {
        val audioManager = PlayerManager.application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val error = runCatching { PlayerManager.player.setPreferredAudioDevice(null) }.exceptionOrNull()
        if (error != null) {
            NPLogger.w("NERI-UsbExclusive", "applyUsbExclusivePlaybackPolicy(): setPreferredAudioDevice failed", error)
        } else {
            NPLogger.d("NERI-PlayerManager", "applyUsbExclusivePlaybackPolicy(): enabled=${PlayerManager.usbExclusivePlaybackEnabled}, target=none")
            UsbExclusiveDebugLogger.logSnapshot(
                context = PlayerManager.application,
                audioManager = audioManager,
                reason = "apply_policy_after_set",
                enabled = PlayerManager.usbExclusivePlaybackEnabled,
                preferredDevice = null
            )
        }
    }

    override fun scheduleSoundConfigApply() {
        PlayerManager.schedulePlaybackSoundConfigApply(
            previousConfig = PlayerManager.playbackSoundConfig,
            newConfig = PlayerManager.playbackSoundConfig
        )
    }

    override fun applyActiveBuffer(reason: String) {
        PlayerManager.usbExclusiveLivenessOwner.applyActiveBuffer(reason)
    }

    override fun cancelLivenessJobs() {
        PlayerManager.usbExclusiveLivenessOwner.cancelJobs()
    }

    override fun stopAfterNativeFailure(reason: String) {
        StartupAudioFocusController.forceRelease("native_failure:$reason")
        if (!PlayerManager.isPlayerInitialized()) return
        PlayerManager.runPlayerActionOnMainThread { stopAfterNativeFailureOnMain(reason) }
    }

    private fun stopAfterNativeFailureOnMain(reason: String) {
        if (!canStopAfterNativeFailure(
                PlayerManager.isPlayerInitialized(), PlayerManager.usbExclusivePlaybackEnabled)) return
        val positionMs = PlayerManager.player.currentPosition.coerceAtLeast(0L)
        val keepIntent = PlayerManager.usbInterruptedPlaybackOwner.rememberAfterNativeFailure(reason, positionMs)
        cancelPendingNativePlayback(reason)
        stopNativePlayerAfterFailure(reason)
        publishNativeFailure(positionMs, keepIntent)
    }

    private fun cancelPendingNativePlayback(reason: String) {
        PlayerManager.cancelPendingPauseRequest(resetVolumeToFull = true)
        PlayerManager.cancelVolumeFade(resetToFull = true)
        PlayerManager.clearAudioRouteMuteSuppression("usb_native_failure:$reason")
        PlayerManager.playbackRequestToken += 1
        PlayerManager.playJob?.cancel()
        PlayerManager.playJob = null
        PlayerManager.pendingMediaLoadActive = false
        PlayerManager.usbSinkRouteOwner.clearPendingPreferenceReconfiguration()
    }

    private fun stopNativePlayerAfterFailure(reason: String) {
        UsbExclusiveSessionController.forceStopAllSessions("native_failure:$reason")
        UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(PlayerManager.application, "native_failure:$reason")
        UsbExclusiveAudioPathTracker.forceSystemFallback(reason)
        runCatching {
            PlayerManager.player.playWhenReady = false
            PlayerManager.player.pause()
            PlayerManager.player.stop()
        }.onFailure { NPLogger.w("NERI-UsbExclusive", "failed to stop player after native USB failure: reason=$reason", it) }
    }

    private fun publishNativeFailure(positionMs: Long, keepIntent: Boolean) {
        PlayerManager._isPlayingFlow.value = false
        PlayerManager._playWhenReadyFlow.value = false
        PlayerManager._playbackPositionMs.value = positionMs
        PlayerManager.stopProgressUpdates()
        PlayerManager.scheduleStatePersist(positionMs = positionMs, shouldResumePlayback = keepIntent)
        PlayerManager.postPlayerEvent(
            PlayerEvent.ShowError(PlayerManager.getLocalizedString(CoreCommonR.string.settings_usb_exclusive_issue_transport))
        )
    }

    override fun manualRouteAvailable(): Boolean {
        val diagnostics = UsbExclusiveDiagnostics.snapshot(PlayerManager.application)
        return !shouldSkipUsbExclusiveRouteRebuildForManualPlayback(
            usbExclusivePlaybackEnabled = PlayerManager.usbExclusivePlaybackEnabled,
            allowMixedPlaybackEnabled = PlayerManager.allowMixedPlaybackEnabled,
            hasUsbAudioOutput = diagnostics.hasUsbAudioOutput,
            hasUsbHostAudioDevice = diagnostics.hasUsbHostAudioDevice
        )
    }

    override fun blockManualPlayback(reason: String) {
        val player = PlayerManager.player
        PlayerManager.usbInterruptedPlaybackOwner.queueIndexForInterruption()?.let { queueIndex ->
            PlayerManager.usbInterruptedPlaybackOwner.remember(
                "manual_play_no_usb:$reason", queueIndex, player.currentPosition.coerceAtLeast(0L)
            )
        }
        StartupAudioFocusController.forceRelease("manual_play_no_usb:$reason")
        player.playWhenReady = false
        player.pause()
        PlayerManager._isPlayingFlow.value = false
        PlayerManager._playWhenReadyFlow.value = false
        PlayerManager.scheduleStatePersist(player.currentPosition.coerceAtLeast(0L), true)
        PlayerManager.postPlayerEvent(
            PlayerEvent.ShowError(PlayerManager.getLocalizedString(CoreCommonR.string.settings_usb_exclusive_issue_device))
        )
    }

    override fun rebuildManualPlayer(reason: String): Boolean {
        val player = PlayerManager.player
        val count = player.mediaItemCount
        val index = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        NPLogger.i("NERI-UsbExclusive", "prepare native USB route for manual playback: reason=$reason index=$index positionMs=$positionMs")
        return try {
            player.playWhenReady = false
            player.stop()
            player.seekTo(index, positionMs)
            player.prepare()
            true
        } catch (error: Throwable) {
            UsbExclusiveAudioPathTracker.forceSystemFallback("manual_play_reconfigure_failed")
            NPLogger.e("NERI-UsbExclusive", "prepare native USB route for manual playback failed: reason=$reason", error)
            false
        }
    }

    override fun resumeAfterOpenGate(reason: String) {
        if (!PlayerManager.usbExclusivePlaybackEnabled) {
            PlayerManager.resumeInterruptedUsbExclusivePlaybackIfNeeded(reason)
            return
        }
        PlayerManager.player.playWhenReady = true
        PlayerManager.player.play()
    }

    override fun releaseSystemSound(reason: String) {
        UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(PlayerManager.application, reason)
    }

    override fun releaseNativeResources() {
        PlayerManager.usbInterruptedPlaybackOwner.cancelReattach()
        UsbExclusiveSessionController.forceStopAllSessions("player_release")
        PlaybackTransitionWakeLock.releaseAll("player_release")
        UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(PlayerManager.application, "player_release")
    }

    override fun application(): android.app.Application = PlayerManager.application
}
