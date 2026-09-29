package moe.ouom.neriplayer.data.settings.storage

import moe.ouom.neriplayer.util.units.GIBIBYTE_BYTES
import moe.ouom.neriplayer.util.units.MEBIBYTE_BYTES

/**
 * keeps the cache setting representation shared by the settings UI and playback startup
 */
object CacheSizePolicy {
    const val UNLIMITED_CACHE_SIZE_BYTES = -1L
    const val DEFAULT_CACHE_SIZE_BYTES = moe.ouom.neriplayer.data.model.settings.playback.DEFAULT_CACHE_SIZE_BYTES
    const val MAX_FINITE_CACHE_SIZE_BYTES = 10 * GIBIBYTE_BYTES
    const val CACHE_SIZE_SLIDER_MAX_FINITE_MB = 10_240f
    const val CACHE_SIZE_SLIDER_UNLIMITED_VALUE = 10_241f
    const val CACHE_SIZE_SLIDER_NO_CACHE_THRESHOLD_MB = 10f

    fun normalizeCacheSizeBytes(bytes: Long): Long {
        return if (bytes == UNLIMITED_CACHE_SIZE_BYTES) {
            bytes
        } else {
            bytes.coerceIn(0L, MAX_FINITE_CACHE_SIZE_BYTES)
        }
    }

    fun toSliderValue(bytes: Long): Float {
        val normalized = normalizeCacheSizeBytes(bytes)
        if (normalized == UNLIMITED_CACHE_SIZE_BYTES) {
            return CACHE_SIZE_SLIDER_UNLIMITED_VALUE
        }
        return (normalized.toFloat() / MEBIBYTE_BYTES.toFloat())
            .coerceIn(0f, CACHE_SIZE_SLIDER_MAX_FINITE_MB)
    }

    fun fromSliderValue(value: Float): Long {
        if (value.isNaN()) return 0L
        val normalized = value.coerceIn(0f, CACHE_SIZE_SLIDER_UNLIMITED_VALUE)
        if (normalized >= CACHE_SIZE_SLIDER_UNLIMITED_VALUE) {
            return UNLIMITED_CACHE_SIZE_BYTES
        }
        if (normalized < CACHE_SIZE_SLIDER_NO_CACHE_THRESHOLD_MB) {
            return 0L
        }
        return (normalized * MEBIBYTE_BYTES.toFloat()).toLong()
            .coerceIn(0L, MAX_FINITE_CACHE_SIZE_BYTES)
    }
}
