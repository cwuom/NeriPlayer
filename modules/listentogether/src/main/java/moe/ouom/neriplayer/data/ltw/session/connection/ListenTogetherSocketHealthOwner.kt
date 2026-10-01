package moe.ouom.neriplayer.data.ltw.session.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.api.ltw.ws.LISTEN_TOGETHER_SOCKET_RESPONSE_TIMEOUT_MS
import moe.ouom.neriplayer.api.ltw.ws.shouldReconnectListenTogetherSocket
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import kotlin.time.Duration.Companion.milliseconds

const val LISTEN_TOGETHER_SOCKET_KEEP_ALIVE_INTERVAL_MS = 20_000L

interface ListenTogetherSocketHealthPort {
    fun session(): ListenTogetherSessionState
    fun reconnectEnabled(): Boolean
    fun sendPing(sentAtElapsedMs: Long): Boolean
    fun sendLegacyPing(): Boolean
    fun scheduleReconnect(reason: String)
    fun connectWebSocket()
    fun updateBackgroundKeepAlive(reason: String)
}

class ListenTogetherSocketHealthOwner(
    private val scope: CoroutineScope,
    private val port: ListenTogetherSocketHealthPort,
    private val elapsedRealtimeMs: () -> Long,
    private val wallTimeMs: () -> Long
) {
    private var keepAliveJob: Job? = null
    private var foregroundProbeJob: Job? = null
    private var pingSentAtWallMs = 0L
    private var pingSentAtElapsedMs = 0L
    private var clockSyncPingSupported: Boolean? = null
    @Volatile
    var lastMessageAtElapsedMs = 0L
        private set
    @Volatile
    var serverClockOffsetMs = 0L
        private set
    @Volatile
    var pendingRefreshAfterReconnect = false

    fun noteMessage() {
        lastMessageAtElapsedMs = elapsedRealtimeMs()
    }

    fun clearLastMessage() {
        lastMessageAtElapsedMs = 0L
    }

    fun sendPing(): Boolean {
        val sentAtElapsedMs = elapsedRealtimeMs()
        if (pingSentAtElapsedMs <= 0L || lastMessageAtElapsedMs > pingSentAtElapsedMs) {
            pingSentAtElapsedMs = sentAtElapsedMs
            pingSentAtWallMs = wallTimeMs()
        }
        return if (clockSyncPingSupported == false) port.sendLegacyPing() else port.sendPing(sentAtElapsedMs)
    }

    fun onPong(serverNowMs: Long?, echoedSentAtElapsedMs: Long?) {
        val nowElapsedMs = elapsedRealtimeMs()
        val nowWallMs = wallTimeMs()
        val echoedElapsedMs = validEchoedElapsed(echoedSentAtElapsedMs)
        val sentAtElapsedMs = echoedElapsedMs ?: pingSentAtElapsedMs
        val sentAtWallMs = sentWallTimeForPong(echoedElapsedMs, nowWallMs, nowElapsedMs)
        updateOffset(serverNowMs, sentAtWallMs, sentAtElapsedMs, nowWallMs, nowElapsedMs, "pong")
    }

    private fun sentWallTimeForPong(echoedElapsedMs: Long?, nowWallMs: Long, nowElapsedMs: Long): Long =
        echoedElapsedMs?.let { nowWallMs - (nowElapsedMs - it) } ?: pingSentAtWallMs

    private fun validEchoedElapsed(value: Long?): Long? = value?.takeIf { it > 0L }

    fun onServerMessage(serverNowMs: Long?, reason: String) {
        updateOffset(serverNowMs, 0L, 0L, wallTimeMs(), elapsedRealtimeMs(), reason)
    }

    fun onRoundTrip(serverNowMs: Long?, sentAtWallMs: Long, sentAtElapsedMs: Long, reason: String) {
        updateOffset(serverNowMs, sentAtWallMs, sentAtElapsedMs, wallTimeMs(), elapsedRealtimeMs(), reason)
    }

    private fun updateOffset(
        serverNowMs: Long?,
        sentAtWallMs: Long,
        sentAtElapsedMs: Long,
        nowWallMs: Long,
        nowElapsedMs: Long,
        reason: String
    ) {
        val sample = offsetSample(serverNowMs, sentAtWallMs, sentAtElapsedMs, nowWallMs, nowElapsedMs, reason)
            ?: return
        val previous = serverClockOffsetMs
        serverClockOffsetMs = if (previous == 0L) sample else (previous * 7 + sample * 3) / 10
        NPLogger.d(TAG, "updateServerClockOffsetFromRoundTrip(): reason=$reason, offset=$serverClockOffsetMs, sample=$sample")
    }

    private fun offsetSample(
        serverNowMs: Long?,
        sentAtWallMs: Long,
        sentAtElapsedMs: Long,
        nowWallMs: Long,
        nowElapsedMs: Long,
        reason: String
    ): Long? {
        if (serverNowMs == null || serverNowMs <= 0L) return null
        val hasRoundTrip = sentAtWallMs > 0L && sentAtElapsedMs > 0L
        val rtt = if (hasRoundTrip) nowElapsedMs - sentAtElapsedMs else 0L
        if (rtt < 0L) return null
        if (rtt > MAX_RTT_MS) {
            NPLogger.w(TAG, "updateServerClockOffsetFromRoundTrip(): ignore stale sample, reason=$reason, rtt=$rtt")
            return null
        }
        val reference = clientReferenceWallTime(hasRoundTrip, sentAtWallMs, rtt, nowWallMs)
        return serverNowMs - reference
    }

    private fun clientReferenceWallTime(
        hasRoundTrip: Boolean,
        sentAtWallMs: Long,
        rtt: Long,
        nowWallMs: Long
    ): Long = if (hasRoundTrip) sentAtWallMs + rtt / 2 else nowWallMs

    fun handleUnsupportedPing(errorMessage: String): Boolean {
        if (!errorMessage.contains("unsupported event type: np_ping", ignoreCase = true)) return false
        clockSyncPingSupported = false
        NPLogger.d(TAG, "socketKeepAlive(): np_ping unsupported, fallback to legacy ping")
        port.sendLegacyPing()
        return true
    }

    fun markPongSupported() {
        clockSyncPingSupported = true
    }

    fun resetProtocolSupport() {
        clockSyncPingSupported = null
    }

    fun resetConnectionTiming() {
        lastMessageAtElapsedMs = 0L
        pingSentAtWallMs = 0L
        pingSentAtElapsedMs = 0L
        serverClockOffsetMs = 0L
        resetProtocolSupport()
    }

    fun startKeepAlive() {
        if (keepAliveJob?.isActive == true) return
        NPLogger.d(TAG, "startSocketKeepAlive()")
        keepAliveJob = scope.launch {
            while (isActive) {
                delay((keepAliveTick()).milliseconds)
            }
        }
    }

    private fun keepAliveTick(): Long {
        val session = port.session()
        if (session.connectionState != ListenTogetherConnectionState.CONNECTED) return STATE_RECHECK_INTERVAL_MS
        if (shouldReconnectListenTogetherSocket(
                reconnectEnabled = port.reconnectEnabled(),
                connectionState = session.connectionState,
                lastMessageAtElapsedMs = lastMessageAtElapsedMs,
                lastPingSentAtElapsedMs = pingSentAtElapsedMs,
                nowElapsedMs = elapsedRealtimeMs(),
                responseTimeoutMs = LISTEN_TOGETHER_SOCKET_RESPONSE_TIMEOUT_MS
            )
        ) {
            NPLogger.w(TAG, "socketKeepAlive(): response timeout, scheduling reconnect")
            pendingRefreshAfterReconnect = true
            port.scheduleReconnect("socket_keep_alive_response_timeout")
            return LISTEN_TOGETHER_SOCKET_KEEP_ALIVE_INTERVAL_MS
        }
        if (!sendPing()) {
            NPLogger.w(TAG, "socketKeepAlive(): send failed")
            pendingRefreshAfterReconnect = true
            port.scheduleReconnect("socket_keep_alive_send_failed")
        }
        port.updateBackgroundKeepAlive("socket_keep_alive")
        return LISTEN_TOGETHER_SOCKET_KEEP_ALIVE_INTERVAL_MS
    }

    fun stopKeepAlive() {
        NPLogger.d(TAG, "stopSocketKeepAlive()")
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    fun scheduleForegroundProbe(roomId: String?, probeStartedAtElapsedMs: Long) {
        foregroundProbeJob?.cancel()
        foregroundProbeJob = scope.launch {
            delay(FOREGROUND_PROBE_TIMEOUT_MS.milliseconds)
            val session = port.session()
            if (!shouldReconnectListenTogetherForegroundSocket(
                    reconnectEnabled = port.reconnectEnabled(),
                    connectionState = session.connectionState,
                    expectedRoomId = roomId,
                    currentRoomId = session.roomId,
                    lastWebSocketMessageAtElapsedMs = lastMessageAtElapsedMs,
                    probeStartedAtElapsedMs = probeStartedAtElapsedMs
                )
            ) return@launch
            NPLogger.w(TAG, "scheduleForegroundSocketProbe(): no websocket response after foreground recovery")
            pendingRefreshAfterReconnect = true
            port.connectWebSocket()
        }
    }

    fun cancelForegroundProbe() {
        foregroundProbeJob?.cancel()
        foregroundProbeJob = null
    }

    private companion object {
        const val TAG = "NERI-ListenTogether"
        const val MAX_RTT_MS = 30_000L
        const val STATE_RECHECK_INTERVAL_MS = 10_000L
        const val FOREGROUND_PROBE_TIMEOUT_MS = 5_000L
    }
}
