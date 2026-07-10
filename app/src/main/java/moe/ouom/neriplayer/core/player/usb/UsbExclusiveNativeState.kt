package moe.ouom.neriplayer.core.player.usb

data class UsbExclusiveNativeState(
    val available: Boolean = false,
    val opened: Boolean = false,
    val streaming: Boolean = false,
    val paused: Boolean = false,
    val transitioning: Boolean = false,
    val source: String = "idle",
    val handle: Long = 0L,
    val selectedDeviceName: String? = null,
    val inputFormat: String = "none",
    val outputFormat: String = "none",
    val outputSampleRate: Int = 0,
    val bufferDurationMs: Int = 250,
    val completedAudioFrames: Long = 0L,
    val queuedAudioFrames: Long = 0L,
    val runtimeReport: String = "idle",
    val lastError: String? = null
)
