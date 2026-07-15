@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.usb.sink

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import androidx.media3.common.C
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.settings.UsbExclusivePreferences
import moe.ouom.neriplayer.data.settings.UsbExclusiveSampleRateMode

internal data class ResolvedUsbOutputFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val bitDepth: Int,
    val subslotBytes: Int,
    val bufferDurationMs: Int,
    val description: String
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

        val requestedRate = preferences.sampleRateMode.requestedSampleRateHz(inputSampleRate)
            ?: return failure("invalid_source_sample_rate")
        val reportedRates = output.sampleRates.filter { it > 0 }
        val supportedRates = reportedRates.ifEmpty { listOf(requestedRate) }
        val resolvedRate = preferences.resolveSampleRateHz(inputSampleRate, supportedRates)
            ?: return failure(
                "sample_rate_unsupported:requested=$requestedRate supported=$reportedRates"
            )
        if (shouldBlockImplicitNativeSampleRateConversion(preferences, inputSampleRate, resolvedRate)) {
            return failure(
                "sample_rate_unsupported:requested=$inputSampleRate " +
                    "resolved=$resolvedRate native_resample_blocked"
            )
        }

        val sourceBitDepth = sourceBitDepthForEncoding(inputEncoding)
            ?: return failure("unsupported_input_encoding:$inputEncoding")
        val reportedBitDepths = output.encodings
            .map(::bitDepthForAudioFormatEncoding)
            .filterNotNull()
            .distinct()
        val supportedBitDepths = reportedBitDepths.ifEmpty {
            optimisticBitDepthsForSource(sourceBitDepth)
        }
        val resolvedBitDepth = preferences.resolveBitDepth(sourceBitDepth, supportedBitDepths)
            ?: return failure(
                "bit_depth_unsupported:requested=$sourceBitDepth supported=$reportedBitDepths"
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
                bufferDurationMs = preferences.bufferDurationMs(
                    appInForeground = PlayerManager.usbExclusiveAppInForeground
                ),
                description = buildDescription(
                    sampleRate = resolvedRate,
                    channelCount = resolvedChannels,
                    bitDepth = resolvedBitDepth,
                    subslotBytes = subslotBytes,
                    rateMode = preferences.sampleRateMode.storageValue,
                    bitMode = preferences.bitDepthMode.storageValue,
                    policy = preferences.unsupportedFormatPolicy.storageValue
                )
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

    internal fun shouldBlockImplicitNativeSampleRateConversion(
        preferences: UsbExclusivePreferences,
        inputSampleRate: Int,
        resolvedSampleRate: Int
    ): Boolean {
        return preferences.sampleRateMode == UsbExclusiveSampleRateMode.FOLLOW_SOURCE &&
            inputSampleRate > 0 &&
            resolvedSampleRate > 0 &&
            resolvedSampleRate != inputSampleRate
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
        preferred: ResolvedUsbOutputFormat,
        inputEncoding: Int
    ): List<ResolvedUsbOutputFormat> {
        val sourceBitDepth = sourceBitDepthForEncoding(inputEncoding) ?: preferred.bitDepth
        val candidates = LinkedHashMap<String, ResolvedUsbOutputFormat>()
        val rateMode = candidateRateMode(preferred.description)
        val bitMode = candidateBitMode(preferred.description)
        val policy = candidatePolicy(preferred.description)
        val allowLowerBitDepthFallback = policy != "system_fallback"
        val candidateRates = listOf(preferred.sampleRate).filter { it > 0 }

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
                    if (allowLowerBitDepthFallback) {
                        addCandidate(sampleRate = sampleRate, bitDepth = 16, subslotBytes = 2)
                    }
                }
                32 -> {
                    addCandidate(sampleRate = sampleRate, bitDepth = 32, subslotBytes = 4)
                    if (allowLowerBitDepthFallback && sourceBitDepth >= 24) {
                        addCandidate(sampleRate = sampleRate, bitDepth = 24, subslotBytes = 3)
                        addCandidate(sampleRate = sampleRate, bitDepth = 24, subslotBytes = 4)
                    }
                    if (allowLowerBitDepthFallback) {
                        addCandidate(sampleRate = sampleRate, bitDepth = 16, subslotBytes = 2)
                    }
                }
                16 -> addCandidate(sampleRate = sampleRate, bitDepth = 16, subslotBytes = 2)
            }
        }
        return candidates.values.toList()
    }

    private fun bitDepthForAudioFormatEncoding(encoding: Int): Int? {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT -> 32
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> null
        }
    }

    private fun optimisticBitDepthsForSource(sourceBitDepth: Int): List<Int> {
        return when {
            sourceBitDepth >= 32 -> listOf(32, 24, 16)
            sourceBitDepth >= 24 -> listOf(24, 32, 16)
            sourceBitDepth >= 16 -> listOf(16)
            else -> listOf(sourceBitDepth)
        }
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
}
