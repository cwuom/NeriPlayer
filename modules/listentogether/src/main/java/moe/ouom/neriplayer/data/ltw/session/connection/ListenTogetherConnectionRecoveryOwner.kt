package moe.ouom.neriplayer.data.ltw.session.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.api.ltw.reconnect.LISTEN_TOGETHER_MAX_RECONNECT_ATTEMPTS
import moe.ouom.neriplayer.api.ltw.reconnect.isTerminalListenTogetherReconnectError
import moe.ouom.neriplayer.api.ltw.reconnect.listenTogetherReconnectDelayMs
import moe.ouom.neriplayer.data.ltw.platform.ListenTogetherNetworkMonitor
import moe.ouom.neriplayer.data.ltw.platform.NoListenTogetherNetworkMonitor
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import kotlin.time.Duration.Companion.milliseconds

interface ListenTogetherConnectionRecoveryPort {
    fun session(): ListenTogetherSessionState
    fun isController(session: ListenTogetherSessionState): Boolean
    fun updateBackgroundKeepAlive(reason: String)
    fun connectWebSocket()
    fun closeRoomLocally(reason: String)
    fun beginMembershipRecovery(session: ListenTogetherSessionState)
    suspend fun rejoinRoom(identity: ListenTogetherRejoinIdentity)
    fun membershipRecoveryFailed(errorMessage: String)
}

