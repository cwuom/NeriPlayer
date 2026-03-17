package moe.ouom.neriplayer.core.player

internal const val PENDING_TRACK_END_DEDUPLICATION_KEY = "__pending_track_end__"

internal fun trackEndDeduplicationKey(
    mediaId: String?,
    fallbackSongKey: String?
): String {
    return mediaId ?: fallbackSongKey ?: PENDING_TRACK_END_DEDUPLICATION_KEY
}

internal fun shouldHandleTrackEnd(
    lastHandledKey: String?,
    currentKey: String
): Boolean {
    return lastHandledKey != currentKey
}
