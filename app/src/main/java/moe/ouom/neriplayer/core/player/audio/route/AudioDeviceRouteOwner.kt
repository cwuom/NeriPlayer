package moe.ouom.neriplayer.core.player.audio.route

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.audio.isBluetoothOutputType
import moe.ouom.neriplayer.core.player.audio.isHeadsetLikeOutput
import moe.ouom.neriplayer.core.player.audio.isUsbOutputType
import moe.ouom.neriplayer.core.player.audio.requiresDisconnectConfirmation
import moe.ouom.neriplayer.core.player.model.AudioDevice
import moe.ouom.neriplayer.core.player.policy.audio.BLUETOOTH_DISCONNECT_CONFIRMATION_SAMPLE_COUNT
import moe.ouom.neriplayer.core.player.policy.audio.BLUETOOTH_DISCONNECT_CONFIRM_INITIAL_DELAY_MS
import moe.ouom.neriplayer.core.player.policy.audio.BLUETOOTH_DISCONNECT_CONFIRM_SAMPLE_INTERVAL_MS
import moe.ouom.neriplayer.core.player.policy.audio.shouldConfirmBluetoothDisconnect
import moe.ouom.neriplayer.core.player.policy.usb.shouldDeferUsbExclusiveNoisyRouteToNativePath
import moe.ouom.neriplayer.core.player.policy.usb.shouldStopUsbExclusivePlaybackForNoisyRoute
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeState
import moe.ouom.neriplayer.listentogether.playback.shouldMuteListenTogetherListenerForOutputDisconnect

internal data class AudioDeviceRouteSnapshot(
    val initialized: Boolean,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
    val resumeRequested: Boolean,
    val usbExclusiveEnabled: Boolean,
    val allowMixedPlayback: Boolean,
    val stopOnBluetoothDisconnect: Boolean,
    val listenTogetherActive: Boolean,
    val listenTogetherController: Boolean,
    val interruptedUsbPlayback: Boolean
)

internal interface AudioDeviceRoutePort {
    fun snapshot(): AudioDeviceRouteSnapshot
    fun ensureInitialized()
    fun readCurrentDevice(): AudioDevice
    fun publishCurrentDevice(device: AudioDevice)
    fun registerCallback(callback: AudioDeviceCallback)
    fun unregisterCallback(callback: AudioDeviceCallback)
    fun logDeviceCallback(reason: String, devices: Array<out AudioDeviceInfo>?)
    fun logSnapshot(reason: String)
    fun nativeState(): UsbExclusiveNativeState
    fun nativeOpenGateReason(): String?
    fun forceSystemFallback(reason: String)
    fun clearForcedSystemFallback()
    fun deferNativeOpen(reason: String, delayMs: Long)
    fun muteListenTogetherListener(): Boolean
    fun suppressPlayback(reason: String)
    fun pausePlayback(reason: String)
    fun stopUsbPlayback(reason: String)
    fun applyUsbPolicy(reconfigureSink: Boolean)
    fun scheduleUsbResumeAfterAttach(reason: String)
    fun restorePlayback(reason: String)
}

