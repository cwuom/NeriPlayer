package moe.ouom.neriplayer.core.player.dlna.session

import moe.ouom.neriplayer.data.model.playback.dlna.DlnaTransportState

/**
 * Pure DLNA transport state & track-end decision helper.
 * Distinguishes natural track completion from user-initiated pause/stop,
 * buffering transitions, and out-of-order generation events.
 */
object DlnaCastStateMachine {

    private const val MAX_SLEEP_POLL_PROJECTION_MS = 4L * 60L * 60L * 1000L
    private const val STARTUP_STOPPED_GRACE_POLLS = 8
    private const val STARTUP_STOPPED_GRACE_MS = 12_000L
    internal const val TRACK_END_REWIND_CEILING_MS = 2_000L

    data class PollObservation(
        val previousState: DlnaTransportState,
        val currentState: DlnaTransportState,
        val positionMs: Long,
        val durationMs: Long,
        val userPausedOrStopped: Boolean,
        val generationId: Long,
        val activeGenerationId: Long,
        val hasStartedPlayingInGeneration: Boolean,
        val consecutiveStoppedPolls: Int,
        val lastPlayingPositionMs: Long = 0L,
        val consecutiveEndPausedPolls: Int = 0,
        val elapsedSinceLastPlayingPollMs: Long = 0L,
        val playCommandSentInGeneration: Boolean = false,
        val playCommandStartPositionMs: Long = 0L,
        val elapsedSincePlayCommandMs: Long = 0L
    )

    sealed class PollDecision {
        data object KeepPolling : PollDecision()
        data class SyncPlayback(
            val isPlaying: Boolean,
            val positionMs: Long,
            val durationMs: Long,
            val markStartedPlaying: Boolean
        ) : PollDecision()

        data class TrackEnded(val generationId: Long) : PollDecision()
    }

