package moe.ouom.neriplayer.listentogether.session

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState

internal data class AcceptedRoomState(
    val state: ListenTogetherRoomState,
    val expectedPositionMs: Long?
)

internal enum class RoomStateSource(val logName: String) {
    HTTP_REFRESH("http_refresh"),
    HTTP_CONTROL_FALLBACK("http_control_fallback"),
    HTTP_SESSION_UPDATE("http_session_update"),
    WEB_SOCKET_STATE("websocket_state"),
    WEB_SOCKET_CONTROL_RESULT("websocket_control_result"),
    WEB_SOCKET_ROOM_STATUS("websocket_room_status"),
    WEB_SOCKET_ROOM_CLOSED("websocket_room_closed"),
    LOCAL_SYNTHETIC("local_synthetic")
}
