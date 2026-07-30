package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.api.bili.BiliSponsorBlockSegment
import moe.ouom.neriplayer.core.api.bili.BiliSponsorBlockTarget
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.policy.skip.BiliSponsorBlockSkipTracker
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sameIdentityAs
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
        ensureSettingsObserver(scope)
        val shouldLoadTarget = synchronized(lock) {
            val current = activeTrack
            val track = if (
                current?.requestToken == requestToken &&
                    current.song.sameIdentityAs(song)
            ) {
                current
            } else {
                clearActiveTrackLocked()
                ActiveTrack(song = song, requestToken = requestToken).also { activeTrack = it }
            }
            enabled && track.target == null && targetLoadJob?.isActive != true
        }
        if (shouldLoadTarget) {
            loadTargetForActiveTrack(scope)
        }
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
            val track = if (
                current?.requestToken == requestToken &&
                current.target == target &&
                current.song.sameIdentityAs(song)
            ) {
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
            enabled && !track.loaded && segmentLoadJob?.isActive != true
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
        val shouldObserve = synchronized(lock) { settingsJob?.isActive != true }
        if (!shouldObserve) return

        val newJob = scope.launch {
            AppContainer.settingsRepo
                .settingFlow(AutoSettingsSchema.playback.biliSponsorBlockEnabled)
                .collect { settingEnabled ->
                    onSettingChanged(settingEnabled, scope)
                }
        }
        synchronized(lock) {
            if (settingsJob?.isActive != true) {
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
            } else when {
                track == null -> LoadAction.NONE
                track.target == null && targetLoadJob?.isActive != true -> LoadAction.TARGET
                !track.loaded && segmentLoadJob?.isActive != true -> LoadAction.SEGMENTS
                else -> LoadAction.NONE
            }
        }
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
                targetLoadJob?.isActive == true
            ) {
                return@synchronized
            }
            val song = track.song
            val requestToken = track.requestToken
            targetLoadJob = scope.launch {
                val target = try {
                    resolveBiliSong(song, AppContainer.biliClient)
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
                        !current.loaded && segmentLoadJob?.isActive != true
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
                segmentLoadJob?.isActive == true
            ) {
                return@synchronized
            }
            val requestToken = track.requestToken
            segmentLoadJob = scope.launch {
                val segments = try {
                    AppContainer.biliSponsorBlockRepository.loadAutoSkipSegments(target)
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

    private fun clearActiveTrackLocked() {
        targetLoadJob?.cancel()
        targetLoadJob = null
        segmentLoadJob?.cancel()
        segmentLoadJob = null
        activeTrack = null
    }

    private class ActiveTrack(
        val song: SongItem,
        val requestToken: Long,
        var target: BiliSponsorBlockTarget? = null,
        var segments: List<BiliSponsorBlockSegment> = emptyList(),
        var loaded: Boolean = false,
        val skipTracker: BiliSponsorBlockSkipTracker = BiliSponsorBlockSkipTracker()
    )

    private enum class LoadAction {
        NONE,
        TARGET,
        SEGMENTS
    }
}
