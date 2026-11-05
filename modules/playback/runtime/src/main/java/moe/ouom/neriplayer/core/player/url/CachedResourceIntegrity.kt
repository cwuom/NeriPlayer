@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.url

import androidx.media3.datasource.cache.CacheSpan

internal data class CachedResourceIntegrity(
    val isComplete: Boolean,
    val requiresRepair: Boolean,
    val coveredLength: Long
)

internal enum class CachePrefetchReadiness {
    COMPLETE,
    READY_FOR_PREFETCH,
    UNAVAILABLE
}

internal fun inspectCachedResourceSpans(
    spans: Collection<CacheSpan>,
    contentLength: Long
): CachedResourceIntegrity {
    if (contentLength <= 0L || spans.isEmpty()) {
        return CachedResourceIntegrity(
            isComplete = false,
            requiresRepair = false,
            coveredLength = 0L
        )
    }

    var coveredUntil = 0L
    var requiresRepair = false
    var hasGap = false
    for (span in spans.sortedBy { it.position }) {
        val file = span.file
        val hasValidFile = span.isCached &&
            span.position >= 0L &&
            span.length > 0L &&
            file?.isFile == true &&
            file.length() == span.length
        if (!hasValidFile) {
            requiresRepair = true
            continue
        }

        val endPosition = span.position + span.length
        if (endPosition < span.position) {
            requiresRepair = true
            continue
        }
        if (
            span.position >= contentLength ||
            endPosition > contentLength
        ) {
            requiresRepair = true
            continue
        }
        if (span.position > coveredUntil) {
            hasGap = true
            continue
        }
        if (span.position < coveredUntil) {
            requiresRepair = true
            continue
        }
        coveredUntil = maxOf(coveredUntil, endPosition)
    }

    return CachedResourceIntegrity(
        isComplete = coveredUntil >= contentLength && !requiresRepair && !hasGap,
        requiresRepair = requiresRepair,
        coveredLength = coveredUntil
    )
}
