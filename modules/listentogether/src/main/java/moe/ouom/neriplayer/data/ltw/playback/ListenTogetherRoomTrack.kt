package moe.ouom.neriplayer.data.ltw.playback

import moe.ouom.neriplayer.data.ltw.mapping.trustedListenTogetherStreamUrls
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

fun ListenTogetherRoomState.currentStableKey(): String? = currentTrack()?.stableKey

fun ListenTogetherRoomState.currentTrack(): ListenTogetherTrack? {
    return if (queue.isNotEmpty()) {
        queue[currentIndex.coerceIn(0, queue.lastIndex)]
    } else {
        track
    }
}

fun ListenTogetherRoomState.authoritativeStreamUrlForCurrentTrack(): String? =
    authoritativeStreamUrlsForCurrentTrack().firstOrNull()

fun ListenTogetherRoomState.authoritativeStreamUrlsForCurrentTrack(): List<String> {
    val targetTrack = currentTrack() ?: return emptyList()
    return sequenceOf(targetTrack, track)
        .filterNotNull()
        .filter { candidate -> candidate.stableKey == targetTrack.stableKey }
        .flatMap { candidate ->
            trustedListenTogetherStreamUrls(
                channelId = candidate.channelId,
                streamUrls = candidate.streamUrls,
                legacyStreamUrl = candidate.streamUrl
            ).asSequence()
        }
        .distinct()
        .toList()
}

fun ListenTogetherEvent.requestedStableKey(): String? {
    return requestTrackStableKey
        ?: queue?.getOrNull(currentIndex ?: -1)?.stableKey
        ?: track?.stableKey
}

fun resolveListenTogetherQueueIndex(
    queue: List<ListenTogetherTrack>,
    requestedIndex: Int,
    preferredStableKey: String?
): Int {
    if (queue.isEmpty()) return -1
    val indexedPosition = if (requestedIndex in queue.indices) requestedIndex else 0
    val preferredKey = nonBlankQueueKey(preferredStableKey) ?: return indexedPosition
    if (queue[indexedPosition].stableKey == preferredKey) {
        return indexedPosition
    }
    return preferredQueueIndex(queue, preferredKey) ?: indexedPosition
}

private fun nonBlankQueueKey(stableKey: String?): String? = if (stableKey.isNullOrBlank()) null else stableKey

private fun preferredQueueIndex(queue: List<ListenTogetherTrack>, stableKey: String): Int? =
    queue.indexOfFirst { it.stableKey == stableKey }.takeIf { it >= 0 }

fun List<ListenTogetherTrack>.mergeCurrentTrack(
    currentIndex: Int,
    currentTrack: ListenTogetherTrack?
): List<ListenTogetherTrack> {
    val replacement = currentTrack ?: return this
    if (currentIndex !in indices) return this
    if (this[currentIndex].stableKey != replacement.stableKey) return this
    if (this[currentIndex] == replacement) return this
    return toMutableList().also { it[currentIndex] = replacement }
}

fun isListenTogetherQueueUpdateCause(causeType: String?): Boolean =
    causeType in LISTEN_TOGETHER_QUEUE_UPDATE_CAUSES || isListenTogetherPlaybackModeQueueUpdate(causeType)

fun isListenTogetherPlaybackModeQueueUpdate(causeType: String?): Boolean =
    causeType in LISTEN_TOGETHER_PLAYBACK_MODE_QUEUE_UPDATE_CAUSES

private val LISTEN_TOGETHER_QUEUE_UPDATE_CAUSES = setOf("SET_QUEUE", "REQUEST_SET_QUEUE")
private val LISTEN_TOGETHER_PLAYBACK_MODE_QUEUE_UPDATE_CAUSES = setOf("PLAYBACK_MODE", "REQUEST_PLAYBACK_MODE")