internal class AudioDeviceRouteOwner(
    private val scope: CoroutineScope,
    private val port: AudioDeviceRoutePort
) {
    private var currentDevice: AudioDevice? = null
    private var callback: AudioDeviceCallback? = null
    private var bluetoothDisconnectPauseJob: Job? = null

    fun register() {
        val initial = port.readCurrentDevice()
        publish(initial)
        NPLogger.d("NERI-PlayerManager", "setupAudioDeviceCallback(): initialDevice=${initial.type}:${initial.name}")
        port.logSnapshot("setup_initial")
        val registered = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                logCallbackDevices("audioDevicesAdded", addedDevices)
                port.logDeviceCallback("audioDevicesAdded", addedDevices)
                onDeviceChange(addedDevices.containsUsbOutput())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                logCallbackDevices("audioDevicesRemoved", removedDevices)
                port.logDeviceCallback("audioDevicesRemoved", removedDevices)
                onDeviceChange(
                    usbTopologyChanged = removedDevices.containsUsbOutput(),
                    outputDeviceRemoved = removedDevices.containsHeadsetOutput()
                )
            }
        }
        callback = registered
        port.registerCallback(registered)
        NPLogger.d("NERI-PlayerManager", "setupAudioDeviceCallback(): callback registered")
    }

    private fun logCallbackDevices(reason: String, devices: Array<out AudioDeviceInfo>?) {
        val received = devices?.asList().orEmpty()
        val description = received.joinToString { "${it.type}:${it.productName}" }
        NPLogger.d("NERI-PlayerManager", "$reason(): count=${received.size}, devices=$description")
    }

    private fun Array<out AudioDeviceInfo>?.containsUsbOutput(): Boolean =
        this?.any(::isUsbSink) == true

    private fun Array<out AudioDeviceInfo>?.containsHeadsetOutput(): Boolean =
        this?.any(::isHeadsetSink) == true

    private fun isUsbSink(device: AudioDeviceInfo): Boolean = device.isSink && isUsbOutputType(device.type)

    private fun isHeadsetSink(device: AudioDeviceInfo): Boolean = device.isSink && isHeadsetLikeOutput(device.type)

    fun release() {
        callback?.let(port::unregisterCallback)
        callback = null
        cancelBluetoothDisconnectPause()
    }

    fun cancelBluetoothDisconnectPause() {
        bluetoothDisconnectPauseJob?.cancel()
        bluetoothDisconnectPauseJob = null
    }

    fun onAudioBecomingNoisy(): Boolean {
        port.ensureInitialized()
        val state = port.snapshot()
        if (!state.initialized) {
            NPLogger.d("NERI-PlayerManager", "handleAudioBecomingNoisy(): ignored because manager is not initialized")
            return false
        }
        val device = currentDevice
        if (port.muteListenTogetherListener()) {
            NPLogger.d("NERI-PlayerManager", "handleAudioBecomingNoisy(): mute Listen Together listener without pausing")
            port.suppressPlayback("listen_together_becoming_noisy")
            return true
        }
        if (deferNoisyToNativeRoute(state, device)) return false
        if (stopUsbOnNoisyRoute(state, device)) return true
        return handleSystemNoisyRoute(state, device)
    }

    private fun deferNoisyToNativeRoute(state: AudioDeviceRouteSnapshot, device: AudioDevice?): Boolean {
        val native = port.nativeState()
        val defer = shouldDeferUsbExclusiveNoisyRouteToNativePath(
                usbExclusivePlaybackEnabled = state.usbExclusiveEnabled,
                allowMixedPlaybackEnabled = state.allowMixedPlayback,
                routeIsUsbOutput = device?.type?.let(::isUsbOutputType) == true,
                nativePlayerPcmActive = native.opened && native.source == "player_pcm"
            )
        if (defer) {
            NPLogger.d(
                "NERI-UsbExclusive",
                "defer noisy-route broadcast to active native USB path: streaming=${native.streaming} handle=${native.handle}"
            )
        }
        return defer
    }

    private fun stopUsbOnNoisyRoute(state: AudioDeviceRouteSnapshot, device: AudioDevice?): Boolean {
        if (!shouldStopUsbNoisyRoute(state, device, state.playbackActiveForNoisyRoute())) return false
        NPLogger.w("NERI-UsbExclusive", "stop USB exclusive playback after noisy route event: device=${device?.type}:${device?.name}")
        port.stopUsbPlayback("usb_audio_route_noisy")
        return true
    }

    private fun handleSystemNoisyRoute(state: AudioDeviceRouteSnapshot, device: AudioDevice?): Boolean {
        if (!state.isPlaying) {
            NPLogger.d("NERI-PlayerManager", "handleAudioBecomingNoisy(): ignored because playback is already paused")
            return false
        }
        if (shouldIgnoreUsbSystemNoisyRoute(state, device?.type)) return false
        return handleActiveSystemNoisyRoute(state, device)
    }

    private fun handleActiveSystemNoisyRoute(state: AudioDeviceRouteSnapshot, device: AudioDevice?): Boolean {
        if (device != null && requiresDisconnectConfirmation(device.type)) return handleBluetoothNoisyRoute(state, device)
        port.suppressPlayback("becoming_noisy_immediate")
        port.pausePlayback("becoming_noisy_immediate")
        return true
    }

    private fun handleBluetoothNoisyRoute(state: AudioDeviceRouteSnapshot, device: AudioDevice): Boolean {
        if (!shouldPauseForBluetoothDisconnect(state, device, null)) return false
        port.suppressPlayback("bluetooth_disconnect_pending")
        schedulePauseForBluetoothDisconnect(device, "becoming_noisy")
        return true
    }

    fun onDeviceChange(usbTopologyChanged: Boolean, outputDeviceRemoved: Boolean = false) {
        val previous = currentDevice
        val next = port.readCurrentDevice()
        publish(next)
        val routeChanged = routeChanged(previous, next)
        val reconfigureSink = routeChanged || usbTopologyChanged
        val state = port.snapshot()
        val listenerDisconnected = listenerDisconnected(state, previous, next, outputDeviceRemoved, routeChanged)
        if (listenerDisconnected) muteDisconnectedListener()
        if (interceptUsbRoute(state, previous, next, reconfigureSink, usbTopologyChanged)) return
        applyAndroidRoute(state, previous, next, reconfigureSink, listenerDisconnected)
    }

    private fun muteDisconnectedListener() {
        cancelBluetoothDisconnectPause()
        port.suppressPlayback("listen_together_output_disconnect")
    }

    private fun interceptUsbRoute(
        state: AudioDeviceRouteSnapshot,
        previous: AudioDevice?,
        next: AudioDevice,
        reconfigureSink: Boolean,
        usbTopologyChanged: Boolean
    ): Boolean {
        if (handlePhysicalUsbDetach(state, next)) return true
        if (ignoreNativeOwnedRoute(state, previous, next, usbTopologyChanged)) return true
        if (resumeInterruptedUsb(state, next, reconfigureSink)) return true
        return handleUsbRouteJitter(state, previous, next)
    }

    private fun routeChanged(previous: AudioDevice?, next: AudioDevice): Boolean =
        previous == null || previous.type != next.type || previous.name != next.name

    private fun listenerDisconnected(
        state: AudioDeviceRouteSnapshot,
        previous: AudioDevice?,
        next: AudioDevice,
        outputDeviceRemoved: Boolean,
        routeChanged: Boolean
    ): Boolean = shouldMuteListenTogetherListenerForOutputDisconnect(
            listenTogetherActive = state.listenTogetherActive,
            isCurrentUserController = state.listenTogetherController,
            previousRouteWasHeadsetLike = previous?.type?.let(::isHeadsetLikeOutput) == true,
            newRouteIsBuiltinSpeaker = next.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            outputDeviceRemoved = outputDeviceRemoved,
            routeChanged = routeChanged
        )

    private fun handlePhysicalUsbDetach(state: AudioDeviceRouteSnapshot, next: AudioDevice): Boolean {
        if (!state.usbExclusiveEnabled || isUsbOutputType(next.type)) return false
        if (port.nativeOpenGateReason()?.contains("usb_device_detached", ignoreCase = true) == true) {
            port.forceSystemFallback("usb_device_detached")
            return true
        }
        return false
    }

    private fun ignoreNativeOwnedRoute(
        state: AudioDeviceRouteSnapshot,
        previous: AudioDevice?,
        next: AudioDevice,
        usbTopologyChanged: Boolean
    ): Boolean {
        if (!state.usbExclusiveEnabled) return false
        val native = port.nativeState()
        if (state.nativeOwnsUsbRoute(native)) {
            NPLogger.d(
                "NERI-UsbExclusive",
                "ignore Android route churn while native USB owns the device: " +
                    "previous=${previous?.type}:${previous?.name} next=${next.type}:${next.name} topology=$usbTopologyChanged"
            )
            return true
        }
        return false
    }

    private fun resumeInterruptedUsb(
        state: AudioDeviceRouteSnapshot,
        next: AudioDevice,
        reconfigureSink: Boolean
    ): Boolean {
        if (!state.canResumeInterruptedUsb(next.type)) return false
        port.clearForcedSystemFallback()
        port.applyUsbPolicy(reconfigureSink)
        port.scheduleUsbResumeAfterAttach("audio_device_added")
        port.logSnapshot("usb_device_reattach")
        return true
    }

    private fun handleUsbRouteJitter(
        state: AudioDeviceRouteSnapshot,
        previous: AudioDevice?,
        next: AudioDevice
    ): Boolean {
        if (!shouldTreatAsUsbRouteJitter(state, previous, next)) return false
        cancelBluetoothDisconnectPause()
        port.deferNativeOpen("route_jitter", ROUTE_JITTER_REOPEN_COOLDOWN_MS)
        port.restorePlayback("usb_exclusive_route_jitter")
        return true
    }

    private fun applyAndroidRoute(
        state: AudioDeviceRouteSnapshot,
        previous: AudioDevice?,
        next: AudioDevice,
        reconfigureSink: Boolean,
        listenerDisconnected: Boolean
    ) {
        configureAndroidRoute(state, reconfigureSink)
        port.logSnapshot("device_change")
        NPLogger.d(
            "NERI-PlayerManager",
            "handleDeviceChange(): ${previous?.type}:${previous?.name} -> ${next.type}:${next.name}, isPlaying=${state.isPlaying}"
        )
        if (listenerDisconnected) return
        reconcilePlaybackAfterRouteChange(state, previous, next)
    }

    private fun configureAndroidRoute(state: AudioDeviceRouteSnapshot, reconfigureSink: Boolean) {
        if (reconfigureSink) port.clearForcedSystemFallback()
        port.applyUsbPolicy(shouldReconfigureUsbPolicy(state, reconfigureSink))
    }

    private fun reconcilePlaybackAfterRouteChange(
        state: AudioDeviceRouteSnapshot,
        previous: AudioDevice?,
        next: AudioDevice
    ) {
        if (shouldPauseForBluetoothDisconnect(state, previous, next)) {
            schedulePauseForBluetoothDisconnect(previous, "device_changed_to_${next.type}")
        } else if (shouldPauseForImmediateOutputDisconnect(state, previous, next)) {
            cancelBluetoothDisconnectPause()
            port.suppressPlayback("immediate_output_disconnect")
            port.pausePlayback("immediate_output_disconnect")
        } else if (next.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            cancelBluetoothDisconnectPause()
            port.restorePlayback("device_changed_to_${next.type}")
        }
    }

    private fun publish(device: AudioDevice) {
        currentDevice = device
        port.publishCurrentDevice(device)
    }

    private fun shouldStopUsbNoisyRoute(state: AudioDeviceRouteSnapshot, device: AudioDevice?, active: Boolean): Boolean =
        shouldStopUsbExclusivePlaybackForNoisyRoute(
            usbExclusivePlaybackEnabled = state.usbExclusiveEnabled,
            allowMixedPlaybackEnabled = state.allowMixedPlayback,
            routeIsUsbOutput = device?.type?.let(::isUsbOutputType) == true,
            playbackActive = active
        )

    private fun shouldPauseForImmediateOutputDisconnect(
        state: AudioDeviceRouteSnapshot,
        previous: AudioDevice?,
        next: AudioDevice?
    ): Boolean = pausesForImmediateOutputDisconnect(state, previous?.type, next?.type)

    private fun shouldTreatAsUsbRouteJitter(
        state: AudioDeviceRouteSnapshot,
        previous: AudioDevice?,
        next: AudioDevice?
    ): Boolean = treatsAsUsbRouteJitter(state, previous, next)

    private fun shouldPauseForBluetoothDisconnect(
        state: AudioDeviceRouteSnapshot,
        previous: AudioDevice?,
        next: AudioDevice?
    ): Boolean = pausesForBluetoothDisconnect(state, previous?.type, next?.type)

    private fun schedulePauseForBluetoothDisconnect(previous: AudioDevice?, reason: String) {
        if (previous == null || !requiresDisconnectConfirmation(previous.type)) return
        cancelBluetoothDisconnectPause()
        bluetoothDisconnectPauseJob = scope.launch {
            val sampledRoutesAreBluetooth = mutableListOf<Boolean>()
            repeat(BLUETOOTH_DISCONNECT_CONFIRMATION_SAMPLE_COUNT) { sampleIndex ->
                delay(if (sampleIndex == 0) BLUETOOTH_DISCONNECT_CONFIRM_INITIAL_DELAY_MS
                    else BLUETOOTH_DISCONNECT_CONFIRM_SAMPLE_INTERVAL_MS)
                val state = port.snapshot()
                if (!state.stopOnBluetoothDisconnect || !state.isPlaying) {
                    port.restorePlayback("bluetooth_disconnect_canceled:$reason")
                    bluetoothDisconnectPauseJob = null
                    return@launch
                }
                val confirmed = port.readCurrentDevice()
                publish(confirmed)
                if (!isBluetoothOutputType(confirmed.type) && confirmed.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    port.restorePlayback("bluetooth_disconnect_rerouted:${confirmed.type}")
                    bluetoothDisconnectPauseJob = null
                    return@launch
                }
                sampledRoutesAreBluetooth += isBluetoothOutputType(confirmed.type)
            }
            val state = port.snapshot()
            if (!shouldConfirmBluetoothDisconnect(
                    stopOnBluetoothDisconnectEnabled = state.stopOnBluetoothDisconnect,
                    playbackActive = state.isPlaying,
                    previousRouteWasBluetooth = requiresDisconnectConfirmation(previous.type),
                    sampledRoutesAreBluetooth = sampledRoutesAreBluetooth
                )) {
                port.restorePlayback("bluetooth_disconnect_transient:$reason")
                bluetoothDisconnectPauseJob = null
                return@launch
            }
            port.suppressPlayback("bluetooth_disconnect_confirmed:$reason")
            port.pausePlayback("bluetooth_disconnect_confirmed:$reason")
            bluetoothDisconnectPauseJob = null
        }
    }

    private companion object {
        const val ROUTE_JITTER_REOPEN_COOLDOWN_MS = 4_000L
    }
}
