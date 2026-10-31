package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.policy.command.ManualResumePlaybackDecision
import moe.ouom.neriplayer.core.player.policy.command.resolveManualResumePlaybackDecision
import moe.ouom.neriplayer.core.player.policy.pending.shouldApplyResolvedMedia
import moe.ouom.neriplayer.core.player.session.AppQueueSongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.server.isServerSong

/** Preserve the failed server song's retry position without changing ordinary resume settings. */
internal fun PlayerManager.resolveManualResumeWithServerRecovery(
    song: SongItem?, isPlayerPrepared: Boolean
): ManualResumePlaybackDecision {
    val serverRetryPosition = song?.let { current ->
        serverRecoveryPosition?.takeIf { it.first == AppQueueSongIdentity.stableKey(current) }?.second
    }
    return resolveManualResumePlaybackDecision(
        keepLastPlaybackProgressEnabled = keepLastPlaybackProgressEnabled || serverRetryPosition != null,
        restoredResumePositionMs = serverRetryPosition ?: restoredResumePositionMs,
        persistedPlaybackPositionMs = serverRetryPosition ?: _playbackPositionMs.value,
        isPlayerPrepared = isPlayerPrepared,
        currentMediaUrlResolvedAtMs = currentMediaUrlResolvedAtMs
    )
}

/** Preserve the latest seek, including zero, when a server URL request fails in this session. */
internal suspend fun PlayerManager.preserveServerPlaybackForRetry(
    song: SongItem, positionMs: Long, requestToken: Long
): Boolean {
    if (!song.isServerSong()) return false
    withContext(Dispatchers.Main) {
        if (!shouldApplyResolvedMedia(requestToken, playbackRequestToken)) return@withContext
        val retryPositionMs = pendingSeekPositionOrNull() ?: positionMs
        stopPlaybackPreservingQueue(clearMediaUrl = true)
        _playbackPositionMs.value = retryPositionMs
        setRestoredPlayback(retryPositionMs, shouldResume = false)
        serverRecoveryPosition = AppQueueSongIdentity.stableKey(song) to retryPositionMs
        scheduleStatePersist(positionMs = retryPositionMs, shouldResumePlayback = false)
    }
    return true
}
