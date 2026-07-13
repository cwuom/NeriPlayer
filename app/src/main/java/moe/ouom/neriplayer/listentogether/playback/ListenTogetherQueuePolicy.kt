package moe.ouom.neriplayer.listentogether.playback

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack

const val LISTEN_TOGETHER_MAX_SHAREABLE_QUEUE_SIZE = 2_000

internal fun List<ListenTogetherTrack>.boundedAroundStableKey(
    targetStableKey: String?
): List<ListenTogetherTrack> {
    if (size <= LISTEN_TOGETHER_MAX_SHAREABLE_QUEUE_SIZE) return this
    val targetIndex = targetStableKey
        ?.let { stableKey -> indexOfFirst { it.stableKey == stableKey } }
        ?.takeIf { it >= 0 }
        ?: 0
    val maxSize = LISTEN_TOGETHER_MAX_SHAREABLE_QUEUE_SIZE
    val halfWindow = maxSize / 2
    val start = (targetIndex - halfWindow).coerceIn(0, size - maxSize)
    return subList(start, start + maxSize)
}
