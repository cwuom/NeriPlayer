package moe.ouom.neriplayer.core.player.policy.usb

internal enum class UsbExclusiveBackgroundAudioAnchorTransferMode {
    StaticLoop,
    Streaming
}

private const val USB_EXCLUSIVE_BACKGROUND_ANCHOR_BYTES_PER_SAMPLE = 2
private const val USB_EXCLUSIVE_BACKGROUND_ANCHOR_CARRIER_AMPLITUDE = 1

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

internal fun usbExclusiveBackgroundAudioAnchorSpecs(): List<UsbExclusiveBackgroundAudioAnchorSpec> {
    return listOf(
        UsbExclusiveBackgroundAudioAnchorSpec(
            name = "static_48k_stereo",
            sampleRateHz = 48_000,
            channelCount = 2,
            bufferFrames = 4_800,
            transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.StaticLoop
        ),
        UsbExclusiveBackgroundAudioAnchorSpec(
            name = "static_48k_mono",
            sampleRateHz = 48_000,
            channelCount = 1,
            bufferFrames = 4_800,
            transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.StaticLoop
        ),
        UsbExclusiveBackgroundAudioAnchorSpec(
            name = "static_44k_stereo",
            sampleRateHz = 44_100,
            channelCount = 2,
            bufferFrames = 4_410,
            transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.StaticLoop
        ),
        UsbExclusiveBackgroundAudioAnchorSpec(
            name = "static_44k_mono",
            sampleRateHz = 44_100,
            channelCount = 1,
            bufferFrames = 4_410,
            transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.StaticLoop
        ),
        UsbExclusiveBackgroundAudioAnchorSpec(
            name = "stream_48k_stereo",
            sampleRateHz = 48_000,
            channelCount = 2,
            bufferFrames = 12_000,
            transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming
        ),
        UsbExclusiveBackgroundAudioAnchorSpec(
            name = "stream_48k_mono",
            sampleRateHz = 48_000,
            channelCount = 1,
            bufferFrames = 12_000,
            transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming
        ),
        UsbExclusiveBackgroundAudioAnchorSpec(
            name = "stream_96k_stereo",
            sampleRateHz = 96_000,
            channelCount = 2,
            bufferFrames = 24_000,
            transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming
        )
    )
}
