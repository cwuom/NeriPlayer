package moe.ouom.neriplayer.core.player.audio.route

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Usb
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.audio.isBluetoothOutputType
import moe.ouom.neriplayer.core.player.audio.isUsbOutputType
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDebugLogger
import moe.ouom.neriplayer.core.player.lifecycle.applyUsbExclusivePlaybackPolicy
import moe.ouom.neriplayer.core.player.lifecycle.scheduleUsbExclusivePlaybackResumeAfterDeviceAttach
import moe.ouom.neriplayer.core.player.lifecycle.stopPlaybackAfterUsbExclusiveNativeFailure
import moe.ouom.neriplayer.core.player.model.AudioDevice
import moe.ouom.neriplayer.core.player.playback.pauseForAudioRouteLoss
import moe.ouom.neriplayer.core.player.playback.restorePlaybackAfterTransientAudioRouteLoss
import moe.ouom.neriplayer.core.player.playback.suppressPlaybackForAudioRouteLoss
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeState

internal object PlayerManagerAudioDeviceRoutePort : AudioDeviceRoutePort {
    private fun audioManager(): AudioManager =
        PlayerManager.application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun snapshot(): AudioDeviceRouteSnapshot = AudioDeviceRouteSnapshot(
        initialized = PlayerManager.initialized,
        isPlaying = PlayerManager._isPlayingFlow.value,
        playWhenReady = PlayerManager._playWhenReadyFlow.value,
        resumeRequested = PlayerManager.resumePlaybackRequested,
        usbExclusiveEnabled = PlayerManager.usbExclusivePlaybackEnabled,
        allowMixedPlayback = PlayerManager.allowMixedPlaybackEnabled,
        stopOnBluetoothDisconnect = PlayerManager.stopOnBluetoothDisconnectEnabled,
        listenTogetherActive = PlayerManager.isListenTogetherActive(),
        listenTogetherController = PlayerManager.isCurrentUserControllerInListenTogether(),
        interruptedUsbPlayback = PlayerManager.usbExclusiveInterruptedPlaybackIntent != null
    )

    override fun ensureInitialized() {
        PlayerManager.ensureInitialized()
    }

    override fun readCurrentDevice(): AudioDevice {
        val devices = audioManager().getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val selected = preferredOutputDevice(devices, PlayerManager.usbExclusivePlaybackEnabled)
        if (selected != null) return toAudioDevice(selected)
        return AudioDevice(
            PlayerManager.getLocalizedString(R.string.device_speaker),
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            Icons.Default.SpeakerGroup
        )
    }

    private fun toAudioDevice(device: AudioDeviceInfo): AudioDevice =
        if (isBluetoothOutputType(device.type)) toBluetoothDevice(device) else toWiredOrUsbDevice(device)

    private fun toWiredOrUsbDevice(device: AudioDeviceInfo): AudioDevice =
        if (isUsbOutputType(device.type)) toUsbDevice(device) else AudioDevice(
            PlayerManager.getLocalizedString(R.string.device_wired_headset), device.type, Icons.Default.Headset
        )

    private fun toBluetoothDevice(device: AudioDeviceInfo): AudioDevice = try {
        AudioDevice(
            name = device.productName.toString()
                .ifBlank { PlayerManager.getLocalizedString(R.string.device_bluetooth_headset) },
            type = device.type,
            icon = Icons.Default.BluetoothAudio
        )
    } catch (_: SecurityException) {
        AudioDevice(
            PlayerManager.getLocalizedString(R.string.device_bluetooth_headset),
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            Icons.Default.BluetoothAudio
        )
    }

    private fun toUsbDevice(device: AudioDeviceInfo): AudioDevice = AudioDevice(
        name = device.productName.toString()
            .ifBlank { PlayerManager.getLocalizedString(R.string.device_usb_audio) },
        type = device.type,
        icon = Icons.Default.Usb
    )

    override fun publishCurrentDevice(device: AudioDevice) {
        PlayerManager._currentAudioDevice.value = device
    }

    override fun registerCallback(callback: AudioDeviceCallback) {
        audioManager().registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    override fun unregisterCallback(callback: AudioDeviceCallback) {
        runCatching { audioManager().unregisterAudioDeviceCallback(callback) }
            .onFailure { NPLogger.w("NERI-PlayerManager", "release(): unregisterAudioDeviceCallback failed", it) }
    }

    override fun logDeviceCallback(reason: String, devices: Array<out AudioDeviceInfo>?) {
        UsbExclusiveDebugLogger.logAudioDeviceCallback(reason, devices)
    }

    override fun logSnapshot(reason: String) {
        UsbExclusiveDebugLogger.logSnapshot(
            context = PlayerManager.application,
            audioManager = audioManager(),
            reason = reason,
            enabled = PlayerManager.usbExclusivePlaybackEnabled
        )
    }

    override fun nativeState(): UsbExclusiveNativeState = UsbExclusiveSessionController.state.value
    override fun nativeOpenGateReason(): String? = UsbExclusiveSessionController.playerPcmOpenGateReason()

    override fun forceSystemFallback(reason: String) {
        UsbExclusiveAudioPathTracker.forceSystemFallback(reason)
    }

    override fun clearForcedSystemFallback() {
        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    }

    override fun deferNativeOpen(reason: String, delayMs: Long) {
        UsbExclusiveSessionController.deferPlayerPcmOpen(reason, delayMs)
    }

    override fun muteListenTogetherListener(): Boolean =
        PlayerManager.shouldMuteListenTogetherListenerForAudioRouteLoss()

    override fun suppressPlayback(reason: String) {
        PlayerManager.suppressPlaybackForAudioRouteLoss(reason)
    }

    override fun pausePlayback(reason: String) {
        PlayerManager.pauseForAudioRouteLoss(reason)
    }

    override fun stopUsbPlayback(reason: String) {
        PlayerManager.stopPlaybackAfterUsbExclusiveNativeFailure(reason)
    }

    override fun applyUsbPolicy(reconfigureSink: Boolean) {
        PlayerManager.applyUsbExclusivePlaybackPolicy(reconfigureAudioSink = reconfigureSink)
    }

    override fun scheduleUsbResumeAfterAttach(reason: String) {
        PlayerManager.scheduleUsbExclusivePlaybackResumeAfterDeviceAttach(reason)
    }

    override fun restorePlayback(reason: String) {
        PlayerManager.restorePlaybackAfterTransientAudioRouteLoss(reason)
    }
}
