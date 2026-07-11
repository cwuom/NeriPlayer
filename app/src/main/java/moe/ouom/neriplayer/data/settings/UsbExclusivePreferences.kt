package moe.ouom.neriplayer.data.settings

import kotlin.math.abs

const val DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_MODE = "follow_source"
const val DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_MODE = "auto"
const val DEFAULT_USB_EXCLUSIVE_BUFFER_PROFILE = "stable"
const val DEFAULT_USB_EXCLUSIVE_UNSUPPORTED_FORMAT_POLICY = "closest_supported"
const val DEFAULT_USB_EXCLUSIVE_DEVICE_KEY = "auto"
const val DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_COMPATIBILITY = true
const val DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_COMPATIBILITY = true
const val DEFAULT_USB_EXCLUSIVE_CHANNEL_COMPATIBILITY = true
const val DEFAULT_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS = 250
const val DEFAULT_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS = 1500
const val MIN_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS = 100
const val MIN_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS = 200
const val MIN_USB_EXCLUSIVE_BUFFER_MS = MIN_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS
const val MAX_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS = 1000
const val MAX_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS = 3000
const val MAX_USB_EXCLUSIVE_BUFFER_MS = MAX_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS
const val USB_EXCLUSIVE_BUFFER_STEP_MS = 50

enum class UsbExclusiveSampleRateMode(
    val storageValue: String,
    val sampleRateHz: Int?
) {
    FOLLOW_SOURCE("follow_source", null),
    RATE_44100("44100", 44_100),
    RATE_48000("48000", 48_000),
    RATE_88200("88200", 88_200),
    RATE_96000("96000", 96_000),
    RATE_176400("176400", 176_400),
    RATE_192000("192000", 192_000);

    fun requestedSampleRateHz(sourceSampleRateHz: Int): Int? {
        return sampleRateHz ?: sourceSampleRateHz.takeIf { it > 0 }
    }

    companion object {
        fun fromStorageValue(value: String?): UsbExclusiveSampleRateMode {
            return entries.findStoredValue(value, UsbExclusiveSampleRateMode::storageValue)
                ?: FOLLOW_SOURCE
        }
    }
}

enum class UsbExclusiveBitDepthMode(
    val storageValue: String,
    val bitDepth: Int?
) {
    AUTO("auto", null),
    BIT_16("16", 16),
    BIT_24("24", 24),
    BIT_32("32", 32);

    fun requestedBitDepth(sourceBitDepth: Int): Int? {
        return bitDepth ?: sourceBitDepth.takeIf { it > 0 }
    }

    companion object {
        fun fromStorageValue(value: String?): UsbExclusiveBitDepthMode {
            return entries.findStoredValue(value, UsbExclusiveBitDepthMode::storageValue) ?: AUTO
        }
    }
}

enum class UsbExclusiveBufferProfile(
    val storageValue: String,
    val bufferDurationMs: Int
) {
    LOW_LATENCY("low_latency", 3000),
    BALANCED("balanced", 5000),
    STABLE("stable", 12000);

    companion object {
        fun fromStorageValue(value: String?): UsbExclusiveBufferProfile {
            return entries.findStoredValue(value, UsbExclusiveBufferProfile::storageValue)
                ?: STABLE
        }
    }
}

enum class UsbExclusiveUnsupportedFormatPolicy(val storageValue: String) {
    SYSTEM_FALLBACK("system_fallback"),
    CLOSEST_SUPPORTED("closest_supported");

    companion object {
        fun fromStorageValue(value: String?): UsbExclusiveUnsupportedFormatPolicy {
            return entries.findStoredValue(
                value,
                UsbExclusiveUnsupportedFormatPolicy::storageValue
            ) ?: CLOSEST_SUPPORTED
        }
    }
}

