package moe.ouom.neriplayer.core.player.policy

import androidx.media3.common.Player

internal enum class PlaybackFailureAdvanceAction {
    NEXT,
    WRAP,
    STOP
}

internal fun resolvePlaybackFailureAdvanceAction(
    currentIndex: Int,
    playlistSize: Int,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    shuffleFutureSize: Int,
    shuffleBagSize: Int
): PlaybackFailureAdvanceAction {
    if (playlistSize <= 0 || currentIndex !in 0 until playlistSize) {
        return PlaybackFailureAdvanceAction.STOP
    }

    val canWrap = repeatMode == Player.REPEAT_MODE_ALL && playlistSize > 1
    return if (shuffleEnabled) {
        when {
            shuffleFutureSize > 0 || shuffleBagSize > 0 -> PlaybackFailureAdvanceAction.NEXT
            canWrap -> PlaybackFailureAdvanceAction.WRAP
            else -> PlaybackFailureAdvanceAction.STOP
        }
    } else {
        when {
            currentIndex < playlistSize - 1 -> PlaybackFailureAdvanceAction.NEXT
            canWrap -> PlaybackFailureAdvanceAction.WRAP
            else -> PlaybackFailureAdvanceAction.STOP
        }
    }
}
