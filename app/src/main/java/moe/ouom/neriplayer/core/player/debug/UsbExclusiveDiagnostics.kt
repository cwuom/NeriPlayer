package moe.ouom.neriplayer.core.player.debug

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.lifecycle.retryUsbExclusivePlayback
import moe.ouom.neriplayer.core.player.audio.isUsbOutputType
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveWakeLock
import moe.ouom.neriplayer.core.player.usb.device.usbExclusiveDeviceKey
import moe.ouom.neriplayer.core.player.usb.device.usbExclusiveDeviceKeyMatchesLabel
import moe.ouom.neriplayer.core.player.usb.device.matchesUsbExclusiveDeviceKey
import moe.ouom.neriplayer.data.settings.DEFAULT_USB_EXCLUSIVE_DEVICE_KEY
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.data.settings.toUsbExclusivePreferences
import moe.ouom.neriplayer.core.logging.NPLogger
import java.util.concurrent.atomic.AtomicBoolean

data class UsbExclusiveDiagnosticsSnapshot(
    val usbExclusivePlaybackEnabled: Boolean,
    val allowMixedPlaybackEnabled: Boolean,
    val selectedDeviceKey: String,
    val sampleRateMode: String,
    val bitDepthMode: String,
    val bufferProfile: String,
    val unsupportedFormatPolicy: String,
    val sampleRateCompatibility: Boolean,
    val bitDepthCompatibility: Boolean,
    val channelCompatibility: Boolean,
    val foregroundBufferMs: Int,
    val backgroundBufferMs: Int,
    val playerInitialized: Boolean,
    val playerPlaying: Boolean,
    val currentPlayerDeviceName: String?,
    val currentPlayerDeviceType: Int?,
    val audioOutputs: List<UsbAudioOutputDebugInfo>,
    val usbHostDevices: List<UsbHostDeviceDebugInfo>,
    val selectedUsbOutput: UsbAudioOutputDebugInfo?,
    val selectedUsbHostDevice: UsbHostDeviceDebugInfo?,
    val lastPermissionEvent: UsbPermissionDebugEvent?,
    val systemRouteSummary: String,
    val systemRouteLimitation: String,
    val nativeExclusiveSummary: String,
    val nativeExclusiveSource: String,
    val nativeExclusiveRuntime: String,
    val nativeExclusiveStreaming: Boolean,
    val nativeExclusiveError: String?,
    val audioServiceInstanceActive: Boolean,
    val audioServiceForegroundActive: Boolean,
    val usbWakeLockHeld: Boolean,
    val nativePcmLevelBytes: Long,
    val nativePcmCapacityBytes: Long,
    val nativePcmFreeBytes: Long,
    val nativePcmBackpressureEvents: Long,
    val nativePcmBackpressureCurrentMs: Long,
    val nativePcmBackpressureMaxMs: Long,
    val nativePlayerSignalFrames: Long,
    val nativePlayerSilentFrames: Long,
    val nativePlayerSignalBytes: Long,
    val nativeOutputPeak: Float,
    val nativeLastOutputPeak: Float,
    val requestedPath: String,
    val effectivePath: String,
    val fallbackReason: String?,
    val sinkPlaying: Boolean,
    val nativePaused: Boolean,
    val inputFormat: String,
    val requestedPlaybackParameters: String,
    val requestedVolume: Float
) {
    val hasUsbAudioOutput: Boolean get() = audioOutputs.any { it.isUsbOutput }
    val hasUsbHostAudioDevice: Boolean get() = usbHostDevices.any { it.hasAudioInterface }
    val hasUsbPermission: Boolean get() = usbHostDevices.any { it.hasPermission }
    val canRequestPermission: Boolean get() = usbHostDevices.any { it.hasAudioInterface && !it.hasPermission }

    fun toReport(): String = buildString {
        appendLine("USB Exclusive Diagnostics")
        appendLine("systemRoute=$systemRouteSummary")
        appendLine("systemRouteLimitation=$systemRouteLimitation")
        appendLine("nativeExclusive=$nativeExclusiveSummary")
        appendLine("nativeSource=$nativeExclusiveSource")
        appendLine("nativeRuntime=$nativeExclusiveRuntime")
        appendLine("nativeStreaming=$nativeExclusiveStreaming")
        appendLine("nativeError=${nativeExclusiveError ?: "none"}")
        appendLine(
            "service: instance=$audioServiceInstanceActive foreground=$audioServiceForegroundActive " +
                "wakeLock=$usbWakeLockHeld"
        )
        appendLine(
            "nativePcm: level=$nativePcmLevelBytes/$nativePcmCapacityBytes " +
                "free=$nativePcmFreeBytes backpressureEvents=$nativePcmBackpressureEvents " +
                "backpressureCurrentMs=$nativePcmBackpressureCurrentMs " +
                "backpressureMaxMs=$nativePcmBackpressureMaxMs"
        )
        appendLine(
            "nativeSignal: signalFrames=$nativePlayerSignalFrames " +
                "silentFrames=$nativePlayerSilentFrames signalBytes=$nativePlayerSignalBytes " +
                "outputPeak=$nativeOutputPeak lastOutputPeak=$nativeLastOutputPeak"
        )
        appendLine("requestedPath=$requestedPath")
        appendLine("effectivePath=$effectivePath")
        appendLine("fallbackReason=${fallbackReason ?: "none"}")
        appendLine("sinkPlaying=$sinkPlaying nativePaused=$nativePaused")
        appendLine("inputFormat=$inputFormat")
        appendLine("playbackParameters=$requestedPlaybackParameters volume=$requestedVolume")
        appendLine("settings: usbExclusive=$usbExclusivePlaybackEnabled, allowMixed=$allowMixedPlaybackEnabled")
        appendLine("usbDeviceSelection=$selectedDeviceKey")
        appendLine(
            "usbFormatSettings: sampleRate=$sampleRateMode bitDepth=$bitDepthMode " +
                "buffer=$bufferProfile foregroundBufferMs=$foregroundBufferMs " +
                "backgroundBufferMs=$backgroundBufferMs unsupported=$unsupportedFormatPolicy " +
                "compat(rate=$sampleRateCompatibility,bit=$bitDepthCompatibility,channels=$channelCompatibility)"
        )
        appendLine(
            "player: initialized=$playerInitialized, playing=$playerPlaying, " +
                "current=$currentPlayerDeviceType:$currentPlayerDeviceName"
        )
        appendLine("selectedAudioOutput=${selectedUsbOutput?.compactLine() ?: "none"}")
        appendLine("selectedUsbHostDevice=${selectedUsbHostDevice?.compactLine() ?: "none"}")
        appendLine("lastPermission=${lastPermissionEvent?.compactLine() ?: "none"}")
        appendLine()
        appendLine("Audio outputs:")
        if (audioOutputs.isEmpty()) {
            appendLine("  none")
        } else {
            audioOutputs.forEach { appendLine("  ${it.compactLine()}") }
        }
        appendLine()
        appendLine("USB host devices:")
        if (usbHostDevices.isEmpty()) {
            appendLine("  none")
        } else {
            usbHostDevices.forEach { appendLine("  ${it.compactLine()}") }
        }
    }
}

