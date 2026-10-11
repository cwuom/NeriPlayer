package moe.ouom.neriplayer.data.ltw.session.liveness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.ltw.mapping.ListenTogetherSongMapper
import moe.ouom.neriplayer.data.ltw.playback.ListenTogetherPlaybackHost
import moe.ouom.neriplayer.data.ltw.playback.ListenTogetherSoftSyncRecheckAction
import moe.ouom.neriplayer.data.ltw.playback.expectedPositionMs
import moe.ouom.neriplayer.data.ltw.playback.isReadyForListenTogetherSoftSync
import moe.ouom.neriplayer.data.ltw.playback.resolveListenTogetherSoftSyncPlaybackRate
import moe.ouom.neriplayer.data.ltw.playback.resolveListenTogetherSoftSyncRecheckAction
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

internal const val LISTEN_TOGETHER_SOFT_SYNC_RECHECK_INTERVAL_MS = 1_000L

internal data class ListenTogetherSoftSyncRecheckConfig(
    val intervalMs: Long,
    val minDriftMs: Long,
    val fastDriftMs: Long,
    val forcePositionSyncMs: Long
)

internal class ListenTogetherSoftSyncRateRecheckOwner(
    private val scope: CoroutineScope,
    private val playback: ListenTogetherPlaybackHost,
    songMapper: ListenTogetherSongMapper,
    private val config: ListenTogetherSoftSyncRecheckConfig,
    private val session: () -> ListenTogetherSessionState,
    private val room: () -> ListenTogetherRoomState?,
    private val isController: (ListenTogetherSessionState) -> Boolean,
    private val serverClockOffset: () -> Long,
    private val applyRoom: (ListenTogetherRoomState, String, Long) -> Unit
) : ListenTogetherSongMapper by songMapper {
    private var job: Job? = null
    private var convergedTicks = 0

    fun reconcile() {
        if (abs(playback.listenTogetherSyncPlaybackRate - 1f) < 0.001f) {
            stop()
            return
        }
        if (job?.isActive == true) return
        convergedTicks = 0
        job = scope.launch {
            try {
                while (isActive) {
                    delay(config.intervalMs.milliseconds)
                    if (!tick()) return@launch
                }
            } finally {
                if (job === coroutineContext[Job]) job = null
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private fun tick(): Boolean {
        val state = room()
        val expected = expectedPosition(state)
        val drift = expected - playback.playbackPositionFlow.value.coerceAtLeast(0L)
        return when (action(state, drift)) {
            ListenTogetherSoftSyncRecheckAction.NONE -> false
            ListenTogetherSoftSyncRecheckAction.RESET_RATE -> { playback.resetListenTogetherSyncPlaybackRate(); false }
            ListenTogetherSoftSyncRecheckAction.FORCE_POSITION_SYNC -> {
                if (state != null) applyRoom(state, "SOFT_SYNC_RECHECK", expected) else playback.resetListenTogetherSyncPlaybackRate()
                false
            }
            ListenTogetherSoftSyncRecheckAction.KEEP_RATE -> keepRate(drift)
            ListenTogetherSoftSyncRecheckAction.CONVERGED -> holdUntilConverged()
        }
    }

    private fun holdUntilConverged(): Boolean {
        if (++convergedTicks < CONVERGED_TICKS_TO_RESET) return true
        playback.resetListenTogetherSyncPlaybackRate()
        return false
    }

    private fun expectedPosition(state: ListenTogetherRoomState?): Long {
        val song = state?.targetSongItem() ?: return 0L
        return state.playback.expectedPositionMs(serverClockOffsetMs = serverClockOffset(), durationMs = song.durationMs)
    }

    private fun action(state: ListenTogetherRoomState?, drift: Long): ListenTogetherSoftSyncRecheckAction {
        val currentSession = session()
        return resolveListenTogetherSoftSyncRecheckAction(
            currentRate = playback.listenTogetherSyncPlaybackRate,
            sessionConnected = currentSession.connectionState == ListenTogetherConnectionState.CONNECTED,
            isController = isController(currentSession),
            desiredPlaying = state?.playback?.state == "playing",
            localPlaying = playback.isReadyForListenTogetherSoftSync(),
            currentTrackMatchesRoom = currentTrackMatches(state),
            signedDriftMs = drift,
            softSyncMinDriftMs = config.minDriftMs,
            forcePositionSyncDriftMs = config.forcePositionSyncMs
        )
    }

    private fun currentTrackMatches(state: ListenTogetherRoomState?): Boolean {
        val song = state?.targetSongItem() ?: return false
        return playback.currentSongFlow.value?.sameTrackAs(song) == true
    }

    private fun keepRate(drift: Long): Boolean {
        convergedTicks = 0
        val rate = resolveListenTogetherSoftSyncPlaybackRate(abs(drift), drift, true, false, config.minDriftMs, config.fastDriftMs, config.forcePositionSyncMs)
        if (rate == null) { playback.resetListenTogetherSyncPlaybackRate(); return false }
        playback.setListenTogetherSyncPlaybackRate(rate)
        return true
    }

    private companion object {
        const val CONVERGED_TICKS_TO_RESET = 2
    }
}
