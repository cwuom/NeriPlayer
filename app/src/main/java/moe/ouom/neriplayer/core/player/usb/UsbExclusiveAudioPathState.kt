package moe.ouom.neriplayer.core.player.usb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    val generation: Long = 0L
) {
    companion object {
        const val REQUESTED_SYSTEM = "SYSTEM_AUDIO"
        const val REQUESTED_NATIVE_USB = "NATIVE_USB"
        const val EFFECTIVE_SYSTEM = "SYSTEM_AUDIO"
        const val EFFECTIVE_NATIVE_USB = "NATIVE_USB"
    }
}

object UsbExclusiveAudioPathTracker {
    private val _state = MutableStateFlow(UsbExclusiveAudioPathState())
    val state: StateFlow<UsbExclusiveAudioPathState> = _state.asStateFlow()
    @Volatile
    private var forcedSystemFallbackReason: String? = null

    fun updateRequested(enabled: Boolean) {
        forcedSystemFallbackReason = null
        _state.update { current ->
            current.copy(
                requestedPath = if (enabled) {
                    UsbExclusiveAudioPathState.REQUESTED_NATIVE_USB
                } else {
                    UsbExclusiveAudioPathState.REQUESTED_SYSTEM
                },
                fallbackReason = null,
                generation = current.generation + 1L
            )
        }
    }

    fun forceSystemFallback(reason: String) {
        forcedSystemFallbackReason = reason
        _state.update { current -> current.copy(fallbackReason = reason) }
    }

    fun clearForcedSystemFallback() {
        forcedSystemFallbackReason = null
        _state.update { current -> current.copy(fallbackReason = null) }
    }

    fun forcedSystemFallbackReason(): String? = forcedSystemFallbackReason

    fun updateConfigured(
        usingNative: Boolean,
        fallbackReason: String?,
        inputFormat: String
    ) {
        _state.update { current ->
            current.copy(
                effectivePath = if (usingNative) {
                    UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB
                } else {
                    UsbExclusiveAudioPathState.EFFECTIVE_SYSTEM
                },
                fallbackReason = fallbackReason,
                inputFormat = inputFormat,
                nativePaused = usingNative && !current.sinkPlaying,
                generation = current.generation + 1L
            )
        }
    }

    fun updatePlaybackParameters(speed: Float, pitch: Float) {
        _state.update { current ->
            current.copy(requestedPlaybackParameters = "speed=$speed pitch=$pitch")
        }
    }

    fun updateSkipSilence(enabled: Boolean) {
        _state.update { current -> current.copy(skipSilence = enabled) }
    }

    fun updatePlaying(playing: Boolean, usingNative: Boolean) {
        _state.update { current ->
            current.copy(
                sinkPlaying = playing,
                nativePaused = usingNative && !playing
            )
        }
    }

    fun updateNativePaused(paused: Boolean, sinkPlaying: Boolean) {
        _state.update { current ->
            current.copy(
                sinkPlaying = sinkPlaying,
                nativePaused = paused
            )
        }
    }

    fun updateVolume(volume: Float) {
        _state.update { current -> current.copy(requestedVolume = volume) }
    }
}
