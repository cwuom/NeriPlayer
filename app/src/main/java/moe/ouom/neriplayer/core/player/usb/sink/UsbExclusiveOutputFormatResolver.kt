@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.usb.sink

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.C
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.settings.UsbExclusivePreferences
import moe.ouom.neriplayer.data.settings.UsbExclusiveSampleRateMode
import moe.ouom.neriplayer.data.settings.UsbExclusiveUnsupportedFormatPolicy

internal data class ResolvedUsbOutputFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val bitDepth: Int,
    val subslotBytes: Int,
    val bufferDurationMs: Int,
    val description: String,
    val alternativeSampleRates: List<Int> = emptyList(),
    val allowBitDepthFallback: Boolean = true
)

internal data class UsbOutputFormatResolution(
    val format: ResolvedUsbOutputFormat? = null,
    val error: String? = null
)

internal data class PreparedUsbInputPcmFormat(
    val encoding: Int,
    val bytesPerSample: Int
)

internal fun describeUsbInputFormat(
    sampleRate: Int,
    channelCount: Int,
    encoding: Int
): String = "rate=$sampleRate channels=$channelCount encoding=$encoding"

internal object UsbExclusiveOutputFormatResolver {
    private const val DEFAULT_USB_EXCLUSIVE_DEVICE_KEY = "auto"
    private const val MAX_COMPATIBLE_FALLBACK_SAMPLE_RATES = 12
    private data class OutputDescription(
        val sampleRate: Int,
        val channelCount: Int,
        val bitDepth: Int,
        val subslotBytes: Int
    )

    private data class ParsedUsbExclusiveDeviceKey(
        val label: String
    )

    private fun isUsbOutputType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun AudioDeviceInfo.matchesUsbExclusiveDeviceKey(deviceKey: String): Boolean {
        if (deviceKey == DEFAULT_USB_EXCLUSIVE_DEVICE_KEY) return true
        val selection = parseUsbExclusiveDeviceKey(deviceKey) ?: return false
        val normalizedProduct = normalizedDeviceLabel(productName?.toString().orEmpty())
        return normalizedProduct.isNotBlank() &&
            (normalizedProduct == selection.label || normalizedProduct.contains(selection.label))
    }

    private fun parseUsbExclusiveDeviceKey(deviceKey: String): ParsedUsbExclusiveDeviceKey? {
        val parts = deviceKey.split(':', limit = 4)
        if (parts.size != 4 || parts[0] != "usb") return null
        val label = parts[3].takeIf(String::isNotBlank) ?: return null
        return ParsedUsbExclusiveDeviceKey(label = label)
    }

