package moe.ouom.neriplayer.data.ltw.session.control

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherAppliedEvent
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherCause
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherControlResponse
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.session.AcceptedRoomState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState

interface ListenTogetherHttpControlFallbackPort {
    fun session(): ListenTogetherSessionState
    suspend fun send(baseUrl: String, roomId: String, token: String, event: ListenTogetherEvent): ListenTogetherControlResponse
    fun setLastError(error: String?)
    fun acknowledge(eventId: String?)
    fun tryQueueMutationLegacyFallback(error: String, eventId: String?): Boolean
    fun tryTrackFinishedLegacyFallback(error: String): Boolean
    fun handleTerminalFailure(error: String, reason: String): Boolean
    fun recoverFromMembershipError(error: String, reason: String)
    fun accept(state: ListenTogetherRoomState, expectedPositionMs: Long?, cause: ListenTogetherCause?): AcceptedRoomState?
    fun isController(): Boolean
    fun applyToPlayer(state: ListenTogetherRoomState, causeType: String?, expectedPositionMs: Long?)
    fun requestControllerLink(state: ListenTogetherRoomState, causeType: String?)
}

class ListenTogetherHttpControlFallbackOwner(
    private val scope: CoroutineScope,
    private val port: ListenTogetherHttpControlFallbackPort
) {
    private val lock = Any()
    private val pendingJobs = mutableSetOf<Job>()
    private var generation = 0L

    fun send(event: ListenTogetherEvent, reason: String, expectedRoomId: String?) = synchronized(lock) {
        val target = httpFallbackTarget(port.session(), expectedRoomId) ?: return@synchronized
        val scheduledGeneration = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            execute(target, event, reason, scheduledGeneration)
        }
        pendingJobs += job
        job.invokeOnCompletion { synchronized(lock) { pendingJobs.remove(job) } }
        job.start()
        Unit
    }

    fun reset() {
        val jobs = synchronized(lock) {
            generation++
            pendingJobs.toList().also { pendingJobs.clear() }
        }
        jobs.forEach(Job::cancel)
    }

    private suspend fun execute(target: HttpFallbackTarget, event: ListenTogetherEvent, reason: String, scheduledGeneration: Long) {
        if (!synchronized(lock) { isCurrent(target, scheduledGeneration) }) return
        val response = try {
            port.send(target.baseUrl, target.roomId, target.token, event)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            onFailureIfCurrent(target, scheduledGeneration, error, reason)
            return
        }
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (isCurrent(target, scheduledGeneration)) handleResponse(response, event, reason)
        }
    }

    private fun isCurrent(target: HttpFallbackTarget, scheduledGeneration: Long): Boolean =
        generation == scheduledGeneration && target.matches(port.session())

    private fun onFailureIfCurrent(target: HttpFallbackTarget, scheduledGeneration: Long, error: Throwable, reason: String) =
        synchronized(lock) {
            if (!isCurrent(target, scheduledGeneration)) return@synchronized
            val message = error.message ?: error.javaClass.simpleName
            NPLogger.w(TAG, "http control fallback failed: reason=$reason, error=$message", error)
            port.setLastError(message)
            recover(message, "http_control_fallback")
        }

    private fun handleResponse(response: ListenTogetherControlResponse, event: ListenTogetherEvent, reason: String) {
        if (!response.ok || !response.error.isNullOrBlank()) {
            handleRejection(response.error ?: "control event rejected", event, reason)
            return
        }
        port.setLastError(null)
        port.acknowledge(event.eventId)
        response.applied?.let(::applyCommittedState)
    }

    private fun handleRejection(error: String, event: ListenTogetherEvent, reason: String) {
        NPLogger.w(TAG, "http control fallback rejected: type=${event.type}, reason=$reason, error=$error")
        port.setLastError(error)
        if (port.tryQueueMutationLegacyFallback(error, event.eventId)) return
        if (port.tryTrackFinishedLegacyFallback(error)) return
        recover(error, "http_control_fallback_response")
    }

    private fun recover(error: String, reason: String) {
        if (!port.handleTerminalFailure(error, reason)) port.recoverFromMembershipError(error, reason)
    }

    private fun applyCommittedState(applied: ListenTogetherAppliedEvent) {
        val state = applied.state ?: return
        val accepted = port.accept(state, applied.expectedPositionMs, applied.causedBy) ?: return
        if (port.isController()) return
        val causeType = applied.causedBy?.type
        port.applyToPlayer(accepted.state, causeType, accepted.expectedPositionMs)
        port.requestControllerLink(accepted.state, causeType)
    }

    private companion object {
        const val TAG = "NERI-ListenTogether"
    }
}

private data class HttpFallbackTarget(val baseUrl: String, val roomId: String, val token: String, val userUuid: String?) {
    fun matches(session: ListenTogetherSessionState): Boolean =
        baseUrl == session.baseUrl && roomId == session.roomId && token == session.token && userUuid == session.userUuid
}

private fun httpFallbackTarget(session: ListenTogetherSessionState, expectedRoomId: String?): HttpFallbackTarget? {
    val baseUrl = nonBlankFallbackField(session.baseUrl) ?: return null
    val roomId = nonBlankFallbackField(session.roomId) ?: return null
    val token = nonBlankFallbackField(session.token) ?: return null
    if (roomId != expectedRoomId) return null
    return HttpFallbackTarget(baseUrl, roomId, token, session.userUuid)
}

private fun nonBlankFallbackField(value: String?): String? = if (value.isNullOrBlank()) null else value
