package moe.ouom.neriplayer.data.model.playback.usb

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
    val nativeChannel0OutputPeak: Float,
    val nativeChannel1OutputPeak: Float,
    val nativeLastChannel0OutputPeak: Float,
    val nativeLastChannel1OutputPeak: Float,
    val requestedPath: String,
    val effectivePath: String,
    val fallbackReason: String?,
    val sinkPlaying: Boolean,
    val nativePaused: Boolean,
    val inputFormat: String,
    val requestedOutputFormat: String,
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
                "outputPeak=$nativeOutputPeak lastOutputPeak=$nativeLastOutputPeak " +
                "channelPeaks=$nativeChannel0OutputPeak/$nativeChannel1OutputPeak " +
                "lastChannelPeaks=$nativeLastChannel0OutputPeak/$nativeLastChannel1OutputPeak"
        )
        appendLine("requestedPath=$requestedPath")
        appendLine("effectivePath=$effectivePath")
        appendLine("fallbackReason=${fallbackReason ?: "none"}")
        appendLine("sinkPlaying=$sinkPlaying nativePaused=$nativePaused")
        appendLine("inputFormat=$inputFormat")
        appendLine("requestedOutputFormat=$requestedOutputFormat")
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

private fun List<Int>.compactList(maxItems: Int = 8): String {
    if (isEmpty()) return "[]"
    val visible = take(maxItems).joinToString(prefix = "[", postfix = "]")
    return if (size <= maxItems) visible else "$visible+$size"
}