data class UsbAudioOutputDebugInfo(
    val id: Int,
    val type: Int,
    val typeName: String,
    val productName: String,
    val address: String,
    val isSink: Boolean,
    val isSource: Boolean,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val encodings: List<Int>,
    val isUsbOutput: Boolean
) {
    fun compactLine(): String {
        return "id=$id type=$typeName($type) name=$productName address=$address " +
            "usb=$isUsbOutput rates=${sampleRates.compactList()} channels=${channelCounts.compactList()} " +
            "encodings=${encodings.compactList()}"
    }
}

data class UsbHostDeviceDebugInfo(
    val deviceKey: String,
    val deviceName: String,
    val productName: String,
    val manufacturerName: String,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceClassName: String,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val interfaceCount: Int,
    val hasAudioInterface: Boolean,
    val hasAudioStreamingInterface: Boolean,
    val hasPermission: Boolean,
    val interfaces: List<UsbInterfaceDebugInfo>
) {
    val vendorProductId: String
        get() = "0x${vendorId.toString(16).uppercase()}:0x${productId.toString(16).uppercase()}"

    fun compactLine(): String {
        return "$productName $vendorProductId name=$deviceName class=$deviceClassName($deviceClass) " +
            "audio=$hasAudioInterface streaming=$hasAudioStreamingInterface permission=$hasPermission " +
            "interfaces=${interfaces.joinToString(prefix = "[", postfix = "]") { it.compactLine() }}"
    }
}

