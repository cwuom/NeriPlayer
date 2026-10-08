package moe.ouom.neriplayer.data.model.playback.usb

data class UsbExclusiveAudioPathState(
    val requestedPath: String = REQUESTED_SYSTEM,
    val effectivePath: String = EFFECTIVE_SYSTEM,
    val fallbackReason: String? = null,
    val inputFormat: String = "none",
    val requestedPlaybackParameters: String = "speed=1.0 pitch=1.0",
    val skipSilence: Boolean = false,
    val sinkPlaying: Boolean = false,
    val nativePaused: Boolean = false,
    val requestedVolume: Float = 1f,
    val hardwareVolume: Boolean = false,
    val generation: Long = 0L
) {
    companion object {
        const val REQUESTED_SYSTEM = "SYSTEM_AUDIO"
        const val REQUESTED_NATIVE_USB = "NATIVE_USB"
        const val EFFECTIVE_SYSTEM = "SYSTEM_AUDIO"
        const val EFFECTIVE_NATIVE_USB = "NATIVE_USB"
    }
}