data class UsbExclusivePreferences(
    val selectedDeviceKey: String = DEFAULT_USB_EXCLUSIVE_DEVICE_KEY,
    val sampleRateMode: UsbExclusiveSampleRateMode = UsbExclusiveSampleRateMode.FOLLOW_SOURCE,
    val bitDepthMode: UsbExclusiveBitDepthMode = UsbExclusiveBitDepthMode.AUTO,
    val bufferProfile: UsbExclusiveBufferProfile = UsbExclusiveBufferProfile.STABLE,
    val unsupportedFormatPolicy: UsbExclusiveUnsupportedFormatPolicy =
        UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED,
    val sampleRateCompatibilityEnabled: Boolean =
        DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_COMPATIBILITY,
    val bitDepthCompatibilityEnabled: Boolean =
        DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_COMPATIBILITY,
    val channelCompatibilityEnabled: Boolean =
        DEFAULT_USB_EXCLUSIVE_CHANNEL_COMPATIBILITY,
    val foregroundBufferMs: Int = DEFAULT_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS,
    val backgroundBufferMs: Int = DEFAULT_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS
) {
    fun resolveSampleRateHz(
        sourceSampleRateHz: Int,
        supportedSampleRatesHz: Collection<Int>
    ): Int? {
        val requested = sampleRateMode.requestedSampleRateHz(sourceSampleRateHz) ?: return null
        val normalizedSupported = supportedSampleRatesHz
            .asSequence()
            .filter { it > 0 }
            .distinct()
            .toList()
        if (!sampleRateCompatibilityEnabled) {
            return requested.takeIf { it in normalizedSupported }
        }
        if (
            sampleRateMode == UsbExclusiveSampleRateMode.FOLLOW_SOURCE &&
            unsupportedFormatPolicy == UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED &&
            requested !in normalizedSupported
        ) {
            val sourceFamily = sampleRateFamily(requested)
            if (sourceFamily != null) {
                normalizedSupported
                    .filter { sampleRateFamily(it) == sourceFamily }
                    .nearestTo(requested)
                    ?.let { return it }
            }
        }
        return resolveSupportedValue(
            requested = requested,
            supportedValues = normalizedSupported,
            unsupportedFormatPolicy = unsupportedFormatPolicy
        )
    }

    fun resolveBitDepth(
        sourceBitDepth: Int,
        supportedBitDepths: Collection<Int>
    ): Int? {
        val requested = bitDepthMode.requestedBitDepth(sourceBitDepth) ?: return null
        val normalizedSupported = supportedBitDepths
            .asSequence()
            .filter { it > 0 }
            .distinct()
            .toList()
        if (!bitDepthCompatibilityEnabled) {
            return requested.takeIf { it in normalizedSupported }
        }
        if (bitDepthMode == UsbExclusiveBitDepthMode.AUTO) {
            normalizedSupported
                .filter { it >= requested }
                .minOrNull()
                ?.let { return it }
        }
        return resolveSupportedValue(
            requested = requested,
            supportedValues = normalizedSupported,
            unsupportedFormatPolicy = unsupportedFormatPolicy
        )
    }

    fun resolveChannelCount(
        sourceChannelCount: Int,
        supportedChannelCounts: Collection<Int>
    ): Int? {
        val normalizedSupported = supportedChannelCounts
            .asSequence()
            .filter { it > 0 }
            .distinct()
            .toList()
        if (normalizedSupported.isEmpty()) {
            return sourceChannelCount.takeIf { it in 1..2 } ?: 2
        }
        if (!channelCompatibilityEnabled) {
            return sourceChannelCount.takeIf { it in normalizedSupported && it in 1..2 }
        }
        return when {
            2 in normalizedSupported -> 2
            sourceChannelCount in normalizedSupported && sourceChannelCount in 1..2 ->
                sourceChannelCount
            else -> null
        }
    }

    fun bufferDurationMs(appInForeground: Boolean): Int {
        val requested = if (appInForeground) foregroundBufferMs else backgroundBufferMs
        return if (appInForeground) {
            normalizeUsbExclusiveForegroundBufferMs(requested)
        } else {
            normalizeUsbExclusiveBackgroundBufferMs(requested)
        }
    }

    companion object {
        fun fromStorageValues(
            sampleRateMode: String?,
            bitDepthMode: String?,
            bufferProfile: String?,
            unsupportedFormatPolicy: String?,
            selectedDeviceKey: String? = null,
            sampleRateCompatibilityEnabled: Boolean? = null,
            bitDepthCompatibilityEnabled: Boolean? = null,
            channelCompatibilityEnabled: Boolean? = null,
            foregroundBufferMs: Int? = null,
            backgroundBufferMs: Int? = null
        ): UsbExclusivePreferences {
            val parsedBufferProfile = UsbExclusiveBufferProfile.fromStorageValue(bufferProfile)
            val hasValidStoredBufferProfile = bufferProfile
                ?.trim()
                ?.let { storedValue ->
                    UsbExclusiveBufferProfile.entries.any { profile ->
                        profile.storageValue.equals(storedValue, ignoreCase = true) ||
                            profile.name.equals(storedValue, ignoreCase = true)
                    }
                } == true
            val parsed = UsbExclusivePreferences(
                selectedDeviceKey = normalizeUsbExclusiveDeviceKey(selectedDeviceKey),
                sampleRateMode = UsbExclusiveSampleRateMode.fromStorageValue(sampleRateMode),
                bitDepthMode = UsbExclusiveBitDepthMode.fromStorageValue(bitDepthMode),
                bufferProfile = parsedBufferProfile,
                unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.fromStorageValue(
                    unsupportedFormatPolicy
                ),
                sampleRateCompatibilityEnabled = sampleRateCompatibilityEnabled
                    ?: DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_COMPATIBILITY,
                bitDepthCompatibilityEnabled = bitDepthCompatibilityEnabled
                    ?: DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_COMPATIBILITY,
                channelCompatibilityEnabled = channelCompatibilityEnabled
                    ?: DEFAULT_USB_EXCLUSIVE_CHANNEL_COMPATIBILITY,
                foregroundBufferMs = normalizeUsbExclusiveForegroundBufferMs(
                    foregroundBufferMs ?: if (hasValidStoredBufferProfile) {
                        parsedBufferProfile.bufferDurationMs
                    } else {
                        DEFAULT_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS
                    }
                ),
                backgroundBufferMs = normalizeUsbExclusiveBackgroundBufferMs(
                    backgroundBufferMs ?: DEFAULT_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS
                )
            )
            val legacyDefaultPolicy = unsupportedFormatPolicy == null &&
                parsed.sampleRateMode == UsbExclusiveSampleRateMode.FOLLOW_SOURCE &&
                parsed.bitDepthMode == UsbExclusiveBitDepthMode.AUTO &&
                parsed.bufferProfile == UsbExclusiveBufferProfile.BALANCED &&
                parsed.unsupportedFormatPolicy == UsbExclusiveUnsupportedFormatPolicy.SYSTEM_FALLBACK
            return if (legacyDefaultPolicy) {
                parsed.copy(
                    unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED
                )
            } else {
                parsed
            }
        }
    }
}

