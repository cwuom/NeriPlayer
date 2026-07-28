package moe.ouom.neriplayer.listentogether.validation

import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.listentogether.mapping.toListenTogetherTrackOrNull

internal fun validateListenTogetherRoomCreation(
    queue: List<SongItem>,
    currentIndex: Int,
    currentSong: SongItem?
): ListenTogetherValidationError? {
    val currentSongValue = currentSong
        ?: return ListenTogetherValidationError(R.string.listen_together_error_current_track_missing)
    val currentTrack = currentSongValue.toListenTogetherTrackOrNull()
        ?: return ListenTogetherValidationError(R.string.listen_together_error_current_track_not_shareable)
    val queueSong = queue.getOrNull(currentIndex)
        ?: return ListenTogetherValidationError(
            R.string.listen_together_error_current_track_unavailable
        )
    val queueTrack = queueSong.toListenTogetherTrackOrNull()
        ?: return ListenTogetherValidationError(
            R.string.listen_together_error_current_track_not_shareable
        )
    return if (queueTrack.stableKey == currentTrack.stableKey) {
        null
    } else {
        ListenTogetherValidationError(R.string.listen_together_error_current_track_unavailable)
    }
}

internal fun requireValidListenTogetherRoomCreation(
    queue: List<SongItem>,
    currentIndex: Int,
    currentSong: SongItem?
) {
    validateListenTogetherRoomCreation(queue, currentIndex, currentSong)
        ?.let { error(it.formatForApp()) }
}
