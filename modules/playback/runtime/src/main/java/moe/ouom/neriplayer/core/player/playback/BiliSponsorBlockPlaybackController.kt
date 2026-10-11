package moe.ouom.neriplayer.core.player.playback

import moe.ouom.neriplayer.data.identity.sameIdentityAs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliSponsorBlockSegment
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliSponsorBlockTarget
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliBvidOrNull
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliCidOrNull
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.resolveBiliSong
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.policy.skip.BiliSponsorBlockSkipTracker
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema

internal object BiliSponsorBlockPlaybackController {
    private const val TAG = "BiliSponsorBlock"

    private val lock = Any()
    private var enabled = false
    private var settingsJob: Job? = null
    private var targetLoadJob: Job? = null
    private var segmentLoadJob: Job? = null
    private var activeTrack: ActiveTrack? = null

    fun onPlaybackRequestStarted(song: SongItem, requestToken: Long) {
        synchronized(lock) {
            val track = activeTrack ?: return
            if (!track.isFor(song, requestToken)) {
                clearActiveTrackLocked()
            }
        }
    }

    fun prepareActiveBiliTrackTarget(
        song: SongItem,
        requestToken: Long,
        scope: CoroutineScope
    ) {
        ensureSettingsObserver(scope)
        val loadAction = synchronized(lock) {
            val current = activeTrack
            val explicitTarget = song.explicitBiliSponsorBlockTargetOrNull()
            val track = if (current != null && current.isFor(song, requestToken)) {
                current
            } else {
                clearActiveTrackLocked()
                ActiveTrack(
                    song = song,
                    requestToken = requestToken,
                    target = explicitTarget
                ).also { activeTrack = it }
            }
            if (explicitTarget != null && track.target != explicitTarget) {
                retargetActiveTrackLocked(track, explicitTarget)
            }
            if (enabled) nextLoadActionLocked(track) else LoadAction.NONE
        }
        runLoadAction(loadAction, scope)
    }

    fun onBiliTrackResolved(
        song: SongItem,
        target: BiliSponsorBlockTarget,
        requestToken: Long,
        scope: CoroutineScope
    ) {
        ensureSettingsObserver(scope)
        val shouldLoadSegments = synchronized(lock) {
            val current = activeTrack
            val track = if (current != null && current.isFor(song, requestToken) && current.target == target) {
                current
            } else {
                clearActiveTrackLocked()
                ActiveTrack(
                    song = song,
                    target = target,
                    requestToken = requestToken
                ).also { activeTrack = it }
            }
            targetLoadJob?.cancel()
            enabled && !track.loaded && !segmentLoadJob.isRunning
        }
        if (shouldLoadSegments) {
            loadSegmentsForActiveTrack(scope)
        }
    }

    fun nextSkipPosition(
        song: SongItem,
        currentPositionMs: Long,
        durationMs: Long
    ): Long? = synchronized(lock) {
        if (!enabled) return@synchronized null
        val track = activeTrack ?: return@synchronized null
        if (!track.song.sameIdentityAs(song)) return@synchronized null
        track.skipTracker.nextSkipPosition(
            segments = track.segments,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs.takeIf { it > 0L } ?: track.target?.durationMs ?: 0L
        )
    }

    private fun ensureSettingsObserver(scope: CoroutineScope) {
        val shouldObserve = synchronized(lock) { !settingsJob.isRunning }
        if (!shouldObserve) return

        val newJob = scope.launch {
            PlayerDependencies.repositories.settingsRepo
                .settingFlow(AutoSettingsSchema.playback.biliSponsorBlockEnabled)
                .collect { settingEnabled ->
                    onSettingChanged(settingEnabled, scope)
                }
        }
        synchronized(lock) {
            if (!settingsJob.isRunning) {
                settingsJob = newJob
            } else {
                newJob.cancel()
            }
        }
    }

    private fun onSettingChanged(settingEnabled: Boolean, scope: CoroutineScope) {
        val loadAction = synchronized(lock) {
            enabled = settingEnabled
            val track = activeTrack
            if (!settingEnabled) {
                targetLoadJob?.cancel()
                segmentLoadJob?.cancel()
                track?.skipTracker?.reset()
                LoadAction.NONE
            } else if (track == null) {
                LoadAction.NONE
            } else {
                nextLoadActionLocked(track)
            }
        }
        runLoadAction(loadAction, scope)
    }

    private fun nextLoadActionLocked(track: ActiveTrack): LoadAction = when {
        track.target == null && !targetLoadJob.isRunning -> LoadAction.TARGET
        !track.loaded && !segmentLoadJob.isRunning -> LoadAction.SEGMENTS
        else -> LoadAction.NONE
    }