fun PlaybackPreferenceSnapshot.toUsbExclusivePreferences(): UsbExclusivePreferences {
    val normalizedSnapshot = sanitized()
    return UsbExclusivePreferences.fromStorageValues(
        selectedDeviceKey = normalizedSnapshot.usbExclusiveDeviceKey,
        sampleRateMode = normalizedSnapshot.usbExclusiveSampleRateMode,
        bitDepthMode = normalizedSnapshot.usbExclusiveBitDepthMode,
        bufferProfile = normalizedSnapshot.usbExclusiveBufferProfile,
        unsupportedFormatPolicy = normalizedSnapshot.usbExclusiveUnsupportedFormatPolicy,
        sampleRateCompatibilityEnabled =
            normalizedSnapshot.usbExclusiveSampleRateCompatibility,
        bitDepthCompatibilityEnabled =
            normalizedSnapshot.usbExclusiveBitDepthCompatibility,
        channelCompatibilityEnabled =
            normalizedSnapshot.usbExclusiveChannelCompatibility,
        foregroundBufferMs = normalizedSnapshot.usbExclusiveForegroundBufferMs,
        backgroundBufferMs = normalizedSnapshot.usbExclusiveBackgroundBufferMs
    )
}

fun normalizeUsbExclusiveDeviceKey(value: String?): String {
    return value?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals(DEFAULT_USB_EXCLUSIVE_DEVICE_KEY, ignoreCase = true) }
        ?: DEFAULT_USB_EXCLUSIVE_DEVICE_KEY
}

fun normalizeUsbExclusiveForegroundBufferMs(value: Int): Int {
    return normalizeUsbExclusiveBufferMs(
        value = value,
        minBufferMs = MIN_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS,
        maxBufferMs = MAX_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS
    )
}

fun normalizeUsbExclusiveBackgroundBufferMs(value: Int): Int {
    return normalizeUsbExclusiveBufferMs(
        value = value,
        minBufferMs = MIN_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS,
        maxBufferMs = MAX_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS
    )
}

fun normalizeUsbExclusiveBufferMs(value: Int): Int {
    return normalizeUsbExclusiveForegroundBufferMs(value)
}

private fun normalizeUsbExclusiveBufferMs(
    value: Int,
    minBufferMs: Int,
    maxBufferMs: Int
): Int {
    val clamped = value.coerceIn(minBufferMs, maxBufferMs)
    val rounded = (clamped / USB_EXCLUSIVE_BUFFER_STEP_MS) * USB_EXCLUSIVE_BUFFER_STEP_MS
    return rounded.coerceIn(minBufferMs, maxBufferMs)
}

private fun resolveSupportedValue(
    requested: Int,
    supportedValues: Collection<Int>,
    unsupportedFormatPolicy: UsbExclusiveUnsupportedFormatPolicy
): Int? {
    val candidates = supportedValues.asSequence()
        .filter { it > 0 }
        .distinct()
        .toList()
    if (requested in candidates) {
        return requested
    }
    if (unsupportedFormatPolicy == UsbExclusiveUnsupportedFormatPolicy.SYSTEM_FALLBACK) {
        return null
    }
    return candidates.nearestTo(requested)
}

private fun Collection<Int>.nearestTo(requested: Int): Int? {
    return minWithOrNull(
        compareBy<Int> { abs(it.toLong() - requested.toLong()) }
            .thenByDescending { it }
    )
}

private fun sampleRateFamily(sampleRateHz: Int): Int? {
    return when {
        sampleRateHz > 0 && sampleRateHz % 44_100 == 0 -> 44_100
        sampleRateHz > 0 && sampleRateHz % 48_000 == 0 -> 48_000
        else -> null
    }
}

private fun <T : Enum<T>> Iterable<T>.findStoredValue(
    value: String?,
    storageValue: (T) -> String
): T? {
    return value?.trim()?.let { candidate ->
        firstOrNull { preference ->
            storageValue(preference).equals(candidate, ignoreCase = true) ||
                preference.name.equals(candidate, ignoreCase = true)
        }
    }
}
