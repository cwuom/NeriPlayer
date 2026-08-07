package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.api.bili.biliBvidOrNull
import moe.ouom.neriplayer.core.api.bili.biliCidOrNull
import moe.ouom.neriplayer.core.api.bili.resolveBiliVideoSkipTarget
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.policy.skip.BiliVideoSkipTracker
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipTarget

private const val BILI_VIDEO_SKIP_TARGET_LOAD_MAX_ATTEMPTS = 3
private const val BILI_VIDEO_SKIP_TARGET_LOAD_RETRY_BASE_DELAY_MS = 1_000L

internal fun resolveBiliVideoSkipTargetLoadRetryDelayMs(
    completedAttempts: Int
): Long? {
    if (completedAttempts !in 1 until BILI_VIDEO_SKIP_TARGET_LOAD_MAX_ATTEMPTS) {
        return null
    }
    return BILI_VIDEO_SKIP_TARGET_LOAD_RETRY_BASE_DELAY_MS * completedAttempts
}

internal fun shouldReplaceBiliVideoSkipTrackForCid(
    activeCid: Long?,
    incomingCid: Long?
): Boolean {
    return incomingCid != null && incomingCid != activeCid
}

internal object BiliVideoSkipPlaybackController {
    private const val TAG = "BiliVideoSkip"

    private val lock = Any()
    private var targetLoadJob: Job? = null
    private var activeTrack: ActiveTrack? = null
    private var latestPlaybackRequest: PlaybackRequest? = null
    private val _activeTrackGeneration = MutableStateFlow(0L)

    val activeTrackGeneration: StateFlow<Long> = _activeTrackGeneration.asStateFlow()

    fun onPlaybackRequestStarted(song: SongItem, requestToken: Long) {
        synchronized(lock) {
            latestPlaybackRequest = PlaybackRequest(song = song, requestToken = requestToken)
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
            val incomingCid = song.biliCidOrNull()
            val explicitTarget = song.explicitBiliVideoSkipTargetOrNull()
            val track = if (
                current?.requestToken == requestToken &&
                    current.song.sameIdentityAs(song) &&
                    !shouldReplaceBiliVideoSkipTrackForCid(
                        activeCid = current.song.biliCidOrNull(),
                        incomingCid = incomingCid
                    )
            ) {
                current
            } else {
                clearActiveTrackLocked()
                ActiveTrack(
                    song = song,
                    requestToken = requestToken,
                    target = explicitTarget
                ).also {
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
            val latestRequest = latestPlaybackRequest
            if (
                latestRequest != null &&
                    (latestRequest.requestToken != requestToken ||
                        !latestRequest.song.sameIdentityAs(song))
            ) {
                return
            }
            val current = activeTrack
            val track = if (
                current?.requestToken == requestToken &&
                    current.song.sameIdentityAs(song)
            ) {
                current
            } else if (current != null) {
                return
            } else {
                ActiveTrack(
                    song = song,
                    requestToken = requestToken,
                    target = song.explicitBiliVideoSkipTargetOrNull()
                ).also {
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
        val repository = AppContainer.biliVideoSkipRepository
        val target = track.target
        track.skipTracker.nextSkipPosition(
            intervals = repository.intervalsForPlayback(
                target = target,
                fallbackCid = song.biliCidOrNull(),
                fallbackBvid = song.biliBvidOrNull()
            ),
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
                var target: BiliVideoSkipTarget? = null
                for (attempt in 0 until BILI_VIDEO_SKIP_TARGET_LOAD_MAX_ATTEMPTS) {
                    target = try {
                        resolveBiliVideoSkipTarget(song, AppContainer.biliClient)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        NPLogger.w(
                            TAG,
                            "Bili target loading failed: attempt=${attempt + 1}",
                            error
                        )
                        null
                    }
                    if (target != null) break
                    resolveBiliVideoSkipTargetLoadRetryDelayMs(attempt + 1)?.let { delayMs ->
                        delay(delayMs)
                    }
                }
                if (target == null) {
                    synchronized(lock) {
                        if (targetLoadJob === coroutineContext[Job]) {
                            targetLoadJob = null
                        }
                    }
                    return@launch
                }
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
                    if (targetLoadJob === coroutineContext[Job]) {
                        targetLoadJob = null
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

    private data class PlaybackRequest(
        val song: SongItem,
        val requestToken: Long
    )
}

internal fun SongItem.explicitBiliVideoSkipTargetOrNull(): BiliVideoSkipTarget? {
    val bvid = biliBvidOrNull() ?: return null
    val cid = biliCidOrNull() ?: return null
    return BiliVideoSkipTarget(bvid = bvid, cid = cid).normalizedOrNull()
}
