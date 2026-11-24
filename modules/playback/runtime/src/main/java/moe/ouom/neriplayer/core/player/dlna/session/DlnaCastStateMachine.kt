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

        val projectedPlayingPositionMs = when {
            obs.lastPlayingPositionMs > 0L && obs.elapsedSinceLastPlayingPollMs > 0L ->
                obs.lastPlayingPositionMs +
                    obs.elapsedSinceLastPlayingPollMs.coerceIn(0L, MAX_SLEEP_POLL_PROJECTION_MS)
            obs.lastPlayingPositionMs > 0L ->
                obs.lastPlayingPositionMs
            obs.playCommandSentInGeneration && obs.elapsedSincePlayCommandMs > 0L ->
                obs.playCommandStartPositionMs.coerceAtLeast(0L) +
                    obs.elapsedSincePlayCommandMs.coerceIn(0L, MAX_SLEEP_POLL_PROJECTION_MS)
            else -> 0L
        }

        if (obs.currentState == DlnaTransportState.PAUSED_PLAYBACK) {
            if (!obs.userPausedOrStopped &&
                (obs.hasStartedPlayingInGeneration || obs.playCommandSentInGeneration) &&
                obs.durationMs > 0L
            ) {
                val nearEndAtPause =
                    obs.positionMs >= (obs.durationMs - 2_500L).coerceAtLeast(0L) ||
                        obs.lastPlayingPositionMs >= (obs.durationMs - 3_500L).coerceAtLeast(0L)
                val resetToZeroAtEnd = obs.positionMs == 0L &&
                    projectedPlayingPositionMs >= (obs.durationMs - 5_000L).coerceAtLeast(1_000L)
                if (nearEndAtPause || resetToZeroAtEnd) {
                    return if (obs.consecutiveEndPausedPolls >= 2) {
                        PollDecision.TrackEnded(obs.generationId)
                    } else {
                        PollDecision.KeepPolling
                    }
                }
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
            val endThresholdMs = (obs.durationMs - 5_000L).coerceAtLeast(1_000L)
            val nearEnd = obs.durationMs > 0L && (
                obs.positionMs >= (obs.durationMs - 4_000L).coerceAtLeast(0L) ||
                    obs.lastPlayingPositionMs >= (obs.durationMs - 5_000L).coerceAtLeast(0L) ||
                    (obs.positionMs == 0L && projectedPlayingPositionMs >= endThresholdMs)
                )
            val midTrackBeforeStop = obs.durationMs > 10_000L &&
                obs.lastPlayingPositionMs > 0L &&
                projectedPlayingPositionMs < endThresholdMs
            // Renderers often reset RelTime to 0 upon STOPPED after EOF; boot-clock projection across
            // screen-off Doze/sleep distinguishes natural full-track EOF from mid-track hardware Stop.
            val resetToZeroAfterPlay = !midTrackBeforeStop &&
                obs.positionMs == 0L &&
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

    fun isPausedAtTrackEnd(
        positionMs: Long,
        durationMs: Long,
        lastPlayingPositionMs: Long,
        elapsedSinceLastPlayingPollMs: Long = 0L
    ): Boolean {
        if (durationMs <= 0L) return false
        val projectedPlayingMs = if (lastPlayingPositionMs > 0L && elapsedSinceLastPlayingPollMs > 0L) {
            lastPlayingPositionMs + elapsedSinceLastPlayingPollMs.coerceIn(0L, MAX_SLEEP_POLL_PROJECTION_MS)
        } else {
            lastPlayingPositionMs
        }
        val nearEnd = positionMs >= (durationMs - 2_500L).coerceAtLeast(0L) ||
            lastPlayingPositionMs >= (durationMs - 3_500L).coerceAtLeast(0L)
        val resetToZeroAtEnd = positionMs == 0L &&
            projectedPlayingMs >= (durationMs - 5_000L).coerceAtLeast(1_000L)
        return nearEnd || resetToZeroAtEnd
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
