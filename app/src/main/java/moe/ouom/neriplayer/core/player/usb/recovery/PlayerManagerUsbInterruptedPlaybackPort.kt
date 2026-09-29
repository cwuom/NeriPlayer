package moe.ouom.neriplayer.core.player.usb.recovery

import moe.ouom.neriplayer.data.identity.sameIdentityAs

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnostics
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveDiagnosticsSnapshot
import moe.ouom.neriplayer.core.player.playback.playAtIndex
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.data.model.SongItem

internal object PlayerManagerUsbInterruptedPlaybackPort : UsbInterruptedPlaybackPort {
    override fun snapshot(): UsbInterruptedPlaybackSnapshot {
        val playlist = PlayerManager.currentPlaylist
        val index = PlayerManager.currentIndex
        val song = PlayerManager._currentSongFlow.value
        val usbEnabled = PlayerManager.usbExclusivePlaybackEnabled
        val initialized = PlayerManager.isPlayerInitialized()
        val playbackSignals = playerPlaybackSignals(usbEnabled, initialized)
        return UsbInterruptedPlaybackSnapshot(
            usbEnabled = usbEnabled,
            mixedPlaybackEnabled = PlayerManager.allowMixedPlaybackEnabled,
            resumeRequested = PlayerManager.resumePlaybackRequested,
            playerInitialized = initialized,
            queueSize = playlist.size,
            currentIndex = index,
            currentIndexMatchesCurrentSong = indexMatchesCurrentSong(playlist, index, song),
            currentSongQueueIndex = currentSongQueueIndex(song),
            requestToken = PlayerManager.playbackRequestToken,
            playJobActive = isPlayJobActive(),
            playWhenReady = playbackSignals.first,
            isPlaying = playbackSignals.second,
            reportedPlayWhenReady = PlayerManager._playWhenReadyFlow.value,
            reportedPlaying = PlayerManager._isPlayingFlow.value
        )
    }

    private fun indexMatchesCurrentSong(playlist: List<SongItem>, index: Int, song: SongItem?): Boolean {
        val queuedSong = playlist.getOrNull(index) ?: return false
        return matchesCurrentSongOrUnknown(queuedSong, song)
    }

    private fun matchesCurrentSongOrUnknown(queuedSong: SongItem, song: SongItem?): Boolean {
        if (song == null) return true
        return queuedSong.sameIdentityAs(song)
    }

    private fun currentSongQueueIndex(song: SongItem?): Int =
        song?.let { PlayerManager.queueIndexOf(it) } ?: -1

    private fun isPlayJobActive(): Boolean {
        val job = PlayerManager.playJob ?: return false
        return job.isActive
    }

    private fun playerPlaybackSignals(usbEnabled: Boolean, initialized: Boolean): Pair<Boolean, Boolean> {
        if (!usbEnabled) return false to false
        return initializedPlayerPlaybackSignals(initialized)
    }

    private fun initializedPlayerPlaybackSignals(initialized: Boolean): Pair<Boolean, Boolean> {
        if (!initialized) return false to false
        val player = PlayerManager.player
        return player.playWhenReady to player.isPlaying
    }

    override fun reattachAvailability(): UsbReattachAvailability {
        val diagnostics = UsbExclusiveDiagnostics.snapshot(PlayerManager.application)
        return UsbReattachAvailability(
            canRequestPermission = diagnostics.canRequestPermission,
            selectedOutputAvailable = diagnostics.selectedUsbOutput != null,
            selectedHostPermissionGranted = selectedHostPermissionGranted(diagnostics)
        )
    }

    private fun selectedHostPermissionGranted(diagnostics: UsbExclusiveDiagnosticsSnapshot): Boolean {
        val selectedDevice = diagnostics.selectedUsbHostDevice ?: return false
        return selectedDevice.hasPermission
    }

    override fun requestPermission(reason: String) {
        UsbExclusiveDiagnostics.ensureUsbPermissionIfNeeded(PlayerManager.application, reason)
    }

    override fun nativeOpenGateActive(): Boolean =
        UsbExclusiveSessionController.playerPcmOpenGateReason() != null

    override fun requestLoudPlaybackConfirmation(onConfirm: () -> Unit, onCancel: () -> Unit): Boolean =
        PlayerManager.requestUsbExclusiveLoudPlaybackConfirmation(
            commandSource = PlaybackCommandSource.LOCAL,
            continuePlayback = onConfirm,
            cancelPlayback = onCancel
        )

    override fun updateResumeRequested(requested: Boolean) {
        PlayerManager.updateResumePlaybackRequested(requested)
    }

    override fun resumeOnSystemRoute(intent: UsbInterruptedPlaybackIntent) {
        PlayerManager.currentIndex = intent.queueIndex
        PlayerManager.playAtIndex(
            intent.queueIndex,
            resumePositionMs = intent.positionMs.coerceAtLeast(0L),
            forceStartupProtectionFade = intent.positionMs > 0L
        )
    }

    override fun resumeOnUsbRoute(intent: UsbInterruptedPlaybackIntent) {
        PlayerManager.currentIndex = intent.queueIndex
        PlayerManager.playAtIndex(
            index = intent.queueIndex,
            resumePositionMs = intent.positionMs,
            commandSource = PlaybackCommandSource.LOCAL,
            forceStartupProtectionFade = true
        )
    }
}
