package moe.ouom.neriplayer.core.player

import moe.ouom.neriplayer.core.player.state.blockingIo
import moe.ouom.neriplayer.util.NPLogger

internal fun PlayerManager.suspendPlaybackForServiceRestart(reason: String) {
    if (!isPlayerInitialized() || currentSongFlow.value == null) return
    val shouldResume = isTransportActiveWithoutInitialization()
    val positionMs = runCatching { player.currentPosition.coerceAtLeast(0L) }
        .getOrDefault(playbackPositionFlow.value.coerceAtLeast(0L))

    playbackRequestToken += 1L
    playJob?.cancel()
    playJob = null
    cancelPendingPauseRequest(resetVolumeToFull = true)
    cancelVolumeFade(resetToFull = true)
    runCatching {
        player.playWhenReady = false
        player.stop()
    }.onFailure { error ->
        NPLogger.w(
            "NERI-PlayerManager",
            "failed to suspend playback for service restart reason=$reason",
            error
        )
    }

    _isPlayingFlow.value = false
    _playWhenReadyFlow.value = false
    _playbackPositionMs.value = positionMs
    stopProgressUpdates()
    restoredShouldResumePlayback = shouldResume
    restoredResumePositionMs = positionMs
    updateResumePlaybackRequested(shouldResume)
    blockingIo {
        persistState(positionMs = positionMs, shouldResumePlayback = shouldResume)
    }
    NPLogger.i(
        "NERI-PlayerManager",
        "suspended playback for service restart reason=$reason positionMs=$positionMs resume=$shouldResume"
    )
}