class ListenTogetherConnectionRecoveryOwner(
    private val scope: CoroutineScope,
    private val port: ListenTogetherConnectionRecoveryPort,
    private val networkMonitor: ListenTogetherNetworkMonitor = NoListenTogetherNetworkMonitor
) {
    private val lock = Any()
    private var reconnectJob: Job? = null
    private var membershipRecoveryJob: Job? = null
    private var reconnectAttempt = 0
    private var generation = 0L
    private var stopped = false
    private var watchingNetwork = false
    @Volatile
    var enabled = false
        private set

    fun beginConnect() {
        synchronized(lock) {
            generation++
            stopped = false
            enabled = true
            reconnectJob?.cancel()
            reconnectJob = null
        }
    }

    fun socketOpened() {
        synchronized(lock) {
            generation++
            reconnectAttempt = 0
            reconnectJob?.cancel()
            reconnectJob = null
        }
    }

    fun stop() {
        synchronized(lock) {
            generation++
            stopped = true
            enabled = false
            reconnectAttempt = 0
            reconnectJob?.cancel()
            reconnectJob = null
            membershipRecoveryJob?.cancel()
            membershipRecoveryJob = null
        }
        stopWatchingNetwork()
    }

    fun watchNetwork() {
        val tracker = synchronized(lock) {
            if (watchingNetwork) return
            watchingNetwork = true
            ListenTogetherDefaultNetworkTracker(::onNetworkAvailable, ::onNetworkLost)
        }
        networkMonitor.start(tracker)
    }

    private fun stopWatchingNetwork() {
        synchronized(lock) {
            if (!watchingNetwork) return
            watchingNetwork = false
        }
        networkMonitor.stop()
    }

    private fun onNetworkAvailable() {
        if (!restartBackoffForNetwork()) return
        NPLogger.d(TAG, "default network available: reconnect now")
        requestReconnect("network_available", immediate = true)
    }

    private fun onNetworkLost() {
        NPLogger.d(TAG, "default network lost")
    }

    private fun restartBackoffForNetwork(): Boolean = synchronized(lock) {
        if (!enabled || !isAwaitingReconnect()) return@synchronized false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        true
    }

    private fun isAwaitingReconnect(): Boolean {
        if (reconnectJob?.isActive == true) return true
        return port.session().connectionState == ListenTogetherConnectionState.DISCONNECTED &&
            membershipRecoveryJob?.isActive != true
    }

    fun scheduleReconnect(reason: String) = requestReconnect(reason, immediate = false)

    private fun requestReconnect(reason: String, immediate: Boolean) {
        val observedGeneration = synchronized(lock) { generation }
        val snapshot = port.session()
        if (!shouldScheduleListenTogetherReconnect(snapshot, enabled)) {
            NPLogger.d(TAG, "scheduleReconnect(): skipped, reason=$reason")
            return
        }
        port.updateBackgroundKeepAlive("reconnect_scheduled:$reason")
        enqueueReconnect(snapshot, reason, observedGeneration, immediate)
    }

    private fun enqueueReconnect(
        snapshot: ListenTogetherSessionState,
        reason: String,
        observedGeneration: Long,
        immediate: Boolean
    ) {
        synchronized(lock) {
            if (!mayEnqueueReconnect(snapshot, observedGeneration)) return
            val attempt = ++reconnectAttempt
            if (attempt > LISTEN_TOGETHER_MAX_RECONNECT_ATTEMPTS) {
                NPLogger.w(TAG, "scheduleReconnect(): max attempts reached ($LISTEN_TOGETHER_MAX_RECONNECT_ATTEMPTS), reason=$reason")
                port.closeRoomLocally("reconnect_max_attempts_exceeded")
                return
            }
            launchReconnect(snapshot, reason, attempt, if (immediate) 0L else listenTogetherReconnectDelayMs(attempt))
        }
    }

    private fun mayEnqueueReconnect(snapshot: ListenTogetherSessionState, observedGeneration: Long): Boolean =
        canRunListenTogetherReconnect(snapshot, port.session(), enabled, generation == observedGeneration) &&
            reconnectJob?.isActive != true

    private fun launchReconnect(snapshot: ListenTogetherSessionState, reason: String, attempt: Int, delayMs: Long) {
        val scheduledGeneration = generation
        NPLogger.w(TAG, "scheduleReconnect(): roomId=${snapshot.roomId}, attempt=$attempt, delayMs=$delayMs, reason=$reason")
        reconnectJob = scope.launch { executeReconnect(delayMs, reason, attempt, snapshot, scheduledGeneration) }
    }

    private suspend fun executeReconnect(
        delayMs: Long,
        reason: String,
        attempt: Int,
        scheduledSession: ListenTogetherSessionState,
        scheduledGeneration: Long
    ) {
        delay(delayMs.milliseconds)
        val currentJob = currentCoroutineContext()[Job]
        synchronized(lock) {
            val latest = port.session()
            if (!canRunListenTogetherReconnect(scheduledSession, latest, enabled, generation == scheduledGeneration)) return
            if (reconnectJob == currentJob) reconnectJob = null
            port.updateBackgroundKeepAlive("reconnect_attempt:$reason")
            NPLogger.d(TAG, "reconnect(): roomId=${latest.roomId}, attempt=$attempt")
            if (!recoverMembershipBeforeReconnect("scheduled_reconnect:$reason")) port.connectWebSocket()
        }
    }

    fun recoverMissingListenerMembership(state: ListenTogetherRoomState, reason: String) {
        val snapshot = port.session()
        if (!needsListenTogetherListenerMembershipRecovery(snapshot, state, port.isController(snapshot))) return
        NPLogger.w(TAG, "listener membership missing: userUuid=${snapshot.userUuid}, roomId=${state.roomId}, reason=$reason")
        triggerMembershipRecovery("$reason:missing_member")
    }

    fun recoverFromMembershipError(errorMessage: String?, reason: String): Boolean {
        if (!isListenTogetherMissingMemberError(errorMessage)) return false
        NPLogger.w(TAG, "listener membership error: reason=$reason, error=$errorMessage")
        return triggerMembershipRecovery("$reason:${errorMessage.orEmpty().trim().lowercase()}")
    }

    fun handleTerminalFailure(errorMessage: String?, reason: String): Boolean {
        if (!isTerminalListenTogetherReconnectError(errorMessage)) return false
        NPLogger.w(TAG, "stop reconnect: reason=$reason, error=$errorMessage")
        port.closeRoomLocally(errorMessage ?: "listen_together_unavailable")
        return true
    }

    private fun recoverMembershipBeforeReconnect(reason: String): Boolean {
        if (port.isController(port.session())) return false
        return triggerMembershipRecovery(reason)
    }

    private fun triggerMembershipRecovery(reason: String): Boolean {
        val observedGeneration = synchronized(lock) { generation }
        val snapshot = port.session()
        val identity = listenTogetherRejoinIdentity(snapshot, port.isController(snapshot)) ?: return false
        return startMembershipRecovery(snapshot, identity, reason, observedGeneration)
    }

    private fun startMembershipRecovery(
        snapshot: ListenTogetherSessionState,
        identity: ListenTogetherRejoinIdentity,
        reason: String,
        observedGeneration: Long
    ): Boolean =
        synchronized(lock) {
            val current = port.session()
            if (!canStartListenTogetherMembershipRecovery(
                    snapshot, current, stopped, generation == observedGeneration
                )
            ) return@synchronized false
            if (membershipRecoveryJob?.isActive == true) return@synchronized true
            launchMembershipRecovery(snapshot, identity, reason)
        }

    private fun launchMembershipRecovery(
        snapshot: ListenTogetherSessionState,
        identity: ListenTogetherRejoinIdentity,
        reason: String
    ): Boolean {
        val recoveryGeneration = ++generation
        enabled = true
        reconnectJob?.cancel()
        reconnectJob = null
        port.beginMembershipRecovery(snapshot)
        if (generation != recoveryGeneration || stopped) return false
        membershipRecoveryJob = scope.launch {
            executeMembershipRecovery(snapshot, identity, reason, recoveryGeneration)
        }
        return true
    }

    private suspend fun executeMembershipRecovery(
        snapshot: ListenTogetherSessionState,
        identity: ListenTogetherRejoinIdentity,
        reason: String,
        recoveryGeneration: Long
    ) {
        try {
            NPLogger.w(TAG, "rejoining listener: roomId=${snapshot.roomId}, userUuid=${snapshot.userUuid}, reason=$reason")
            port.rejoinRoom(identity)
            synchronized(lock) {
                if (isCurrentMembershipRecovery(snapshot, recoveryGeneration)) port.connectWebSocket()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            onMembershipRecoveryFailure(error, reason, snapshot, recoveryGeneration)
        } finally {
            val currentJob = currentCoroutineContext()[Job]
            synchronized(lock) {
                if (membershipRecoveryJob == currentJob) membershipRecoveryJob = null
            }
        }
    }

    private fun onMembershipRecoveryFailure(
        error: Throwable,
        reason: String,
        snapshot: ListenTogetherSessionState,
        recoveryGeneration: Long
    ) = synchronized(lock) {
        if (!isCurrentMembershipRecovery(snapshot, recoveryGeneration)) return@synchronized
        val resolvedError = error.message ?: error.javaClass.simpleName
        NPLogger.e(TAG, "listener rejoin failed: reason=$reason", error)
        port.membershipRecoveryFailed(resolvedError)
        if (!handleTerminalFailure(resolvedError, "listener_membership_recovery_failed")) {
            scheduleReconnect("listener_membership_recovery_failed:$reason")
        }
    }

    private fun isCurrentMembershipRecovery(snapshot: ListenTogetherSessionState, recoveryGeneration: Long): Boolean {
        val current = port.session()
        return !stopped && generation == recoveryGeneration && sameListenTogetherMembership(snapshot, current)
    }

    private companion object {
        const val TAG = "NERI-ListenTogether"
    }
}
