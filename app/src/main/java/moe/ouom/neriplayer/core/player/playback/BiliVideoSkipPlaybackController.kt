package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.api.bili.resolveBiliVideoSkipTarget
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.policy.skip.BiliVideoSkipTracker
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipTarget

internal object BiliVideoSkipPlaybackController {
    private const val TAG = "BiliVideoSkip"

    private val lock = Any()
    private var targetLoadJob: Job? = null
    private var activeTrack: ActiveTrack? = null
    private val _activeTrackGeneration = MutableStateFlow(0L)

    val activeTrackGeneration: StateFlow<Long> = _activeTrackGeneration.asStateFlow()

    fun onPlaybackRequestStarted(song: SongItem, requestToken: Long) {
        synchronized(lock) {
            val track = activeTrack ?: return
            if (track.requestToken != requestToken || !track.song.sameIdentityAs(song)) {
                clearActiveTrackLocked()
            }
        }
    }

    fun prepareActiveBiliTrackTarget(
        song: SongItem,
        requestToken: Long,
        scope: CoroutineScope
    ) {
        val shouldLoadTarget = synchronized(lock) {
            val current = activeTrack
            val track = if (
                current?.requestToken == requestToken && current.song.sameIdentityAs(song)
            ) {
                current
            } else {
                clearActiveTrackLocked()
                ActiveTrack(song = song, requestToken = requestToken).also {
                    activeTrack = it
                    notifyActiveTrackChangedLocked()
                }
            }
            track.target == null && targetLoadJob?.isActive != true
        }
        if (shouldLoadTarget) loadTargetForActiveTrack(scope)
    }

    fun onBiliTrackResolved(
        song: SongItem,
        target: BiliVideoSkipTarget,
        requestToken: Long
    ) {
        val normalizedTarget = target.normalizedOrNull() ?: return
        synchronized(lock) {
            val current = activeTrack
            val track = if (
                current?.requestToken == requestToken &&
                    current.song.sameIdentityAs(song)
            ) {
                current
            } else {
                clearActiveTrackLocked()
                ActiveTrack(song = song, requestToken = requestToken).also {
                    activeTrack = it
                    notifyActiveTrackChangedLocked()
                }
            }
            if (track.target != normalizedTarget) {
                track.target = normalizedTarget
                track.skipTracker.reset()
                notifyActiveTrackChangedLocked()
            }
            targetLoadJob?.cancel()
            targetLoadJob = null
        }
    }

    fun activeTargetFor(song: SongItem): BiliVideoSkipTarget? = synchronized(lock) {
        activeTrack?.takeIf { it.song.sameIdentityAs(song) }?.target
    }

    fun nextSkipPosition(
        song: SongItem,
        currentPositionMs: Long,
        durationMs: Long
    ): Long? = synchronized(lock) {
        val track = activeTrack ?: return@synchronized null
        if (!track.song.sameIdentityAs(song)) return@synchronized null
        val target = track.target ?: return@synchronized null
        track.skipTracker.nextSkipPosition(
            intervals = AppContainer.biliVideoSkipRepository.intervalsFor(target),
            currentPositionMs = currentPositionMs,
            durationMs = durationMs
        )
    }

    private fun loadTargetForActiveTrack(scope: CoroutineScope) {
        synchronized(lock) {
            val track = activeTrack
            if (track == null || track.target != null || targetLoadJob?.isActive == true) {
                return@synchronized
            }
            val song = track.song
            val requestToken = track.requestToken
            targetLoadJob = scope.launch {
                val target = try {
                    resolveBiliVideoSkipTarget(song, AppContainer.biliClient)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    NPLogger.w(TAG, "Bili target loading failed", error)
                    null
                }
                if (target == null) return@launch
                synchronized(lock) {
                    val current = activeTrack
                    if (
                        current?.requestToken == requestToken &&
                            current.song.sameIdentityAs(song) &&
                            current.target == null
                    ) {
                        current.target = target
                        current.skipTracker.reset()
                        notifyActiveTrackChangedLocked()
                    }
                }
            }
        }
    }

    private fun clearActiveTrackLocked() {
        val hadActiveTrack = activeTrack != null
        targetLoadJob?.cancel()
        targetLoadJob = null
        activeTrack = null
        if (hadActiveTrack) notifyActiveTrackChangedLocked()
    }

    private fun notifyActiveTrackChangedLocked() {
        _activeTrackGeneration.value = if (_activeTrackGeneration.value == Long.MAX_VALUE) {
            0L
        } else {
            _activeTrackGeneration.value + 1L
        }
    }

    private class ActiveTrack(
        val song: SongItem,
        val requestToken: Long,
        var target: BiliVideoSkipTarget? = null,
        val skipTracker: BiliVideoSkipTracker = BiliVideoSkipTracker()
    )
}
