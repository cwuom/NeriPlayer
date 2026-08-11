package moe.ouom.neriplayer.data.settings

/**
 * keeps the cache setting representation shared by the settings UI and playback startup
 */
object CacheSizePolicy {
    const val UNLIMITED_CACHE_SIZE_BYTES = -1L
    const val MAX_FINITE_CACHE_SIZE_BYTES = 10L * 1024L * 1024L * 1024L
    const val CACHE_SIZE_SLIDER_MAX_FINITE_MB = 10_240f
    const val CACHE_SIZE_SLIDER_UNLIMITED_VALUE = 10_241f
    const val CACHE_SIZE_SLIDER_NO_CACHE_THRESHOLD_MB = 10f

    private const val BYTES_PER_MEGABYTE = 1024L * 1024L

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
        return (normalized.toFloat() / BYTES_PER_MEGABYTE.toFloat())
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
        return (normalized * BYTES_PER_MEGABYTE.toFloat()).toLong()
            .coerceIn(0L, MAX_FINITE_CACHE_SIZE_BYTES)
    }
}
