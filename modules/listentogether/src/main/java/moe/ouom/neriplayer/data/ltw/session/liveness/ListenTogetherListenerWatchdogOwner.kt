package moe.ouom.neriplayer.data.ltw.session.liveness

import moe.ouom.neriplayer.data.ltw.mapping.ListenTogetherSongMapper
import moe.ouom.neriplayer.data.ltw.playback.ListenTogetherPlaybackHost
import moe.ouom.neriplayer.data.ltw.session.state.shouldRepairListenTogetherListenerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.ltw.playback.ListenTogetherListenerStallRecovery
import moe.ouom.neriplayer.data.ltw.playback.expectedPositionMs
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import kotlin.time.Duration.Companion.milliseconds

internal data class ListenTogetherListenerWatchdogSnapshot(
    val session: ListenTogetherSessionState,
    val room: ListenTogetherRoomState?,
    val isController: Boolean,
    val pendingRepairVersion: Long,
    val lastSocketMessageAtElapsedMs: Long,
    val serverClockOffsetMs: Long
)

internal interface ListenTogetherListenerWatchdogPort {
    fun snapshot(): ListenTogetherListenerWatchdogSnapshot
    fun isControllerNow(): Boolean
    fun retryPendingMemberRequest(room: ListenTogetherRoomState?)
    fun applyRoomStateToPlayer(room: ListenTogetherRoomState, cause: String, expectedPositionMs: Long)
    fun requestControllerLink(room: ListenTogetherRoomState, cause: String, force: Boolean)
    suspend fun refreshRoomState(baseUrl: String, roomId: String)
    fun onRefreshFailure(error: Throwable, reason: String)
}

internal class ListenTogetherListenerWatchdogOwner(
    private val scope: CoroutineScope,
    private val playback: ListenTogetherPlaybackHost,
    private val songMapper: ListenTogetherSongMapper,
    private val port: ListenTogetherListenerWatchdogPort,
    private val elapsedRealtimeMs: () -> Long
) : ListenTogetherSongMapper by songMapper {
    private val stallRecovery = ListenTogetherListenerStallRecovery(
        playback = playback,
        songMapper = songMapper,
        stallTimeoutMs = 8_000L,
        recoveryCooldownMs = 12_000L
    )
    private var watchdogJob: Job? = null
    @Volatile
    private var lastRefreshAtElapsedMs = 0L

    fun start() {
        if (watchdogJob?.isActive == true) return
        NPLogger.d(TAG, "startSyncWatchdog()")
        lastRefreshAtElapsedMs = 0L
        watchdogJob = scope.launch { runWatchdog() }
    }

    private suspend fun runWatchdog() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(WATCHDOG_INTERVAL_MS.milliseconds)
            onWatchdogTick(port.snapshot())
        }
    }

    private suspend fun onWatchdogTick(snapshot: ListenTogetherListenerWatchdogSnapshot) {
        if (!isActiveListener(snapshot)) return
        port.retryPendingMemberRequest(snapshot.room)
        snapshot.room?.let { room ->
            applyListenerSync(room, snapshot.serverClockOffsetMs)
            port.requestControllerLink(room, "listener_watchdog", force = false)
        }
        refreshRoomStateIfDue(snapshot, "listener_watchdog")
    }

    private fun isActiveListener(snapshot: ListenTogetherListenerWatchdogSnapshot): Boolean =
        hasConnectedRoom(snapshot.session) && !snapshot.isController

    private fun hasConnectedRoom(session: ListenTogetherSessionState): Boolean =
        session.connectionState == ListenTogetherConnectionState.CONNECTED && !session.roomId.isNullOrBlank()

    fun stop() {
        NPLogger.d(TAG, "stopSyncWatchdog()")
        watchdogJob?.cancel()
        watchdogJob = null
    }

    fun resetRefreshTime() {
        lastRefreshAtElapsedMs = 0L
    }

    fun resetRecovery() {
        resetRefreshTime()
        stallRecovery.reset()
    }

    private fun applyListenerSync(room: ListenTogetherRoomState, serverClockOffsetMs: Long) {
        if (!mayApplyListenerSync(room)) return
        val expectedPositionMs = expectedPosition(room, serverClockOffsetMs)
        val needsStallRecovery = stallRecovery.shouldRecover(room, elapsedRealtimeMs())
        val cause = if (needsStallRecovery) "WATCHDOG_STALL" else "WATCHDOG"
        port.applyRoomStateToPlayer(room, cause, expectedPositionMs)
        requestStallRecoveryIfNeeded(room, cause, needsStallRecovery)
    }

    private fun mayApplyListenerSync(room: ListenTogetherRoomState): Boolean =
        !port.isControllerNow() && room.roomStatus == ListenTogetherRoomStatuses.ACTIVE

    private fun requestStallRecoveryIfNeeded(room: ListenTogetherRoomState, cause: String, needed: Boolean) {
        if (needed) port.requestControllerLink(room, cause, force = true)
    }

    private fun expectedPosition(room: ListenTogetherRoomState, serverClockOffsetMs: Long): Long =
        room.playback.expectedPositionMs(
            serverClockOffsetMs = serverClockOffsetMs,
            durationMs = room.targetSongItem()?.durationMs ?: 0L
        )

    private suspend fun refreshRoomStateIfDue(snapshot: ListenTogetherListenerWatchdogSnapshot, reason: String) {
        val location = refreshLocation(snapshot.session) ?: return
        val now = elapsedRealtimeMs()
        if (!shouldRefresh(snapshot, now)) return
        lastRefreshAtElapsedMs = now
        runCatching { port.refreshRoomState(location.first, location.second) }
            .onFailure { port.onRefreshFailure(it, reason) }
    }

    private fun refreshLocation(session: ListenTogetherSessionState): Pair<String, String>? {
        val baseUrl = session.baseUrl
        if (baseUrl.isNullOrBlank()) return null
        return baseUrl to session.roomId.orEmpty()
    }

    private fun shouldRefresh(snapshot: ListenTogetherListenerWatchdogSnapshot, now: Long): Boolean =
        shouldRepairListenTogetherListenerState(
                nowElapsedMs = now,
                lastWebSocketMessageAtElapsedMs = snapshot.lastSocketMessageAtElapsedMs,
                lastRefreshAtElapsedMs = lastRefreshAtElapsedMs,
                pendingVersionGap = snapshot.pendingRepairVersion,
                webSocketSilenceTimeoutMs = SOCKET_SILENCE_TIMEOUT_MS,
                repairMinIntervalMs = REPAIR_MIN_INTERVAL_MS
            )

    private companion object {
        const val TAG = "NERI-ListenTogether"
        const val WATCHDOG_INTERVAL_MS = 8_000L
        const val SOCKET_SILENCE_TIMEOUT_MS = 45_000L
        const val REPAIR_MIN_INTERVAL_MS = 30_000L
    }
}