    private fun normalizedDeviceLabel(value: String): String {
        return value.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    fun resolve(
        context: Context,
        inputSampleRate: Int,
        inputChannelCount: Int,
        inputEncoding: Int
    ): UsbOutputFormatResolution {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return failure("audio_manager_unavailable")
        val preferences = if (PlayerManager.isPlayerInitialized()) {
            PlayerManager.usbExclusivePreferences
        } else {
            UsbExclusivePreferences()
        }
        val usbOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { device -> device.isSink && isUsbOutputType(device.type) }
            .sortedBy(AudioDeviceInfo::getId)
        val output = usbOutputs
            .firstOrNull { device ->
                device.matchesUsbExclusiveDeviceKey(preferences.selectedDeviceKey)
            }
            ?: usbOutputs.singleOrNull()
            ?: return failure("no_selected_system_usb_audio_output")

        val reportedRates = output.sampleRates.filter { it > 0 }
        val sampleRateCandidates = nativeSampleRateCandidates(
            preferences = preferences,
            inputSampleRate = inputSampleRate,
            reportedSampleRates = reportedRates
        )
        val resolvedRate = sampleRateCandidates.firstOrNull()
            ?: return failure("invalid_source_sample_rate")

        val sourceBitDepth = sourceBitDepthForEncoding(inputEncoding)
            ?: return failure("unsupported_input_encoding:$inputEncoding")
        val resolvedBitDepth = preferredNativeBitDepth(preferences, sourceBitDepth)
            ?: return failure(
                "bit_depth_unsupported:requested=$sourceBitDepth native=16,24,32"
            )

        val reportedChannels = output.channelCounts.filter { it > 0 }
        val resolvedChannels = preferences.resolveChannelCount(
            sourceChannelCount = inputChannelCount,
            supportedChannelCounts = reportedChannels
        ) ?: return failure(
            "channel_count_unsupported:input=$inputChannelCount supported=$reportedChannels"
        )
        val subslotBytes = when (resolvedBitDepth) {
            16 -> 2
            24 -> 3
            32 -> 4
            else -> return failure("native_bit_depth_unsupported:$resolvedBitDepth")
        }
        return UsbOutputFormatResolution(
            format = ResolvedUsbOutputFormat(
                sampleRate = resolvedRate,
                channelCount = resolvedChannels,
                bitDepth = resolvedBitDepth,
                subslotBytes = subslotBytes,
                bufferDurationMs = preferences.reservedBufferDurationMs(),
                description = buildDescription(
                    sampleRate = resolvedRate,
                    channelCount = resolvedChannels,
                    bitDepth = resolvedBitDepth,
                    subslotBytes = subslotBytes,
                    rateMode = preferences.sampleRateMode.storageValue,
                    bitMode = preferences.bitDepthMode.storageValue,
                    policy = preferences.unsupportedFormatPolicy.storageValue
                ),
                alternativeSampleRates = sampleRateCandidates.drop(1),
                allowBitDepthFallback = preferences.bitDepthCompatibilityEnabled &&
                    preferences.unsupportedFormatPolicy ==
                    UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED
            )
        )
    }

    private fun failure(error: String): UsbOutputFormatResolution {
        return UsbOutputFormatResolution(error = error)
    }

    internal fun sourceBitDepthForEncoding(encoding: Int): Int? {
        return when (encoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
            // Media3 float 保留了源音频的完整精度，优先按 32-bit 申请原生 USB 输出
            C.ENCODING_PCM_FLOAT -> 32
            else -> null
        }
    }

    internal fun nativeSampleRateCandidates(
        preferences: UsbExclusivePreferences,
        inputSampleRate: Int,
        reportedSampleRates: Collection<Int>
    ): List<Int> {
        val requested = preferences.sampleRateMode.requestedSampleRateHz(inputSampleRate)
            ?: return emptyList()
        val normalizedReported = reportedSampleRates
            .asSequence()
            .filter { it > 0 }
            .distinct()
            .toList()
        if (!preferences.canTryCompatibleSampleRateFallbacks()) {
            return listOf(requested)
        }
        return buildList {
            add(requested)
            addAll(
                normalizedReported
                    .asSequence()
                    .filterNot { it == requested }
                    .sortedWith(sampleRateFallbackComparator(requested, preferences))
                    .take(MAX_COMPATIBLE_FALLBACK_SAMPLE_RATES)
                    .toList()
            )
        }.distinct()
    }

    private fun UsbExclusivePreferences.canTryCompatibleSampleRateFallbacks(): Boolean {
        return sampleRateCompatibilityEnabled &&
            unsupportedFormatPolicy == UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED
    }

    private fun sampleRateFallbackComparator(
        requestedRate: Int,
        preferences: UsbExclusivePreferences
    ): Comparator<Int> {
        val requestedFamily = if (
            preferences.sampleRateMode == UsbExclusiveSampleRateMode.FOLLOW_SOURCE
        ) {
            sampleRateFamily(requestedRate)
        } else {
            null
        }
        return compareBy<Int> {
            if (requestedFamily != null && sampleRateFamily(it) != requestedFamily) 1 else 0
        }.thenBy { kotlin.math.abs(it.toLong() - requestedRate.toLong()) }
            .thenByDescending { it }
    }

    private fun sampleRateFamily(sampleRateHz: Int): Int? {
        return when {
            sampleRateHz > 0 && sampleRateHz % 44_100 == 0 -> 44_100
            sampleRateHz > 0 && sampleRateHz % 48_000 == 0 -> 48_000
            else -> null
        }
    }

    internal fun preferredNativeBitDepth(
        preferences: UsbExclusivePreferences,
        sourceBitDepth: Int
    ): Int? {
        val requested = preferences.bitDepthMode.requestedBitDepth(sourceBitDepth) ?: return null
        if (requested in nativePcmBitDepths) return requested
        if (
            preferences.bitDepthCompatibilityEnabled &&
            preferences.unsupportedFormatPolicy ==
            UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED
        ) {
            return nativePcmBitDepths.minByOrNull { bitDepth ->
                kotlin.math.abs(bitDepth - requested)
            }
        }
        return null
    }

    internal fun pcmBytesPerSampleForEncoding(encoding: Int): Int? {
        return when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN,
            C.ENCODING_PCM_FLOAT -> 4
            else -> null
        }
    }

