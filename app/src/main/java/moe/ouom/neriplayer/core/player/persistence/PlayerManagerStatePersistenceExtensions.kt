@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.persistence

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.PersistedPlaybackState
import moe.ouom.neriplayer.core.player.url.isCurrentListenTogetherFallbackMediaUrl
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.util.coroutines.runCatchingNonCancellation

internal typealias PlaybackStatePersistRequest =
    PlaybackStatePersistenceCoordinator.Request<PlaybackStatePersistenceSnapshot>

internal fun PlayerManager.prepareStatePersist(
    positionMs: Long = _playbackPositionMs.value.coerceAtLeast(0L),
    shouldResumePlayback: Boolean = currentPlaylist.isNotEmpty() && shouldResumePlaybackSnapshot()
): PlaybackStatePersistRequest? = statePersistenceCoordinator.prepare {
    val session = queueStore.sessionSnapshot()
    PlaybackStatePersistenceSnapshot(
        session = session,
        playback = PersistedPlaybackState(
            index = session.queue.currentIndex,
            mediaUrl = _currentMediaUrl.value.takeUnless { isCurrentListenTogetherFallbackMediaUrl() },
            positionMs = if (keepLastPlaybackProgressEnabled) positionMs.coerceAtLeast(0L) else 0L,
            shouldResumePlayback = shouldResumePlayback && !suppressAutoResumeForCurrentSession,
            repeatMode = if (keepPlaybackModeStateEnabled) repeatModeSetting else Player.REPEAT_MODE_OFF
        ),
        keepShuffleMode = keepPlaybackModeStateEnabled
    )
}

internal fun PlayerManager.scheduleStatePersist(
    positionMs: Long = _playbackPositionMs.value.coerceAtLeast(0L),
    shouldResumePlayback: Boolean = currentPlaylist.isNotEmpty() && shouldResumePlaybackSnapshot(),
    debounceMs: Long = STATE_PERSIST_DEBOUNCE_MS
) {
    val request = prepareStatePersist(positionMs, shouldResumePlayback) ?: return
    statePersistenceCoordinator.schedule(ioScope, request, debounceMs) { snapshot ->
        runCatchingNonCancellation { writeStateSnapshot(snapshot) }
            .onFailure { error -> NPLogger.e("PlayerManager", "Failed to persist state", error) }
    }
}

internal suspend fun PlayerManager.persistStateNow(
    positionMs: Long = _playbackPositionMs.value.coerceAtLeast(0L),
    shouldResumePlayback: Boolean = currentPlaylist.isNotEmpty() && shouldResumePlaybackSnapshot(),
    reason: String
): Boolean {
    if (!initialized) return false
    val request = prepareStatePersist(positionMs, shouldResumePlayback) ?: return false
    return persistStateNow(request, reason)
}

internal suspend fun PlayerManager.persistStateNow(
    request: PlaybackStatePersistRequest,
    reason: String
): Boolean {
    if (!initialized) return false
    NPLogger.d("NERI-PlayerManager", "persistStateNow: reason=$reason")
    return persistPreparedState(request)
}

internal suspend fun PlayerManager.persistStateImpl(
    positionMs: Long = _playbackPositionMs.value.coerceAtLeast(0L),
    shouldResumePlayback: Boolean = currentPlaylist.isNotEmpty() && shouldResumePlaybackSnapshot()
) {
    val request = prepareStatePersist(positionMs, shouldResumePlayback) ?: return
    persistPreparedState(request)
}

private suspend fun PlayerManager.persistPreparedState(request: PlaybackStatePersistRequest): Boolean =
    try {
        statePersistenceCoordinator.persist(request) { writeStateSnapshot(it) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NPLogger.e("PlayerManager", "Failed to persist state", error)
        false
    }

private suspend fun PlayerManager.writeStateSnapshot(snapshot: PlaybackStatePersistenceSnapshot) {
    withContext(Dispatchers.IO) {
        val target = statePersistenceWriter.write(
            snapshot = snapshot,
            roomStore = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(application.applicationContext)),
            legacyStore = PlaybackQueueLegacyStore(stateFile, playbackStateFile, gson),
            onRoomFailure = { error ->
                NPLogger.e("NERI-PlayerManager", "Room persist or legacy cleanup failed; falling back to JSON", error)
            }
        )
        if (target != PlaybackQueuePersistTarget.NONE) {
            lastStatePersistAtMs = SystemClock.elapsedRealtime()
        }
        NPLogger.d(
            "NERI-PlayerManager",
            "persistState: target=$target queueSize=${snapshot.queue.playlist.size}, " +
                "index=${snapshot.playback.index}, positionMs=${snapshot.playback.positionMs}, " +
                "shouldResume=${snapshot.playback.shouldResumePlayback}"
        )
    }
}