data class UsbInterfaceDebugInfo(
    val id: Int,
    val interfaceClass: Int,
    val interfaceClassName: String,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpointCount: Int
) {
    fun compactLine(): String {
        return "#$id:$interfaceClassName($interfaceClass)/sub=$interfaceSubclass/proto=$interfaceProtocol/eps=$endpointCount"
    }
}

data class UsbPermissionDebugEvent(
    val deviceName: String?,
    val vendorProductId: String?,
    val granted: Boolean,
    val atElapsedMs: Long,
    val reason: String
) {
    fun compactLine(): String {
        return "device=$deviceName vidPid=$vendorProductId granted=$granted at=$atElapsedMs reason=$reason"
    }
}

object UsbExclusiveDiagnostics {
    private const val TAG = "NERI-UsbExclusive"
    private const val ACTION_USB_PERMISSION =
        "moe.ouom.neriplayer.action.USB_EXCLUSIVE_PERMISSION"
    private const val USB_SUBCLASS_AUDIO_STREAMING = 0x02
    private const val PERMISSION_REQUEST_COOLDOWN_MS = 3_000L

    private val receiverRegistered = AtomicBoolean(false)
    @Volatile
    private var lastPermissionEvent: UsbPermissionDebugEvent? = null
    @Volatile
    private var lastPermissionRequestKey: String? = null
    @Volatile
    private var lastPermissionRequestAtMs: Long = 0L

