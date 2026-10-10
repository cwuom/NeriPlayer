package moe.ouom.neriplayer.core.player.policy.usb

import android.media.AudioDeviceInfo

internal enum class UsbExclusiveBackgroundAudioAnchorTransferMode {
    StaticLoop,
    Streaming
}

private const val USB_EXCLUSIVE_BACKGROUND_ANCHOR_BYTES_PER_SAMPLE = 2
// preserve a nonzero marker after ordinary media-gain quantization
private const val USB_EXCLUSIVE_BACKGROUND_ANCHOR_CARRIER_AMPLITUDE = 256

internal data class UsbExclusiveBackgroundAudioAnchorSpec(
    val name: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val bufferFrames: Int,
    val transferMode: UsbExclusiveBackgroundAudioAnchorTransferMode
)

internal fun shouldRunUsbExclusiveBackgroundAudioAnchor(
    appInForeground: Boolean,
    serviceForeground: Boolean,
    usbExclusivePlaybackActive: Boolean
): Boolean {
    return !appInForeground && serviceForeground && usbExclusivePlaybackActive
}

internal fun usbExclusiveBackgroundAudioAnchorCarrier(
    bufferBytes: Int,
    channelCount: Int
): ByteArray {
    if (bufferBytes <= 0 || channelCount <= 0) return ByteArray(0)

    val carrier = ByteArray(bufferBytes)
    val bytesPerFrame = channelCount * USB_EXCLUSIVE_BACKGROUND_ANCHOR_BYTES_PER_SAMPLE
    var sample = USB_EXCLUSIVE_BACKGROUND_ANCHOR_CARRIER_AMPLITUDE
    var frameOffset = 0
    while (frameOffset + bytesPerFrame <= carrier.size) {
        repeat(channelCount) { channel ->
            val sampleOffset = frameOffset + channel * USB_EXCLUSIVE_BACKGROUND_ANCHOR_BYTES_PER_SAMPLE
            carrier[sampleOffset] = sample.toByte()
            carrier[sampleOffset + 1] = (sample shr 8).toByte()
        }
        sample = -sample
        frameOffset += bytesPerFrame
    }
    return carrier
}

/** 载波只写到确认的内置扬声器路由；USB 等外部路由一律静音，避免独占释放后漏到 DAC */
internal fun shouldWriteUsbExclusiveBackgroundAudioAnchorCarrier(
    transferMode: UsbExclusiveBackgroundAudioAnchorTransferMode,
    routedToBuiltInOutput: Boolean
): Boolean {
    return transferMode == UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming &&
        routedToBuiltInOutput
}

internal fun isUsbExclusiveBackgroundAudioAnchorBuiltInRoute(routedOutputType: Int?): Boolean =
    routedOutputType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

internal fun usbExclusiveBackgroundAudioAnchorSpecs(): List<UsbExclusiveBackgroundAudioAnchorSpec> {
    return listOf(
        UsbExclusiveBackgroundAudioAnchorSpec(
            name = "stream_48k_stereo",
            sampleRateHz = 48_000,
            channelCount = 2,
            bufferFrames = 4_800,
            transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming
        ),
        UsbExclusiveBackgroundAudioAnchorSpec(
            name = "stream_48k_mono",
            sampleRateHz = 48_000,
            channelCount = 1,
            bufferFrames = 4_800,
            transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming
        ),
        UsbExclusiveBackgroundAudioAnchorSpec(
            name = "stream_44k_stereo",
            sampleRateHz = 44_100,
            channelCount = 2,
            bufferFrames = 4_410,
            transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming
        ),
        UsbExclusiveBackgroundAudioAnchorSpec(
            name = "stream_44k_mono",
            sampleRateHz = 44_100,
            channelCount = 1,
            bufferFrames = 4_410,
            transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming
        ),
        UsbExclusiveBackgroundAudioAnchorSpec(
            name = "stream_96k_stereo",
            sampleRateHz = 96_000,
            channelCount = 2,
            bufferFrames = 9_600,
            transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming
        )
    )
}