    fun evaluatePoll(obs: PollObservation): PollDecision {
        if (obs.generationId != obs.activeGenerationId || obs.activeGenerationId <= 0L) {
            return PollDecision.KeepPolling
        }

        if (obs.currentState == DlnaTransportState.PLAYING) {
            return PollDecision.SyncPlayback(
                isPlaying = true,
                positionMs = obs.positionMs.coerceAtLeast(0L),
                durationMs = obs.durationMs.coerceAtLeast(0L),
                markStartedPlaying = true
            )
        }

        if (obs.currentState == DlnaTransportState.TRANSITIONING) {
            return PollDecision.KeepPolling
        }

        val projectedPlayingPositionMs = projectPlayingPositionMs(
            lastPlayingPositionMs = obs.lastPlayingPositionMs,
            elapsedSinceLastPlayingPollMs = obs.elapsedSinceLastPlayingPollMs,
            playCommandSentInGeneration = obs.playCommandSentInGeneration,
            playCommandStartPositionMs = obs.playCommandStartPositionMs,
            elapsedSincePlayCommandMs = obs.elapsedSincePlayCommandMs
        )

        if (obs.currentState == DlnaTransportState.PAUSED_PLAYBACK) {
            if (!obs.userPausedOrStopped &&
                (obs.hasStartedPlayingInGeneration || obs.playCommandSentInGeneration) &&
                isPausedAtTrackEnd(
                    positionMs = obs.positionMs,
                    durationMs = obs.durationMs,
                    lastPlayingPositionMs = obs.lastPlayingPositionMs,
                    elapsedSinceLastPlayingPollMs = obs.elapsedSinceLastPlayingPollMs,
                    playCommandSentInGeneration = obs.playCommandSentInGeneration,
                    playCommandStartPositionMs = obs.playCommandStartPositionMs,
                    elapsedSincePlayCommandMs = obs.elapsedSincePlayCommandMs
                )
            ) {
                return if (obs.consecutiveEndPausedPolls >= 2) {
                    PollDecision.TrackEnded(obs.generationId)
                } else {
                    PollDecision.KeepPolling
                }
            }
            if (!obs.userPausedOrStopped && isStartupPauseGraceActive(
                    hasStartedPlayingInGeneration = obs.hasStartedPlayingInGeneration,
                    playCommandSentInGeneration = obs.playCommandSentInGeneration,
                    elapsedSincePlayCommandMs = obs.elapsedSincePlayCommandMs
                )
            ) {
                // 切歌装入 URI 后、渲染器尚未从上一首的 PAUSED_PLAYBACK 切入 PLAYING 时，不能反向覆盖播放意图
                return PollDecision.KeepPolling
            }
            return PollDecision.SyncPlayback(
                isPlaying = false,
                positionMs = obs.positionMs.coerceAtLeast(0L),
                durationMs = obs.durationMs.coerceAtLeast(0L),
                markStartedPlaying = false
            )
        }

        val stoppedLike = obs.currentState == DlnaTransportState.STOPPED ||
            obs.currentState == DlnaTransportState.NO_MEDIA_PRESENT

        if (stoppedLike && !obs.userPausedOrStopped && obs.hasStartedPlayingInGeneration) {
            val tailToleranceMs = if (obs.durationMs > 15_000L) 6_500L else 3_000L
            val endThresholdMs = (obs.durationMs - tailToleranceMs).coerceAtLeast(1_000L)
            val rewoundNearStart = obs.positionMs in 0L..TRACK_END_REWIND_CEILING_MS
            val nearEnd = obs.durationMs > 0L && (
                obs.positionMs >= (obs.durationMs - 4_000L).coerceAtLeast(0L) ||
                    (!rewoundNearStart && obs.lastPlayingPositionMs >= (obs.durationMs - 5_000L).coerceAtLeast(0L)) ||
                    (rewoundNearStart && projectedPlayingPositionMs >= endThresholdMs)
                )
            val midTrackBeforeStop = obs.durationMs > 10_000L &&
                obs.lastPlayingPositionMs > 0L &&
                projectedPlayingPositionMs < endThresholdMs
            // Renderers often reset RelTime to 00:00 or 00:01 upon STOPPED after EOF; boot-clock projection
            // across screen-off Doze/sleep distinguishes natural full-track EOF from mid-track hardware Stop.
            val resetToZeroAfterPlay = !midTrackBeforeStop &&
                rewoundNearStart &&
                (obs.previousState == DlnaTransportState.PLAYING ||
                    projectedPlayingPositionMs >= endThresholdMs ||
                    (obs.durationMs <= 0L && obs.consecutiveStoppedPolls >= 2))
            if (nearEnd || resetToZeroAfterPlay) {
                return PollDecision.TrackEnded(obs.generationId)
            }
        }

        if (stoppedLike && !obs.userPausedOrStopped && !obs.hasStartedPlayingInGeneration) {
            if (!obs.playCommandSentInGeneration) {
                // SetAVTransportURI loaded, Play still in flight — never clobber playback intent to PAUSED
                return PollDecision.KeepPolling
            }
            val endThresholdMs = (obs.durationMs - 5_000L).coerceAtLeast(1_000L)
            if (obs.durationMs > 0L && projectedPlayingPositionMs >= endThresholdMs) {
                // Screen locked immediately after Play and polling stayed frozen across the entire track
                return PollDecision.TrackEnded(obs.generationId)
            }
            if (obs.durationMs <= 0L &&
                obs.elapsedSincePlayCommandMs >= 30_000L &&
                obs.consecutiveStoppedPolls >= 2
            ) {
                return PollDecision.TrackEnded(obs.generationId)
            }
            if (obs.consecutiveStoppedPolls <= STARTUP_STOPPED_GRACE_POLLS &&
                (obs.elapsedSincePlayCommandMs <= 0L || obs.elapsedSincePlayCommandMs < STARTUP_STOPPED_GRACE_MS)
            ) {
                // Renderer is still opening HTTP stream and transitioning STOPPED -> PLAYING
                return PollDecision.KeepPolling
            }
        }

        return PollDecision.SyncPlayback(
            isPlaying = false,
            positionMs = obs.positionMs.coerceAtLeast(0L),
            durationMs = obs.durationMs.coerceAtLeast(0L),
            markStartedPlaying = false
        )
    }