    internal fun preparedInputPcmFormat(
        inputEncoding: Int,
        outputFormat: ResolvedUsbOutputFormat
    ): PreparedUsbInputPcmFormat? {
        if (inputEncoding != C.ENCODING_PCM_FLOAT) {
            val bytesPerSample = pcmBytesPerSampleForEncoding(inputEncoding) ?: return null
            return PreparedUsbInputPcmFormat(
                encoding = inputEncoding,
                bytesPerSample = bytesPerSample
            )
        }
        return when (outputFormat.subslotBytes) {
            2 -> PreparedUsbInputPcmFormat(
                encoding = C.ENCODING_PCM_16BIT,
                bytesPerSample = 2
            )
            3 -> PreparedUsbInputPcmFormat(
                encoding = C.ENCODING_PCM_24BIT,
                bytesPerSample = 3
            )
            4 -> PreparedUsbInputPcmFormat(
                encoding = C.ENCODING_PCM_32BIT,
                bytesPerSample = 4
            )
            else -> null
        }
    }

    internal fun preparedInputPcmFormat(
        inputEncoding: Int,
        outputDescription: String
    ): PreparedUsbInputPcmFormat? {
        val output = parseOutputDescription(outputDescription) ?: return null
        return preparedInputPcmFormat(
            inputEncoding = inputEncoding,
            outputFormat = ResolvedUsbOutputFormat(
                sampleRate = output.sampleRate,
                channelCount = output.channelCount,
                bitDepth = output.bitDepth,
                subslotBytes = output.subslotBytes,
                bufferDurationMs = 0,
                description = outputDescription
            )
        )
    }

    internal fun openCandidates(
        preferred: ResolvedUsbOutputFormat
    ): List<ResolvedUsbOutputFormat> {
        val candidates = LinkedHashMap<String, ResolvedUsbOutputFormat>()
        val rateMode = candidateRateMode(preferred.description)
        val bitMode = candidateBitMode(preferred.description)
        val policy = candidatePolicy(preferred.description)
        val allowBitDepthFallback = preferred.allowBitDepthFallback &&
            policy != "system_fallback"
        val candidateRates = buildList {
            add(preferred.sampleRate)
            addAll(preferred.alternativeSampleRates)
        }.filter { it > 0 }.distinct()

        fun addCandidate(sampleRate: Int, bitDepth: Int, subslotBytes: Int) {
            val normalizedSubslotBytes = subslotBytes.coerceAtLeast((bitDepth + 7) / 8)
            val candidate = preferred.copy(
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                subslotBytes = normalizedSubslotBytes,
                description = buildDescription(
                    sampleRate = sampleRate,
                    channelCount = preferred.channelCount,
                    bitDepth = bitDepth,
                    subslotBytes = normalizedSubslotBytes,
                    rateMode = rateMode,
                    bitMode = bitMode,
                    policy = policy
                )
            )
            candidates.putIfAbsent(candidate.description, candidate)
        }

        candidateRates.forEach { sampleRate ->
            when (preferred.bitDepth) {
                24 -> {
                    addCandidate(
                        sampleRate = sampleRate,
                        bitDepth = 24,
                        subslotBytes = preferred.subslotBytes
                    )
                    addCandidate(sampleRate = sampleRate, bitDepth = 24, subslotBytes = 4)
                    addCandidate(sampleRate = sampleRate, bitDepth = 24, subslotBytes = 3)
                    if (allowBitDepthFallback) {
                        addCandidate(sampleRate = sampleRate, bitDepth = 32, subslotBytes = 4)
                        addCandidate(sampleRate = sampleRate, bitDepth = 16, subslotBytes = 2)
                    }
                }
                32 -> {
                    addCandidate(sampleRate = sampleRate, bitDepth = 32, subslotBytes = 4)
                    if (allowBitDepthFallback) {
                        addCandidate(sampleRate = sampleRate, bitDepth = 24, subslotBytes = 3)
                        addCandidate(sampleRate = sampleRate, bitDepth = 24, subslotBytes = 4)
                    }
                    if (allowBitDepthFallback) {
                        addCandidate(sampleRate = sampleRate, bitDepth = 16, subslotBytes = 2)
                    }
                }
                16 -> {
                    addCandidate(sampleRate = sampleRate, bitDepth = 16, subslotBytes = 2)
                    if (allowBitDepthFallback) {
                        addCandidate(sampleRate = sampleRate, bitDepth = 24, subslotBytes = 3)
                        addCandidate(sampleRate = sampleRate, bitDepth = 24, subslotBytes = 4)
                        addCandidate(sampleRate = sampleRate, bitDepth = 32, subslotBytes = 4)
                    }
                }
            }
        }
        return candidates.values.toList()
    }

