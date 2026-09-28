package moe.ouom.neriplayer.listentogether.session.liveness

import moe.ouom.neriplayer.listentogether.session.state.LISTEN_TOGETHER_PAUSED_HEARTBEAT_INTERVAL_MS
import moe.ouom.neriplayer.listentogether.session.state.LISTEN_TOGETHER_PLAYING_HEARTBEAT_INTERVAL_MS
import moe.ouom.neriplayer.listentogether.session.state.resolveListenTogetherHeartbeatIntervalMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherConnectionState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSessionState
import kotlin.time.Duration.Companion.milliseconds

internal interface ListenTogetherHeartbeatPort {
    fun session(): ListenTogetherSessionState
    fun isController(session: ListenTogetherSessionState): Boolean
    fun currentTrackShareable(): Boolean
    fun playbackStateName(): String
    fun playbackPositionMs(): Long
    fun buildHeartbeat(state: String, positionMs: Long): ListenTogetherEvent
    fun sendHeartbeat(event: ListenTogetherEvent)
}

internal class ListenTogetherHeartbeatOwner(
    private val scope: CoroutineScope,
    private val port: ListenTogetherHeartbeatPort,
    private val elapsedRealtimeMs: () -> Long
) {
    private var heartbeatJob: Job? = null
    @Volatile
    private var lastOutboundSyncAtMs = 0L

    fun noteOutboundSync() {
        lastOutboundSyncAtMs = elapsedRealtimeMs()
    }

    fun start() {
        if (heartbeatJob?.isActive == true) return
        NPLogger.d(TAG, "startHeartbeat()")
        if (lastOutboundSyncAtMs == 0L) noteOutboundSync()
        heartbeatJob = scope.launch { runHeartbeatLoop() }
    }

    private suspend fun runHeartbeatLoop() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay((heartbeatTick()).milliseconds)
        }
    }

    private fun heartbeatTick(): Long {
        val session = port.session()
        if (session.connectionState != ListenTogetherConnectionState.CONNECTED ||
            !port.isController(session) || !port.currentTrackShareable()
        ) return STATE_RECHECK_INTERVAL_MS
        val now = elapsedRealtimeMs()
        val idleMs = now - lastOutboundSyncAtMs
        val playbackState = port.playbackStateName()
        val intervalMs = resolveListenTogetherHeartbeatIntervalMs(
            isPlaying = playbackState == "playing",
            playingIntervalMs = LISTEN_TOGETHER_PLAYING_HEARTBEAT_INTERVAL_MS,
            pausedIntervalMs = LISTEN_TOGETHER_PAUSED_HEARTBEAT_INTERVAL_MS
        )
        val remainingMs = intervalMs - idleMs
        if (remainingMs > 0L) return minOf(remainingMs, STATE_RECHECK_INTERVAL_MS)
        val event = port.buildHeartbeat(playbackState, port.playbackPositionMs().coerceAtLeast(0L))
        noteOutboundSync()
        NPLogger.d(TAG, "heartbeat(): eventId=${event.eventId}, positionMs=${event.positionMs}, idleMs=$idleMs")
        port.sendHeartbeat(event)
        return 0L
    }

    fun stop() {
        NPLogger.d(TAG, "stopHeartbeat()")
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun reset() {
        stop()
        lastOutboundSyncAtMs = 0L
    }

    private companion object {
        const val TAG = "NERI-ListenTogether"
        const val STATE_RECHECK_INTERVAL_MS = 10_000L
    }
}
