package moe.ouom.neriplayer.data.ltw.session.liveness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.ltw.playback.LISTEN_TOGETHER_LISTENER_SAFETY_RESUME_CAUSE
import moe.ouom.neriplayer.data.ltw.playback.ListenTogetherPlaybackHost
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherStateResponse
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState

internal interface ListenTogetherSafetyPauseResumePort {
    fun session(): ListenTogetherSessionState
    fun isController(session: ListenTogetherSessionState): Boolean
    fun room(): ListenTogetherRoomState?
    suspend fun refresh(baseUrl: String, roomId: String): ListenTogetherStateResponse
    fun apply(state: ListenTogetherRoomState, cause: String, expectedPositionMs: Long?): Boolean
    fun setError(error: String)
}

internal class ListenTogetherSafetyPauseResumeOwner(
    private val scope: CoroutineScope,
    private val mainScope: CoroutineScope,
    private val playback: ListenTogetherPlaybackHost,
    private val port: ListenTogetherSafetyPauseResumePort
) {
    fun resume() {
        val session = port.session()
        val baseUrl = session.baseUrl
        val roomId = session.roomId
        if (!isActiveListener(session)) { playback.retryListenTogetherSafetyPauseResume(); return }
        val identity = requireNotNull(baseUrl) to requireNotNull(roomId)
        scope.launch {
            runCatching { port.refresh(identity.first, identity.second) }
                .onSuccess { onResponse(identity.second, it) }
                .onFailure(::onFailure)
        }
    }

    private fun isActiveListener(session: ListenTogetherSessionState): Boolean =
        !session.baseUrl.isNullOrBlank() && !session.roomId.isNullOrBlank() && !port.isController(session)

    private fun onResponse(roomId: String, response: ListenTogetherStateResponse) {
        val state = response.state
        if (!response.ok || state == null) {
            playback.retryListenTogetherSafetyPauseResume()
            port.setError(response.error ?: "listener_safety_resume_state_unavailable")
            return
        }
        mainScope.launch { applyResponse(roomId, state, response.expectedPositionMs) }
    }

    private fun applyResponse(roomId: String, responseState: ListenTogetherRoomState, expected: Long?) {
        val currentSession = port.session()
        if (currentSession.roomId != roomId || port.isController(currentSession)) {
            playback.clearListenTogetherSafetyPause()
            return
        }
        val latest = roomToApply(roomId, responseState)
        val position = if (latest.version == responseState.version) expected else null
        val applied = port.apply(latest, LISTEN_TOGETHER_LISTENER_SAFETY_RESUME_CAUSE, position)
        if (applied) playback.completeListenTogetherSafetyPauseResume() else playback.retryListenTogetherSafetyPauseResume()
    }

    private fun roomToApply(roomId: String, response: ListenTogetherRoomState): ListenTogetherRoomState {
        val latest = port.room()
        return if (latest?.roomId == roomId) latest else response
    }

    private fun onFailure(error: Throwable) {
        playback.retryListenTogetherSafetyPauseResume()
        val message = error.message ?: error.javaClass.simpleName
        NPLogger.w("NERI-ListenTogether", "listener safety resume refresh failed: $message", error)
        port.setError(message)
    }
}
