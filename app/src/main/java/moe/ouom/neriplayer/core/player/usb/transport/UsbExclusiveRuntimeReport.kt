package moe.ouom.neriplayer.core.player.usb.transport

data class UsbExclusiveRuntimeMetrics(
    val sampleRate: Int? = null,
    val channelCount: Int? = null,
    val subslotBytes: Int? = null,
    val transferBytes: Long? = null,
    val lastTransferBytes: Long? = null,
    val isoPacketErrors: Long? = null,
    val isoPacketErrorTransfers: Long? = null,
    val isoPacketErrorScore: Int? = null,
    val pcmLevelBytes: Long? = null,
    val pcmCapacityBytes: Long? = null,
    val pcmFreeBytes: Long? = null,
    val pcmMaxLevelBytes: Long? = null,
    val pcmBackpressureEvents: Long? = null,
    val pcmBackpressureTotalMs: Long? = null,
    val pcmBackpressureCurrentMs: Long? = null,
    val pcmBackpressureMaxMs: Long? = null,
    val playerSignalFrames: Long? = null,
    val playerSilentFrames: Long? = null,
    val playerSignalBytes: Long? = null,
    val playerZeroFillBytes: Long? = null,
    val outputPeak: Float? = null,
    val lastOutputPeak: Float? = null,
    val transportFailed: Boolean? = null,
    val running: Boolean? = null,
    val lastError: String = "none"
) {
    val outputFrameBytes: Int?
        get() {
            val channels = channelCount ?: return null
            val bytes = subslotBytes ?: return null
            val frameBytes = channels * bytes
            return frameBytes.takeIf { it > 0 }
        }

    val hasPcmQueue: Boolean
        get() = (pcmCapacityBytes ?: 0L) > 0L

    val hasHealthyTransport: Boolean
        get() = transportFailed != true && lastError == "none"

    val isQueueFull: Boolean
        get() {
            pcmFreeBytes?.let { return it <= 0L && (pcmCapacityBytes ?: 0L) > 0L }
            val level = pcmLevelBytes ?: return false
            val capacity = pcmCapacityBytes ?: return false
            return capacity > 0L && level >= capacity
        }

    val isBenignBackpressure: Boolean
        get() = isQueueFull && hasHealthyTransport
}

internal fun String.usbRuntimeMetrics(): UsbExclusiveRuntimeMetrics {
    val levelPart = valueAfter("pcmLevel")
    return UsbExclusiveRuntimeMetrics(
        sampleRate = valueAfter("sampleRate")?.toIntOrNull(),
        channelCount = valueAfter("channels")?.toIntOrNull(),
        subslotBytes = valueAfter("subslotBytes")?.toIntOrNull(),
        transferBytes = valueAfter("transferBytes")?.toLongOrNull(),
        lastTransferBytes = valueAfter("lastTransferBytes")?.toLongOrNull(),
        isoPacketErrors = valueAfter("isoPacketErrors")?.toLongOrNull(),
        isoPacketErrorTransfers = valueAfter("isoPacketErrorTransfers")?.toLongOrNull(),
        isoPacketErrorScore = valueAfter("isoPacketErrorScore")?.toIntOrNull(),
        pcmLevelBytes = levelPart?.substringBefore('/')?.toLongOrNull(),
        pcmCapacityBytes = levelPart
            ?.substringAfter('/', missingDelimiterValue = "")
            ?.toLongOrNull(),
        pcmFreeBytes = valueAfter("pcmFreeBytes")?.toLongOrNull(),
        pcmMaxLevelBytes = valueAfter("pcmMaxLevelBytes")?.toLongOrNull(),
        pcmBackpressureEvents = valueAfter("pcmBackpressureEvents")?.toLongOrNull(),
        pcmBackpressureTotalMs = valueAfter("pcmBackpressureTotalMs")?.toLongOrNull(),
        pcmBackpressureCurrentMs = valueAfter("pcmBackpressureCurrentMs")?.toLongOrNull(),
        pcmBackpressureMaxMs = valueAfter("pcmBackpressureMaxMs")?.toLongOrNull(),
        playerSignalFrames = valueAfter("playerSignalFrames")?.toLongOrNull(),
        playerSilentFrames = valueAfter("playerSilentFrames")?.toLongOrNull(),
        playerSignalBytes = valueAfter("playerSignalBytes")?.toLongOrNull(),
        playerZeroFillBytes = valueAfter("playerZeroFillBytes")?.toLongOrNull(),
        outputPeak = valueAfter("outputPeak")?.toFloatOrNull(),
        lastOutputPeak = valueAfter("lastOutputPeak")?.toFloatOrNull(),
        transportFailed = booleanField("transportFailed"),
        running = booleanField("running"),
        lastError = valueAfter("lastError") ?: "none"
    )
}

internal fun UsbExclusiveRuntimeMetrics.withLivePcmFreeBytes(
    liveFreeBytes: Long
): UsbExclusiveRuntimeMetrics {
    val capacity = pcmCapacityBytes?.takeIf { it > 0L }
    val normalizedFreeBytes = if (capacity != null) {
        liveFreeBytes.coerceIn(0L, capacity)
    } else {
        liveFreeBytes.coerceAtLeast(0L)
    }
    return copy(
        pcmLevelBytes = capacity?.let { it - normalizedFreeBytes } ?: pcmLevelBytes,
        pcmFreeBytes = normalizedFreeBytes
    )
}

internal fun String.valueAfter(key: String): String? {
    val regex = Regex("(?:^|\\s)${Regex.escape(key)}=([^\\s]+)")
    return regex.find(this)?.groupValues?.getOrNull(1)
}

internal fun String.booleanField(name: String): Boolean? {
    return when (valueAfter(name)) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}