    private fun runLoadAction(loadAction: LoadAction, scope: CoroutineScope) {
        when (loadAction) {
            LoadAction.TARGET -> loadTargetForActiveTrack(scope)
            LoadAction.SEGMENTS -> loadSegmentsForActiveTrack(scope)
            LoadAction.NONE -> Unit
        }
    }

    private fun loadTargetForActiveTrack(scope: CoroutineScope) {
        synchronized(lock) {
            val track = activeTrack
            if (
                !enabled ||
                track == null ||
                track.target != null ||
                targetLoadJob.isRunning
            ) {
                return@synchronized
            }
            val song = track.song
            val requestToken = track.requestToken
            targetLoadJob = scope.launch {
                val target = try {
                    resolveBiliSong(song, PlayerDependencies.repositories.biliClient)
                        ?.takeIf { it.cid > 0L }
                        ?.let { resolved ->
                            BiliSponsorBlockTarget(
                                bvid = resolved.videoInfo.bvid,
                                cid = resolved.cid,
                                durationMs = resolved.pageInfo
                                    ?.durationSec
                                    ?.toLong()
                                    ?.times(1_000L)
                                    ?.takeIf { it > 0L }
                                    ?: song.durationMs
                            )
                        }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    NPLogger.w(TAG, "Bili target loading failed", error)
                    null
                }
                if (target == null) return@launch

                val shouldLoadSegments = synchronized(lock) {
                    val current = activeTrack
                    if (
                        !enabled ||
                        current?.requestToken != requestToken ||
                        !current.song.sameIdentityAs(song) ||
                        current.target != null
                    ) {
                        false
                    } else {
                        current.target = target
                        !current.loaded && !segmentLoadJob.isRunning
                    }
                }
                if (shouldLoadSegments) {
                    loadSegmentsForActiveTrack(scope)
                }
            }
        }
    }

    private fun loadSegmentsForActiveTrack(scope: CoroutineScope) {
        synchronized(lock) {
            val track = activeTrack
            val target = track?.target
            if (
                !enabled ||
                track == null ||
                target == null ||
                track.loaded ||
                segmentLoadJob.isRunning
            ) {
                return@synchronized
            }
            val requestToken = track.requestToken
            segmentLoadJob = scope.launch {
                val segments = try {
                    PlayerDependencies.repositories.biliSponsorBlockRepository.loadAutoSkipSegments(target)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    NPLogger.w(TAG, "segment loading failed", error)
                    emptyList()
                }
                synchronized(lock) {
                    val current = activeTrack
                    if (
                        enabled &&
                        current?.requestToken == requestToken &&
                        current.target == target
                    ) {
                        current.segments = segments
                        current.loaded = true
                        NPLogger.d(TAG, "loaded ${segments.size} auto-skip segments")
                    }
                }
            }
        }
    }

    private val Job?.isRunning: Boolean
        get() = this?.isActive == true

    private fun clearActiveTrackLocked() {
        cancelLoadJobsLocked()
        activeTrack = null
    }

    private fun retargetActiveTrackLocked(track: ActiveTrack, target: BiliSponsorBlockTarget) {
        cancelLoadJobsLocked()
        track.target = target
        track.segments = emptyList()
        track.loaded = false
        track.skipTracker.reset()
    }

    private fun cancelLoadJobsLocked() {
        targetLoadJob?.cancel()
        targetLoadJob = null
        segmentLoadJob?.cancel()
        segmentLoadJob = null
    }

    private class ActiveTrack(
        val song: SongItem,
        val requestToken: Long,
        var target: BiliSponsorBlockTarget? = null,
        var segments: List<BiliSponsorBlockSegment> = emptyList(),
        var loaded: Boolean = false,
        val skipTracker: BiliSponsorBlockSkipTracker = BiliSponsorBlockSkipTracker()
    ) {
        fun isFor(incoming: SongItem, incomingRequestToken: Long): Boolean =
            requestToken == incomingRequestToken && song.sameIdentityAs(incoming)
    }

    private enum class LoadAction {
        NONE,
        TARGET,
        SEGMENTS
    }
}

internal fun SongItem.explicitBiliSponsorBlockTargetOrNull(): BiliSponsorBlockTarget? {
    val bvid = biliBvidOrNull() ?: return null
    val cid = biliCidOrNull() ?: return null
    return BiliSponsorBlockTarget(
        bvid = bvid,
        cid = cid,
        durationMs = durationMs.coerceAtLeast(0L)
    )
}
