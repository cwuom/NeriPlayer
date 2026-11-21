package moe.ouom.neriplayer.core.player.lan

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

internal class LanQueueSelection(
    val songs: List<SongItem>,
    val index: Int,
    val trackKey: String,
    private val fromCurrentQueue: Boolean = false,
) {
    val song: SongItem get() = songs[index]

    fun resolve(current: List<SongItem>): LanQueueSelection? {
        if (!fromCurrentQueue || current === songs) return this
        val currentIndex = when {
            current.getOrNull(index) === song -> index
            else -> current.indexOfFirst { it === song }
        }
        val matched = if (currentIndex >= 0) currentIndex else current.indexOfFirst { LanPlaybackBinding.track(it)?.stableKey == trackKey }
        return if (matched >= 0) LanQueueSelection(current, matched, trackKey) else null
    }
}

internal fun currentLanQueueSelection(
    track: ListenTogetherTrack,
    queue: List<SongItem>,
    currentIndex: Int,
    fallback: () -> SongItem,
): LanQueueSelection {
    val key = track.stableKey
    val index = if (LanPlaybackBinding.track(queue.getOrNull(currentIndex))?.stableKey == key) currentIndex
        else queue.indexOfFirst { LanPlaybackBinding.track(it)?.stableKey == key }
    return if (index >= 0) LanQueueSelection(queue, index, key)
        else LanQueueSelection(listOf(fallback()), 0, key)
}

internal class LanQueueOffers {
    private var pending: LanQueueSelection? = null
    private var publishedMediaId: String? = null

    fun offer(songs: List<SongItem>, index: Int, track: ListenTogetherTrack, fromCurrentQueue: Boolean = false): LanQueueSelection {
        val selected = LanQueueSelection(songs.toList(), index, track.stableKey, fromCurrentQueue)
        pending = selected
        publishedMediaId = null
        return selected
    }

    fun forTrack(track: ListenTogetherTrack): LanQueueSelection? = pending?.takeIf { it.trackKey == track.stableKey }

    fun offerNavigation(songs: List<SongItem>, index: Int, track: ListenTogetherTrack) {
        if (pending == null) offer(songs, index, track, fromCurrentQueue = true)
    }

    fun published(selection: LanQueueSelection, mediaId: String) {
        if (pending === selection) publishedMediaId = mediaId
    }

    fun discard(selection: LanQueueSelection) {
        if (pending === selection) clear()
    }

    fun consume(mediaId: String): LanQueueSelection? =
        pending?.takeIf { publishedMediaId == mediaId }?.also { clear() }

    fun clear() {
        pending = null
        publishedMediaId = null
    }
}
