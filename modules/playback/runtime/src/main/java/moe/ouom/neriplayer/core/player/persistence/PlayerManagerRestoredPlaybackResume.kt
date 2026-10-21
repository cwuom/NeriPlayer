package moe.ouom.neriplayer.core.player.persistence

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.playback.playAtIndex
import moe.ouom.neriplayer.data.ltw.playback.ListenTogetherRestoredPlaybackAction
import moe.ouom.neriplayer.data.ltw.playback.resolveListenTogetherRestoredPlaybackAction
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.RestoredPlaybackState
import kotlin.coroutines.coroutineContext

private data class RestoredPlaybackResumeCandidate(
    val restored: RestoredPlaybackState.ResumePending,
    val song: SongItem,
    val index: Int,
    val requestToken: Long
)

internal fun PlayerManager.scheduleRestoredPlaybackResume(
    scope: CoroutineScope,
    onSourceUnavailable: () -> Unit = {},
    onResumed: () -> Unit
): Job =
    scope.launch {
        if (resumeRestoredPlaybackIfNeededImpl(onSourceUnavailable) != null) onResumed()
    }

internal suspend fun PlayerManager.resumeRestoredPlaybackIfNeededImpl(
    onSourceUnavailable: () -> Unit = {}
): Long? = withContext(Dispatchers.Main.immediate) {
    ensureInitialized()
    val candidate = pendingRestoredPlaybackResume() ?: return@withContext null
    if (!canResumeInCurrentListenTogetherRoom(candidate.restored)) return@withContext null
    val sourceAvailable = restoredSourceAvailable(candidate.song)
    coroutineContext.ensureActive()
    // 探测期间用户可能换歌或暂停，旧结果不能消费新的恢复请求
    if (!isCurrentRestoredPlaybackResume(candidate)) return@withContext null
    if (!canResumeInCurrentListenTogetherRoom(candidate.restored)) return@withContext null
    if (!sourceAvailable) {
        onSourceUnavailable()
        return@withContext null
    }
    val resumePositionMs = candidate.restored.positionMs
    NPLogger.d(
        "NERI-PlayerManager",
        "resumeRestoredPlaybackIfNeeded(): resumeIndex=${candidate.index}, positionMs=$resumePositionMs, " +
            "song=${candidate.song.name}, stack=[${debugStackHint()}]"
    )
    if (!consumeRestoredPlayback(candidate.restored)) return@withContext null
    lastStatePersistAtMs = SystemClock.elapsedRealtime()
    playAtIndex(candidate.index, resumePositionMs = resumePositionMs, forceStartupProtectionFade = true)
    resumePositionMs
}

private fun PlayerManager.pendingRestoredPlaybackResume(): RestoredPlaybackResumeCandidate? {
    if (!initialized) return null
    val restored = restoredPlaybackSnapshot() as? RestoredPlaybackState.ResumePending ?: return null
    val index = currentIndex
    val song = currentPlaylist.getOrNull(index) ?: return null
    return RestoredPlaybackResumeCandidate(restored, song, index, playbackRequestToken)
}

private suspend fun PlayerManager.restoredSourceAvailable(song: SongItem): Boolean {
    if (!isLocalSong(song)) return true
    return try {
        withContext(Dispatchers.IO) { isRestorableLocalSong(song) }.also { available ->
            if (!available) NPLogger.w(
                "NERI-PlayerManager",
                "resumeRestoredPlaybackIfNeeded(): keep restored local progress because media is not readable yet, song=${song.name}"
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        NPLogger.w("NERI-PlayerManager", "Restored local source probe failed: ${error.javaClass.name}")
        false
    }
}

private fun PlayerManager.isCurrentRestoredPlaybackResume(candidate: RestoredPlaybackResumeCandidate): Boolean =
    initialized && restoredPlaybackSnapshot() === candidate.restored &&
        playbackRequestToken == candidate.requestToken && currentIndex == candidate.index &&
        currentPlaylist.getOrNull(candidate.index) == candidate.song && _currentSongFlow.value == candidate.song

private fun PlayerManager.canResumeInCurrentListenTogetherRoom(restored: RestoredPlaybackState.ResumePending): Boolean {
    return when (resolveListenTogetherRestoredPlaybackAction(
        restoredPlaybackRequested = true,
        listenTogetherSessionActive = isListenTogetherActive(),
        currentUserIsController = isCurrentUserControllerInListenTogether()
    )) {
        ListenTogetherRestoredPlaybackAction.SKIP -> false
        ListenTogetherRestoredPlaybackAction.RESUME_LOCAL_PLAYBACK -> true
        ListenTogetherRestoredPlaybackAction.WAIT_FOR_AUTHORITATIVE_ROOM_STATE -> {
            if (consumeRestoredPlayback(restored)) {
                scheduleStatePersist(
                    positionMs = _playbackPositionMs.value.coerceAtLeast(0L),
                    shouldResumePlayback = false,
                    debounceMs = 0L
                )
            }
            false
        }
    }
}
