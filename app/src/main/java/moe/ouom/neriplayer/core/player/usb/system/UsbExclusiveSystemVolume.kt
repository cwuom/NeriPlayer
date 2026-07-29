package moe.ouom.neriplayer.core.player.usb.system

import kotlin.math.pow
import moe.ouom.neriplayer.data.settings.DEFAULT_USB_EXCLUSIVE_BIT_PERFECT

internal const val USB_EXCLUSIVE_SYSTEM_VOLUME_EXPONENT = 2.0

internal data class UsbExclusiveSystemVolumeBridgeSubscription(
    val generation: Long
)

internal object UsbExclusiveSystemVolumeBridge {
    private data class ActiveSubscription(
        val token: UsbExclusiveSystemVolumeBridgeSubscription,
        val listener: (Float?) -> Unit
    )

    private val lock = Any()
    private var nextGeneration = 0L
    private var activeSubscription: ActiveSubscription? = null
    private var sessionVolumeFraction: Float? = null

    fun subscribe(listener: (Float?) -> Unit): UsbExclusiveSystemVolumeBridgeSubscription {
        val token: UsbExclusiveSystemVolumeBridgeSubscription
        val currentVolume: Float?
        synchronized(lock) {
            token = UsbExclusiveSystemVolumeBridgeSubscription(++nextGeneration)
            activeSubscription = ActiveSubscription(token, listener)
            currentVolume = sessionVolumeFraction
        }
        listener(currentVolume)
        return token
    }

    fun unsubscribe(token: UsbExclusiveSystemVolumeBridgeSubscription?) {
        if (token == null) return
        synchronized(lock) {
            if (activeSubscription?.token == token) {
                activeSubscription = null
            }
        }
    }

    fun updateSessionVolumeFraction(volumeFraction: Float) {
        val normalized = volumeFraction.coerceIn(0f, 1f)
        val listener = synchronized(lock) {
            sessionVolumeFraction = normalized
            activeSubscription?.listener
        }
        listener?.invoke(normalized)
    }

    fun currentSessionVolumeFractionOrNull(): Float? {
        return synchronized(lock) { sessionVolumeFraction }
    }

    fun clearSessionVolumeFraction() {
        val listener = synchronized(lock) {
            if (sessionVolumeFraction == null) return
            sessionVolumeFraction = null
            activeSubscription?.listener
        }
        listener?.invoke(null)
    }
}

internal fun usbExclusiveEffectiveNativeVolume(
    playerVolume: Float,
    systemVolumeFraction: Float,
    bitPerfect: Boolean = DEFAULT_USB_EXCLUSIVE_BIT_PERFECT
): Float {
    if (bitPerfect) return 1f
    val playerGain = playerVolume.coerceIn(0f, 1f)
    return playerGain * usbExclusiveSystemVolumeGain(systemVolumeFraction)
}

internal fun usbExclusiveSystemVolumeGain(volumeFraction: Float): Float {
    val normalized = volumeFraction.coerceIn(0f, 1f)
    return normalized.toDouble()
        .pow(USB_EXCLUSIVE_SYSTEM_VOLUME_EXPONENT)
        .toFloat()
        .coerceIn(0f, 1f)
}

internal fun usbExclusiveFloatSampleForNativePipeline(sample: Float): Float {
    return if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
}
