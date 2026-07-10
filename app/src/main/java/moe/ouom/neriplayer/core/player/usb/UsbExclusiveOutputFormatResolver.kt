@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.usb

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import androidx.media3.common.C
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.audio.isUsbOutputType
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.data.settings.toUsbExclusivePreferences

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

internal fun describeUsbInputFormat(
    sampleRate: Int,
    channelCount: Int,
    encoding: Int
): String = "rate=$sampleRate channels=$channelCount encoding=$encoding"

internal object UsbExclusiveOutputFormatResolver {
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
            readPlaybackPreferenceSnapshotSync(context).toUsbExclusivePreferences()
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

        val sourceBitDepth = bitDepthForEncoding(inputEncoding)
            ?: return failure("unsupported_input_encoding:$inputEncoding")
        val reportedBitDepths = output.encodings
            .map(::bitDepthForAudioFormatEncoding)
            .filterNotNull()
            .distinct()
        val supportedBitDepths = reportedBitDepths.ifEmpty { listOf(16) }
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

    private fun bitDepthForEncoding(encoding: Int): Int? {
        return when (encoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN,
            C.ENCODING_PCM_FLOAT -> 32
            else -> null
        }
    }

    private fun bitDepthForAudioFormatEncoding(encoding: Int): Int? {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT -> 32
            else -> null
        }
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
