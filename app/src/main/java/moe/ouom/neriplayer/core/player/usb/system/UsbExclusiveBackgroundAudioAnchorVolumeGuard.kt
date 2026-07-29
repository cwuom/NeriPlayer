package moe.ouom.neriplayer.core.player.usb.system

import android.content.Context
import android.media.AudioManager
import kotlin.math.abs

internal data class UsbExclusiveBackgroundAudioAnchorVolumeGuardToken(
    val generation: Long
)

internal class UsbExclusiveBackgroundAudioAnchorVolumeGuardState {
    private companion object {
        const val VOLUME_EPSILON = 0.0001f
    }

    private data class ActiveSnapshot(
        val token: UsbExclusiveBackgroundAudioAnchorVolumeGuardToken,
        val volumeFraction: Float,
        val routeVolumeFraction: Float? = null,
        val routeVolumeStable: Boolean = false
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

    fun beginRouteObservation(token: UsbExclusiveBackgroundAudioAnchorVolumeGuardToken) {
        val active = activeSnapshot ?: return
        if (active.token != token) return
        activeSnapshot = active.copy(
            routeVolumeFraction = null,
            routeVolumeStable = false
        )
    }

    fun observeRouteVolume(volumeFraction: Float): Float? {
        val active = activeSnapshot ?: return null
        val normalized = volumeFraction.coerceIn(0f, 1f)
        val isStable = active.routeVolumeFraction?.let {
            abs(it - normalized) <= VOLUME_EPSILON
        } == true
        activeSnapshot = active.copy(
            routeVolumeFraction = normalized,
            routeVolumeStable = isStable
        )
        return active.volumeFraction
    }

    fun applyUserVolumeChange(volumeFraction: Float): Float? {
        val active = activeSnapshot ?: return null
        val normalized = volumeFraction.coerceIn(0f, 1f)
        val routeVolume = active.routeVolumeFraction
        val adjustedVolume = if (active.routeVolumeStable && routeVolume != null) {
            (active.volumeFraction + normalized - routeVolume).coerceIn(0f, 1f)
        } else {
            active.volumeFraction
        }
        activeSnapshot = active.copy(
            volumeFraction = adjustedVolume,
            routeVolumeFraction = normalized,
            routeVolumeStable = active.routeVolumeStable
        )
        return adjustedVolume
    }

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

    fun beginRouteObservation(token: UsbExclusiveBackgroundAudioAnchorVolumeGuardToken?) {
        if (token == null) return
        synchronized(lock) {
            state.beginRouteObservation(token)
        }
    }

    fun observeRouteVolume(volumeFraction: Float): Float? {
        return synchronized(lock) {
            state.observeRouteVolume(volumeFraction)
        }
    }

    fun applyUserVolumeChange(volumeFraction: Float): Float? {
        return synchronized(lock) {
            state.applyUserVolumeChange(volumeFraction)
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
