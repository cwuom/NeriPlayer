package moe.ouom.neriplayer.core.player.queue.policy.failure

import moe.ouom.neriplayer.data.model.playback.queue.PlaybackFailureAdvanceAction
import moe.ouom.neriplayer.core.player.queue.policy.QueueRepeatMode

fun resolvePlaybackFailureAdvanceAction(
    currentIndex: Int,
    playlistSize: Int,
    repeatMode: Int
): PlaybackFailureAdvanceAction {
    if (playlistSize <= 0 || currentIndex !in 0 until playlistSize) {
        return PlaybackFailureAdvanceAction.STOP
    }

    val canWrap = repeatMode == QueueRepeatMode.ALL && playlistSize > 1
    return when {
        currentIndex < playlistSize - 1 -> PlaybackFailureAdvanceAction.NEXT
        canWrap -> PlaybackFailureAdvanceAction.WRAP
        else -> PlaybackFailureAdvanceAction.STOP
    }
}
