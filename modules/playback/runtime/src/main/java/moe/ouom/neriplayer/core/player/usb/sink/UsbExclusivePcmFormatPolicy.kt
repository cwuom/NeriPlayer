@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.usb.sink

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlin.math.max
import moe.ouom.neriplayer.common.R as CoreCommonR

internal fun isNativePcmFormat(format: Format): Boolean {
    return MimeTypes.AUDIO_RAW == format.sampleMimeType &&
        format.sampleRate > 0 &&
        format.channelCount > 0 &&
        format.channelCount <= 8 &&
        pcmFrameBytes(format.pcmEncoding, format.channelCount) > 0
}

internal fun isUsbNativePcmFormat(format: Format): Boolean {
    return isNativePcmFormat(format)
}

internal fun inputFormatDescription(format: Format): String {
    return "mime=${format.sampleMimeType ?: "unknown"} sampleRate=${format.sampleRate} " +
        "channels=${format.channelCount} encoding=${format.pcmEncoding}"
}

internal fun nativeFormatWarningMessageResId(reason: String): Int? = when {
    reason.startsWith("sample_rate_unsupported") ->
        CoreCommonR.string.settings_usb_exclusive_issue_sample_rate
    reason.startsWith("bit_depth_unsupported") ->
        CoreCommonR.string.settings_usb_exclusive_issue_bit_depth
    reason.startsWith("channel_count_unsupported") ->
        CoreCommonR.string.settings_usb_exclusive_issue_device
    else -> null
}

internal fun pcmFrameBytes(encoding: Int, channels: Int): Int {
    val bytesPerSample = when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT,
        C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
        C.ENCODING_PCM_24BIT,
        C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
        C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_32BIT_BIG_ENDIAN,
        C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }
    return bytesPerSample * max(0, channels)
}
