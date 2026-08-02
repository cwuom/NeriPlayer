package moe.ouom.neriplayer.core.player.policy.failure

import androidx.media3.common.Player

internal enum class PlaybackFailureAdvanceAction {
    NEXT,
    WRAP,
    STOP
}

internal fun resolvePlaybackFailureAdvanceAction(
    currentIndex: Int,
    playlistSize: Int,
    repeatMode: Int
): PlaybackFailureAdvanceAction {
    if (playlistSize <= 0 || currentIndex !in 0 until playlistSize) {
        return PlaybackFailureAdvanceAction.STOP
    }

    val canWrap = repeatMode == Player.REPEAT_MODE_ALL && playlistSize > 1
    return when {
        currentIndex < playlistSize - 1 -> PlaybackFailureAdvanceAction.NEXT
        canWrap -> PlaybackFailureAdvanceAction.WRAP
        else -> PlaybackFailureAdvanceAction.STOP
    }
}
