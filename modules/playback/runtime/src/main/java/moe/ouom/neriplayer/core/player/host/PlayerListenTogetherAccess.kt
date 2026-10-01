package moe.ouom.neriplayer.core.player.host

import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState

interface PlayerListenTogetherAccess {
    val roomState: StateFlow<ListenTogetherRoomState?>
    val sessionState: StateFlow<ListenTogetherSessionState>
    fun resumeListenerAfterSafetyPause()
    fun isControllerAudioLinkUnavailable(roomId: String, stableKey: String): Boolean
}