    private fun projectPlayingPositionMs(
        lastPlayingPositionMs: Long,
        elapsedSinceLastPlayingPollMs: Long,
        playCommandSentInGeneration: Boolean = false,
        playCommandStartPositionMs: Long = 0L,
        elapsedSincePlayCommandMs: Long = 0L
    ): Long = when {
        lastPlayingPositionMs > 0L && elapsedSinceLastPlayingPollMs > 0L ->
            lastPlayingPositionMs + elapsedSinceLastPlayingPollMs.coerceIn(0L, MAX_SLEEP_POLL_PROJECTION_MS)
        lastPlayingPositionMs > 0L ->
            lastPlayingPositionMs
        playCommandSentInGeneration && elapsedSincePlayCommandMs > 0L ->
            playCommandStartPositionMs.coerceAtLeast(0L) +
                elapsedSincePlayCommandMs.coerceIn(0L, MAX_SLEEP_POLL_PROJECTION_MS)
        else -> 0L
    }

    fun isPausedAtTrackEnd(
        positionMs: Long,
        durationMs: Long,
        lastPlayingPositionMs: Long,
        elapsedSinceLastPlayingPollMs: Long = 0L,
        playCommandSentInGeneration: Boolean = false,
        playCommandStartPositionMs: Long = 0L,
        elapsedSincePlayCommandMs: Long = 0L
    ): Boolean {
        if (durationMs <= 0L) return false
        val projectedPlayingMs = projectPlayingPositionMs(
            lastPlayingPositionMs = lastPlayingPositionMs,
            elapsedSinceLastPlayingPollMs = elapsedSinceLastPlayingPollMs,
            playCommandSentInGeneration = playCommandSentInGeneration,
            playCommandStartPositionMs = playCommandStartPositionMs,
            elapsedSincePlayCommandMs = elapsedSincePlayCommandMs
        )
        val tailToleranceMs = if (durationMs > 15_000L) 6_500L else 2_500L
        val endThresholdMs = (durationMs - tailToleranceMs).coerceAtLeast(1_000L)
        val rewoundNearStart = positionMs in 0L..TRACK_END_REWIND_CEILING_MS
        val nearEnd = positionMs >= (durationMs - 3_000L).coerceAtLeast(0L) ||
            (!rewoundNearStart && lastPlayingPositionMs >= (durationMs - 5_000L).coerceAtLeast(0L))
        // 山灵 EM7 等渲染器自然播完上报 PAUSED_PLAYBACK 时会将 RelTime 拉回 00:00 或 00:01 (1000ms)
        val rewoundToStartAtEnd = rewoundNearStart &&
            projectedPlayingMs >= endThresholdMs &&
            (lastPlayingPositionMs > TRACK_END_REWIND_CEILING_MS ||
                projectedPlayingMs >= (durationMs - 3_000L).coerceAtLeast(1_000L))
        return nearEnd || rewoundToStartAtEnd
    }

    fun matchesNextTrackUri(
        reportedTrackUri: String,
        expectedNextUrl: String?,
        expectedNextPath: String?
    ): Boolean {
        if (reportedTrackUri.isBlank()) return false
        if (!expectedNextUrl.isNullOrBlank() && reportedTrackUri == expectedNextUrl) return true
        if (!expectedNextPath.isNullOrBlank() && reportedTrackUri.endsWith(expectedNextPath)) return true
        return false
    }

    fun clampRemoteVolume(requestedVolume: Int): Int = requestedVolume.coerceIn(0, 100)
}