    private fun candidateRateMode(description: String): String {
        return description.valueAfterDescriptionKey("rateMode") ?: "follow_source"
    }

    private fun candidateBitMode(description: String): String {
        return description.valueAfterDescriptionKey("bitMode") ?: "auto"
    }

    private fun candidatePolicy(description: String): String {
        return description.valueAfterDescriptionKey("policy") ?: "closest_supported"
    }

    internal fun canReuseEquivalentOutput(
        currentDescription: String,
        preferredDescription: String
    ): Boolean {
        if (currentDescription == preferredDescription) {
            return true
        }
        val current = parseOutputDescription(currentDescription) ?: return false
        val preferred = parseOutputDescription(preferredDescription) ?: return false
        if (current.sampleRate != preferred.sampleRate ||
            current.channelCount != preferred.channelCount ||
            current.bitDepth != preferred.bitDepth
        ) {
            return false
        }
        return current.subslotBytes == preferred.subslotBytes
    }

    internal fun outputFormatFromDescription(
        description: String,
        bufferDurationMs: Int
    ): ResolvedUsbOutputFormat? {
        val output = parseOutputDescription(description) ?: return null
        return ResolvedUsbOutputFormat(
            sampleRate = output.sampleRate,
            channelCount = output.channelCount,
            bitDepth = output.bitDepth,
            subslotBytes = output.subslotBytes,
            bufferDurationMs = bufferDurationMs,
            description = description
        )
    }

    private fun parseOutputDescription(description: String): OutputDescription? {
        val sampleRate = description.valueAfterDescriptionKey("rate")?.toIntOrNull()
            ?: return null
        val channelCount = description.valueAfterDescriptionKey("channels")?.toIntOrNull()
            ?: return null
        val bitDepth = description.valueAfterDescriptionKey("bits")?.toIntOrNull()
            ?: return null
        val subslotBytes = description.valueAfterDescriptionKey("subslot")?.toIntOrNull()
            ?: return null
        return OutputDescription(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitDepth = bitDepth,
            subslotBytes = subslotBytes
        )
    }

    private fun String.valueAfterDescriptionKey(key: String): String? {
        val regex = Regex("(?:^|\\s)${Regex.escape(key)}=([^\\s]+)")
        return regex.find(this)?.groupValues?.getOrNull(1)
    }

    private fun buildDescription(
        sampleRate: Int,
        channelCount: Int,
        bitDepth: Int,
        subslotBytes: Int,
        rateMode: String,
        bitMode: String,
        policy: String
    ): String {
        return "rate=$sampleRate channels=$channelCount bits=$bitDepth subslot=$subslotBytes " +
            "rateMode=$rateMode bitMode=$bitMode policy=$policy"
    }

    private val nativePcmBitDepths = listOf(16, 24, 32)
}
