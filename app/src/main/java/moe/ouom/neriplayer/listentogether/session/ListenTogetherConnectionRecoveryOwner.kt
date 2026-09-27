package moe.ouom.neriplayer.listentogether.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.listentogether.network.reconnect.LISTEN_TOGETHER_MAX_RECONNECT_ATTEMPTS
import moe.ouom.neriplayer.listentogether.network.reconnect.isTerminalListenTogetherReconnectError
import moe.ouom.neriplayer.listentogether.network.reconnect.listenTogetherReconnectDelayMs
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSessionState
import kotlin.coroutines.coroutineContext

internal interface ListenTogetherConnectionRecoveryPort {
    fun session(): ListenTogetherSessionState
    fun isController(session: ListenTogetherSessionState): Boolean
    fun updateBackgroundKeepAlive(reason: String)
    fun connectWebSocket()
    fun closeRoomLocally(reason: String)
    fun beginMembershipRecovery(session: ListenTogetherSessionState)
    suspend fun rejoinRoom(identity: ListenTogetherRejoinIdentity)
    fun membershipRecoveryFailed(errorMessage: String)
}

internal class ListenTogetherConnectionRecoveryOwner(
    private val scope: CoroutineScope,
    private val port: ListenTogetherConnectionRecoveryPort
) {
    private val lock = Any()
    private var reconnectJob: Job? = null
    private var membershipRecoveryJob: Job? = null
    private var reconnectAttempt = 0
    private var generation = 0L
    private var stopped = false
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
    }

    fun scheduleReconnect(reason: String) {
        val observedGeneration = synchronized(lock) { generation }
        val snapshot = port.session()
        if (!shouldScheduleListenTogetherReconnect(snapshot, enabled)) {
            NPLogger.d(TAG, "scheduleReconnect(): skipped, reason=$reason")
            return
        }
        port.updateBackgroundKeepAlive("reconnect_scheduled:$reason")
        enqueueReconnect(snapshot, reason, observedGeneration)
    }

    private fun enqueueReconnect(snapshot: ListenTogetherSessionState, reason: String, observedGeneration: Long) {
        synchronized(lock) {
            val current = port.session()
            if (!canRunListenTogetherReconnect(snapshot, current, enabled, generation == observedGeneration)) return
            if (reconnectJob?.isActive == true) return
            val attempt = ++reconnectAttempt
            if (attempt > LISTEN_TOGETHER_MAX_RECONNECT_ATTEMPTS) {
                NPLogger.w(TAG, "scheduleReconnect(): max attempts reached ($LISTEN_TOGETHER_MAX_RECONNECT_ATTEMPTS), reason=$reason")
                port.closeRoomLocally("reconnect_max_attempts_exceeded")
                return
            }
            val delayMs = listenTogetherReconnectDelayMs(attempt)
            val scheduledGeneration = generation
            NPLogger.w(TAG, "scheduleReconnect(): roomId=${snapshot.roomId}, attempt=$attempt, delayMs=$delayMs, reason=$reason")
            reconnectJob = scope.launch { executeReconnect(delayMs, reason, attempt, snapshot, scheduledGeneration) }
        }
    }

    private suspend fun executeReconnect(
        delayMs: Long,
        reason: String,
        attempt: Int,
        scheduledSession: ListenTogetherSessionState,
        scheduledGeneration: Long
    ) {
        delay(delayMs)
        synchronized(lock) {
            val latest = port.session()
            if (!canRunListenTogetherReconnect(scheduledSession, latest, enabled, generation == scheduledGeneration)) return
            if (reconnectJob == coroutineContext[Job]) reconnectJob = null
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
            synchronized(lock) {
                if (membershipRecoveryJob == coroutineContext[Job]) membershipRecoveryJob = null
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