    fun snapshot(context: Context): UsbExclusiveDiagnosticsSnapshot {
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val settings = readPlaybackPreferenceSnapshotSync(appContext)
        val audioOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { it.toDebugInfo() }
        val usbHostDevices = queryUsbHostDevices(appContext)
        val currentDevice = PlayerManager.currentAudioDeviceFlow.value
        val nativeState = UsbExclusiveSessionController.state.value
        val pathState = UsbExclusiveAudioPathTracker.state.value
        val usbPreferences = if (PlayerManager.isPlayerInitialized()) {
            PlayerManager.usbExclusivePreferences
        } else {
            settings.toUsbExclusivePreferences()
        }
        val usbExclusiveEnabled = if (PlayerManager.isPlayerInitialized()) {
            PlayerManager.usbExclusivePlaybackEnabled
        } else {
            settings.usbExclusivePlayback
        }
        val allowMixedPlayback = if (PlayerManager.isPlayerInitialized()) {
            PlayerManager.allowMixedPlaybackEnabled
        } else {
            settings.allowMixedPlayback
        }
        val selectedDeviceKey = usbPreferences.selectedDeviceKey
        val usbAudioOutputs = audioOutputs.filter { it.isUsbOutput }
        val selectedUsbOutput = usbAudioOutputs.firstOrNull {
            it.isUsbOutput && usbExclusiveDeviceKeyMatchesLabel(selectedDeviceKey, it.productName)
        } ?: usbAudioOutputs.singleOrNull()
        val selectedUsbHostDevice = usbHostDevices.firstOrNull {
            it.hasAudioInterface &&
                (selectedDeviceKey == DEFAULT_USB_EXCLUSIVE_DEVICE_KEY || it.deviceKey == selectedDeviceKey)
        }
        val systemRouteSummary = if (selectedUsbOutput != null && usbExclusiveEnabled) {
            "SYSTEM_USB_AUDIO_ROUTE"
        } else if (usbExclusiveEnabled) {
            "WAITING_FOR_USB_AUDIO_OUTPUT"
        } else {
            "DISABLED"
        }
        val nativeExclusiveSummary = when {
            nativeState.streaming -> "NATIVE_EXCLUSIVE_STREAMING"
            nativeState.opened -> "NATIVE_EXCLUSIVE_OPENED"
            nativeState.available -> "NATIVE_EXCLUSIVE_IDLE"
            else -> "NATIVE_EXCLUSIVE_UNAVAILABLE"
        }

        return UsbExclusiveDiagnosticsSnapshot(
            usbExclusivePlaybackEnabled = usbExclusiveEnabled,
            allowMixedPlaybackEnabled = allowMixedPlayback,
            selectedDeviceKey = selectedDeviceKey,
            sampleRateMode = usbPreferences.sampleRateMode.storageValue,
            bitDepthMode = usbPreferences.bitDepthMode.storageValue,
            bufferProfile = usbPreferences.bufferProfile.storageValue,
            unsupportedFormatPolicy = usbPreferences.unsupportedFormatPolicy.storageValue,
            sampleRateCompatibility = usbPreferences.sampleRateCompatibilityEnabled,
            bitDepthCompatibility = usbPreferences.bitDepthCompatibilityEnabled,
            channelCompatibility = usbPreferences.channelCompatibilityEnabled,
            foregroundBufferMs = usbPreferences.foregroundBufferMs,
            backgroundBufferMs = usbPreferences.backgroundBufferMs,
            playerInitialized = PlayerManager.isPlayerInitialized(),
            playerPlaying = PlayerManager.isPlayingFlow.value,
            currentPlayerDeviceName = currentDevice?.name,
            currentPlayerDeviceType = currentDevice?.type,
            audioOutputs = audioOutputs,
            usbHostDevices = usbHostDevices,
            selectedUsbOutput = selectedUsbOutput,
            selectedUsbHostDevice = selectedUsbHostDevice,
            lastPermissionEvent = lastPermissionEvent,
            systemRouteSummary = systemRouteSummary,
            systemRouteLimitation = "system route only; this does not prove mixer bypass",
            nativeExclusiveSummary = nativeExclusiveSummary,
            nativeExclusiveSource = nativeState.source,
            nativeExclusiveRuntime = nativeState.runtimeReport,
            nativeExclusiveStreaming = nativeState.streaming,
            nativeExclusiveError = nativeState.lastError,
            audioServiceInstanceActive = AudioPlayerService.isInstanceActiveForDiagnostics(),
            audioServiceForegroundActive = AudioPlayerService.isForegroundActiveForDiagnostics(),
            usbWakeLockHeld = UsbExclusiveWakeLock.isHeld(),
            nativePcmLevelBytes = nativeState.pcmLevelBytes,
            nativePcmCapacityBytes = nativeState.pcmCapacityBytes,
            nativePcmFreeBytes = nativeState.pcmFreeBytes,
            nativePcmBackpressureEvents = nativeState.pcmBackpressureEvents,
            nativePcmBackpressureCurrentMs = nativeState.pcmBackpressureCurrentMs,
            nativePcmBackpressureMaxMs = nativeState.pcmBackpressureMaxMs,
            nativePlayerSignalFrames = nativeState.playerSignalFrames,
            nativePlayerSilentFrames = nativeState.playerSilentFrames,
            nativePlayerSignalBytes = nativeState.playerSignalBytes,
            nativeOutputPeak = nativeState.outputPeak,
            nativeLastOutputPeak = nativeState.lastOutputPeak,
            requestedPath = pathState.requestedPath,
            effectivePath = pathState.effectivePath,
            fallbackReason = pathState.fallbackReason,
            sinkPlaying = pathState.sinkPlaying,
            nativePaused = pathState.nativePaused,
            inputFormat = pathState.inputFormat,
            requestedPlaybackParameters = pathState.requestedPlaybackParameters,
            requestedVolume = pathState.requestedVolume
        )
    }

    fun ensureUsbPermissionIfNeeded(
        context: Context,
        reason: String
    ): Boolean {
        val appContext = context.applicationContext
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            NPLogger.w(TAG, "ensureUsbPermissionIfNeeded($reason): UsbManager unavailable")
            return false
        }
        ensurePermissionReceiverRegistered(appContext)

