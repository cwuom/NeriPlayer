@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lifecycle

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.session.AppQueueSongIdentity
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import moe.ouom.neriplayer.platform.subsonic.api.subsonicErrorMessageRes

/** Preserve the current server track after transport retries so manual retry can resume it. */
internal fun PlayerManager.handleServerPlaybackError(error: Throwable): Boolean {
    val serverError = SubsonicException.find(error) ?: return false
    val failedSong = _currentSongFlow.value
    val failedPosition = player.currentPosition.coerceAtLeast(0L)
    pause()
    if (failedSong != null) {
        serverRecoveryPosition = AppQueueSongIdentity.stableKey(failedSong) to failedPosition
    }
    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(subsonicErrorMessageRes(serverError))))
    return true
}
