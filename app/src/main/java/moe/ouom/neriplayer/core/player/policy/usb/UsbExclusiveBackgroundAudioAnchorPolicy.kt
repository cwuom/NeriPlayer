package moe.ouom.neriplayer.core.player.policy.usb

internal enum class UsbExclusiveBackgroundAudioAnchorTransferMode {
    StaticLoop,
    Streaming
}

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