        val selectedDeviceKey = if (PlayerManager.isPlayerInitialized()) {
            PlayerManager.usbExclusivePreferences.selectedDeviceKey
        } else {
            readPlaybackPreferenceSnapshotSync(appContext)
                .toUsbExclusivePreferences()
                .selectedDeviceKey
        }
        val device = usbManager.deviceList.values
            .filter { it.hasAudioInterface() }
            .sortedWith(compareByDescending<UsbDevice> { it.hasAudioStreamingInterface() }.thenBy { it.deviceName })
            .firstOrNull { it.matchesUsbExclusiveDeviceKey(selectedDeviceKey) }
        if (device == null) {
            NPLogger.d(TAG, "ensureUsbPermissionIfNeeded($reason): no USB audio host device")
            return false
        }
        if (usbManager.hasPermission(device)) {
            lastPermissionEvent = device.toPermissionEvent(granted = true, reason = "$reason:already_granted")
            lastPermissionRequestKey = null
            lastPermissionRequestAtMs = 0L
            NPLogger.d(TAG, "ensureUsbPermissionIfNeeded($reason): already granted for ${device.describeForLog()}")
            return true
        }

        val requestKey = device.permissionRequestKey()
        val now = android.os.SystemClock.elapsedRealtime()
        val coolingDown = requestKey == lastPermissionRequestKey &&
            now - lastPermissionRequestAtMs < PERMISSION_REQUEST_COOLDOWN_MS
        if (coolingDown) {
            NPLogger.d(
                TAG,
                "ensureUsbPermissionIfNeeded($reason): cooldown active for ${device.describeForLog()}"
            )
            return false
        }
        lastPermissionRequestKey = requestKey
        lastPermissionRequestAtMs = now

        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pendingIntent = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        NPLogger.i(TAG, "ensureUsbPermissionIfNeeded($reason): requesting permission for ${device.describeForLog()}")
        usbManager.requestPermission(device, pendingIntent)
        return false
    }

    fun logSnapshot(
        context: Context,
        reason: String
    ) {
        val snapshot = snapshot(context)
        NPLogger.d(TAG, "diagnostics($reason):\n${snapshot.toReport()}")
    }

    private fun ensurePermissionReceiverRegistered(context: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        NPLogger.d(TAG, "USB permission receiver registered")
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.getUsbDeviceExtra()
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                lastPermissionRequestKey = null
                lastPermissionRequestAtMs = 0L
            }
            lastPermissionEvent = device?.toPermissionEvent(granted, "permission_result")
                ?: UsbPermissionDebugEvent(
                    deviceName = null,
                    vendorProductId = null,
                    granted = granted,
                    atElapsedMs = android.os.SystemClock.elapsedRealtime(),
                    reason = "permission_result_missing_device"
                )
            NPLogger.i(
                TAG,
                "USB permission result: granted=$granted, device=${device?.describeForLog() ?: "none"}"
            )
            if (granted) {
                PlayerManager.retryUsbExclusivePlayback("usb_permission_granted")
            }
            logSnapshot(context, "permission_result")
        }
    }

    private fun queryUsbHostDevices(context: Context): List<UsbHostDeviceDebugInfo> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return usbManager.deviceList.values
            .sortedBy { it.deviceName }
            .map { device -> device.toDebugInfo(usbManager) }
    }

    @SuppressLint("MissingPermission")
    private fun AudioDeviceInfo.toDebugInfo(): UsbAudioOutputDebugInfo {
        return UsbAudioOutputDebugInfo(
            id = id,
            type = type,
            typeName = audioDeviceTypeName(type),
            productName = productName?.toString().orEmpty().ifBlank { "blank" },
            address = address.ifBlank { "blank" },
            isSink = isSink,
            isSource = isSource,
            sampleRates = sampleRates.toList(),
            channelCounts = channelCounts.toList(),
            encodings = encodings.toList(),
            isUsbOutput = isUsbOutputType(type)
        )
    }

    private fun UsbDevice.toDebugInfo(usbManager: UsbManager): UsbHostDeviceDebugInfo {
        val interfaces = (0 until interfaceCount)
            .map { index -> getInterface(index).toDebugInfo() }
        return UsbHostDeviceDebugInfo(
            deviceKey = usbExclusiveDeviceKey(),
            deviceName = deviceName,
            productName = productName ?: "unknown",
            manufacturerName = manufacturerName ?: "unknown",
            vendorId = vendorId,
            productId = productId,
            deviceClass = deviceClass,
            deviceClassName = usbClassName(deviceClass),
            deviceSubclass = deviceSubclass,
            deviceProtocol = deviceProtocol,
            interfaceCount = interfaceCount,
            hasAudioInterface = hasAudioInterface(),
            hasAudioStreamingInterface = hasAudioStreamingInterface(),
            hasPermission = runCatching { usbManager.hasPermission(this) }.getOrDefault(false),
            interfaces = interfaces
        )
    }

    private fun UsbInterface.toDebugInfo(): UsbInterfaceDebugInfo {
        return UsbInterfaceDebugInfo(
            id = id,
            interfaceClass = interfaceClass,
            interfaceClassName = usbClassName(interfaceClass),
            interfaceSubclass = interfaceSubclass,
            interfaceProtocol = interfaceProtocol,
            endpointCount = endpointCount
        )
    }

    private fun UsbDevice.hasAudioInterface(): Boolean {
        if (deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        return (0 until interfaceCount)
            .map { getInterface(it) }
            .any { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO }
    }

    private fun UsbDevice.hasAudioStreamingInterface(): Boolean {
        return (0 until interfaceCount)
            .map { getInterface(it) }
            .any {
                it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    it.interfaceSubclass == USB_SUBCLASS_AUDIO_STREAMING
            }
    }

    private fun UsbDevice.toPermissionEvent(
        granted: Boolean,
        reason: String
    ): UsbPermissionDebugEvent {
        return UsbPermissionDebugEvent(
            deviceName = deviceName,
            vendorProductId = "0x${vendorId.toString(16).uppercase()}:0x${productId.toString(16).uppercase()}",
            granted = granted,
            atElapsedMs = android.os.SystemClock.elapsedRealtime(),
            reason = reason
        )
    }

    private fun UsbDevice.describeForLog(): String {
        return "name=$deviceName,product=${productName ?: "unknown"}," +
            "vid=0x${vendorId.toString(16)},pid=0x${productId.toString(16)}," +
            "class=${usbClassName(deviceClass)}($deviceClass),interfaces=$interfaceCount"
    }

    private fun UsbDevice.permissionRequestKey(): String {
        return "$vendorId:$productId:$deviceName"
    }

    private fun Intent.getUsbDeviceExtra(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun audioDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
            AudioDeviceInfo.TYPE_DOCK -> "DOCK"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
            else -> bleAudioDeviceTypeName(type) ?: "UNKNOWN"
        }
    }

    private fun bleAudioDeviceTypeName(type: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return when (type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
            else -> null
        }
    }

    private fun usbClassName(deviceClass: Int): String {
        return when (deviceClass) {
            UsbConstants.USB_CLASS_AUDIO -> "AUDIO"
            UsbConstants.USB_CLASS_COMM -> "COMM"
            UsbConstants.USB_CLASS_HID -> "HID"
            UsbConstants.USB_CLASS_HUB -> "HUB"
            UsbConstants.USB_CLASS_MASS_STORAGE -> "MASS_STORAGE"
            UsbConstants.USB_CLASS_PER_INTERFACE -> "PER_INTERFACE"
            UsbConstants.USB_CLASS_VENDOR_SPEC -> "VENDOR_SPEC"
            else -> "UNKNOWN"
        }
    }
}

private fun List<Int>.compactList(maxItems: Int = 8): String {
    if (isEmpty()) return "[]"
    val visible = take(maxItems).joinToString(prefix = "[", postfix = "]")
    return if (size <= maxItems) visible else "$visible+$size"
}
