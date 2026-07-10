package moe.ouom.neriplayer.data.settings

import kotlin.math.abs

const val DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_MODE = "follow_source"
const val DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_MODE = "auto"
const val DEFAULT_USB_EXCLUSIVE_BUFFER_PROFILE = "balanced"
const val DEFAULT_USB_EXCLUSIVE_UNSUPPORTED_FORMAT_POLICY = "closest_supported"
const val DEFAULT_USB_EXCLUSIVE_DEVICE_KEY = "auto"

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
    LOW_LATENCY("low_latency", 80),
    BALANCED("balanced", 250),
    STABLE("stable", 750);

    companion object {
        fun fromStorageValue(value: String?): UsbExclusiveBufferProfile {
            return entries.findStoredValue(value, UsbExclusiveBufferProfile::storageValue)
                ?: BALANCED
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
    val bufferProfile: UsbExclusiveBufferProfile = UsbExclusiveBufferProfile.BALANCED,
    val unsupportedFormatPolicy: UsbExclusiveUnsupportedFormatPolicy =
        UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED
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

    companion object {
        fun fromStorageValues(
            sampleRateMode: String?,
            bitDepthMode: String?,
            bufferProfile: String?,
            unsupportedFormatPolicy: String?,
            selectedDeviceKey: String? = null
        ): UsbExclusivePreferences {
            val parsed = UsbExclusivePreferences(
                selectedDeviceKey = normalizeUsbExclusiveDeviceKey(selectedDeviceKey),
                sampleRateMode = UsbExclusiveSampleRateMode.fromStorageValue(sampleRateMode),
                bitDepthMode = UsbExclusiveBitDepthMode.fromStorageValue(bitDepthMode),
                bufferProfile = UsbExclusiveBufferProfile.fromStorageValue(bufferProfile),
                unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.fromStorageValue(
                    unsupportedFormatPolicy
                )
            )
            val legacyDefaultPolicy = parsed.sampleRateMode == UsbExclusiveSampleRateMode.FOLLOW_SOURCE &&
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
        unsupportedFormatPolicy = normalizedSnapshot.usbExclusiveUnsupportedFormatPolicy
    )
}

fun normalizeUsbExclusiveDeviceKey(value: String?): String {
    return value?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals(DEFAULT_USB_EXCLUSIVE_DEVICE_KEY, ignoreCase = true) }
        ?: DEFAULT_USB_EXCLUSIVE_DEVICE_KEY
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
