package moe.ouom.neriplayer.core.player.runtime.prefetch

import moe.ouom.neriplayer.data.model.playback.SongUrlResult

fun resolveGenericUrlPrefetchTtlMs(
    currentTrackDurationMs: Long,
    defaultTtlMs: Long = GENERIC_URL_PREFETCH_TTL_MS,
    maxTtlMs: Long = GENERIC_URL_PREFETCH_MAX_TTL_MS
): Long {
    val durationBasedTtl = currentTrackDurationMs
        .takeIf { it > 0L }
        ?.plus(30_000L)
    return (durationBasedTtl ?: defaultTtlMs).coerceIn(1L, maxTtlMs)
}

const val GENERIC_MEDIA_PREFETCH_BYTES = 1_536L * 1024L
private const val GENERIC_MEDIA_PREFETCH_MIN_BYTES = 256L * 1024L

fun resolveGenericMediaPrefetchBytes(expectedContentLength: Long?): Long {
    return expectedContentLength
        ?.takeIf { it > 0L }
        ?.coerceAtMost(GENERIC_MEDIA_PREFETCH_BYTES)
        ?.coerceAtLeast(GENERIC_MEDIA_PREFETCH_MIN_BYTES)
        ?: GENERIC_MEDIA_PREFETCH_BYTES
}

fun resolveGenericMediaPrefetchCacheKey(
    genericCacheKey: String,
    result: SongUrlResult.Success
): String {
    return result.cacheKeyOverride
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: genericCacheKey
}
