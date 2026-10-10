package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.policy.pending.shouldApplyResolvedMedia
import moe.ouom.neriplayer.core.player.session.AppQueueSongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.server.isServerSong

/** Handle server URL failures without changing the failure path for other sources. */
internal suspend fun PlayerManager.preserveServerPlaybackForRetry(
    song: SongItem, positionMs: Long, requestToken: Long
): Boolean {
    if (!song.isServerSong()) return false
    withContext(Dispatchers.Main) {
        if (!shouldApplyResolvedMedia(requestToken, playbackRequestToken)) return@withContext
        stopPlaybackPreservingQueue(clearMediaUrl = true)
        _playbackPositionMs.value = positionMs
        setRestoredPlayback(positionMs, shouldResume = false)
        serverRecoveryPosition = AppQueueSongIdentity.stableKey(song) to positionMs
        scheduleStatePersist(positionMs = positionMs, shouldResumePlayback = false)
    }
    return true
}
