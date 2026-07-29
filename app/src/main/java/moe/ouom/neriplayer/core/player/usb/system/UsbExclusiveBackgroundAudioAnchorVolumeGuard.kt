package moe.ouom.neriplayer.core.player.usb.system

import android.content.Context
import android.media.AudioManager

internal data class UsbExclusiveBackgroundAudioAnchorVolumeGuardToken(
    val generation: Long
)

internal class UsbExclusiveBackgroundAudioAnchorVolumeGuardState {
    private data class ActiveSnapshot(
        val token: UsbExclusiveBackgroundAudioAnchorVolumeGuardToken,
        val volumeFraction: Float
    )

    private var nextGeneration = 0L
    private var activeSnapshot: ActiveSnapshot? = null

    fun acquire(volumeFraction: Float): UsbExclusiveBackgroundAudioAnchorVolumeGuardToken {
        val token = UsbExclusiveBackgroundAudioAnchorVolumeGuardToken(++nextGeneration)
        activeSnapshot = ActiveSnapshot(
            token = token,
            volumeFraction = volumeFraction.coerceIn(0f, 1f)
        )
        return token
    }

    fun currentVolumeFractionOrNull(): Float? = activeSnapshot?.volumeFraction

    fun release(token: UsbExclusiveBackgroundAudioAnchorVolumeGuardToken) {
        if (activeSnapshot?.token == token) {
            activeSnapshot = null
        }
    }
}

internal object UsbExclusiveBackgroundAudioAnchorVolumeGuard {
    private val lock = Any()
    private val state = UsbExclusiveBackgroundAudioAnchorVolumeGuardState()

    fun acquire(context: Context): UsbExclusiveBackgroundAudioAnchorVolumeGuardToken? {
        val volumeFraction = readMusicVolumeFraction(context) ?: return null
        return synchronized(lock) {
            state.acquire(volumeFraction)
        }
    }

    fun currentVolumeFractionOrNull(): Float? {
        return synchronized(lock) {
            state.currentVolumeFractionOrNull()
        }
    }

    fun release(token: UsbExclusiveBackgroundAudioAnchorVolumeGuardToken?) {
        if (token == null) return
        synchronized(lock) {
            state.release(token)
        }
    }

    private fun readMusicVolumeFraction(context: Context): Float? {
        val audioManager = context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return null
        return runCatching {
            val minVolume = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val range = maxVolume - minVolume
            if (range <= 0) {
                null
            } else {
                ((currentVolume - minVolume).toFloat() / range.toFloat()).coerceIn(0f, 1f)
            }
        }.getOrNull()
    }
}
