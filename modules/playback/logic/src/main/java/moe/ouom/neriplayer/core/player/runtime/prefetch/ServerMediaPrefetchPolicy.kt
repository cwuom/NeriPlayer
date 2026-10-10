package moe.ouom.neriplayer.core.player.runtime.prefetch

/** Only the server adapter uses this policy; existing platform prefetch behavior is unchanged. */
fun canPrefetchServerMedia(cacheEnabled: Boolean, unmetered: Boolean, playing: Boolean,
                          ready: Boolean, bufferedMs: Long, remainingMs: Long): Boolean =
    cacheEnabled && unmetered && playing && ready &&
        hasServerPrefetchBuffer(bufferedMs, remainingMs)

private fun hasServerPrefetchBuffer(bufferedMs: Long, remainingMs: Long): Boolean =
    bufferedMs >= 5_000L || (remainingMs in 1..5_000L && bufferedMs >= remainingMs)

fun serverMediaPrefetchBytes(expectedLength: Long?, cacheBudget: Long): Long =
    minOf(GENERIC_MEDIA_PREFETCH_BYTES,
        expectedLength?.takeIf { it > 0L } ?: GENERIC_MEDIA_PREFETCH_BYTES,
        if (cacheBudget > 0L) cacheBudget / 8L else GENERIC_MEDIA_PREFETCH_BYTES)
