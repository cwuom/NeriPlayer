package moe.ouom.neriplayer.core.player.dlna.session

import moe.ouom.neriplayer.data.model.playback.dlna.DlnaTransportState

/**
 * Pure DLNA transport state & track-end decision helper.
 * Distinguishes natural track completion from user-initiated pause/stop,
 * buffering transitions, and out-of-order generation events.
 */
object DlnaCastStateMachine {

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
        val consecutiveEndPausedPolls: Int = 0
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

        if (obs.currentState == DlnaTransportState.PAUSED_PLAYBACK) {
            if (!obs.userPausedOrStopped && obs.hasStartedPlayingInGeneration && obs.durationMs > 0L) {
                val nearEndAtPause =
                    obs.positionMs >= (obs.durationMs - 2_500L).coerceAtLeast(0L) ||
                        obs.lastPlayingPositionMs >= (obs.durationMs - 3_500L).coerceAtLeast(0L)
                val resetToZeroAtEnd = obs.positionMs == 0L &&
                    obs.lastPlayingPositionMs >= (obs.durationMs - 5_000L).coerceAtLeast(1_000L)
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
            val nearEnd = obs.durationMs > 0L && (
                obs.positionMs >= (obs.durationMs - 4_000L).coerceAtLeast(0L) ||
                    obs.lastPlayingPositionMs >= (obs.durationMs - 5_000L).coerceAtLeast(0L)
                )
            val transitionedFromPlaying = obs.previousState == DlnaTransportState.PLAYING ||
                obs.previousState == DlnaTransportState.TRANSITIONING
            // Some renderers reset RelTime to 0 as soon as they transition to STOPPED after EOF
            val resetToZeroAfterPlay = obs.previousState == DlnaTransportState.PLAYING &&
                obs.positionMs == 0L
            if (nearEnd || resetToZeroAfterPlay || (transitionedFromPlaying && obs.consecutiveStoppedPolls >= 2)) {
                return PollDecision.TrackEnded(obs.generationId)
            }
            if (obs.consecutiveStoppedPolls >= 3) {
                return PollDecision.TrackEnded(obs.generationId)
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
        lastPlayingPositionMs: Long
    ): Boolean {
        if (durationMs <= 0L) return false
        val nearEnd = positionMs >= (durationMs - 2_500L).coerceAtLeast(0L) ||
            lastPlayingPositionMs >= (durationMs - 3_500L).coerceAtLeast(0L)
        val resetToZeroAtEnd = positionMs == 0L &&
            lastPlayingPositionMs >= (durationMs - 5_000L).coerceAtLeast(1_000L)
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
