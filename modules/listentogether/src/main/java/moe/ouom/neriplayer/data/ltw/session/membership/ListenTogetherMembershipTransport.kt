package moe.ouom.neriplayer.data.ltw.session.membership

import moe.ouom.neriplayer.api.ltw.http.ListenTogetherApi
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherInitialSnapshot
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherLeaveRoomResponse
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherRoomResponse

data class ListenTogetherCreateMembershipCall(
    val baseUrl: String,
    val userUuid: String,
    val nickname: String,
    val initialSnapshot: ListenTogetherInitialSnapshot
)

data class ListenTogetherJoinMembershipCall(
    val baseUrl: String,
    val roomId: String,
    val userUuid: String,
    val nickname: String,
    val memberSecret: String?,
    val joinSecret: String?,
    val bearerToken: String?
)

data class ListenTogetherLeaveMembershipCall(
    val baseUrl: String,
    val roomId: String,
    val token: String
)

interface ListenTogetherMembershipTransport {
    suspend fun create(call: ListenTogetherCreateMembershipCall): ListenTogetherRoomResponse
    suspend fun join(call: ListenTogetherJoinMembershipCall): ListenTogetherRoomResponse
    suspend fun leave(call: ListenTogetherLeaveMembershipCall): ListenTogetherLeaveRoomResponse
}

class ListenTogetherApiMembershipTransport(
    private val api: ListenTogetherApi
) : ListenTogetherMembershipTransport {
    override suspend fun create(call: ListenTogetherCreateMembershipCall): ListenTogetherRoomResponse =
        api.createRoom(
            baseUrl = call.baseUrl,
            userUuid = call.userUuid,
            nickname = call.nickname,
            initialSnapshot = call.initialSnapshot
        )

    override suspend fun join(call: ListenTogetherJoinMembershipCall): ListenTogetherRoomResponse =
        api.joinRoom(
            baseUrl = call.baseUrl,
            roomId = call.roomId,
            userUuid = call.userUuid,
            nickname = call.nickname,
            memberSecret = call.memberSecret,
            joinSecret = call.joinSecret,
            bearerToken = call.bearerToken
        )

    override suspend fun leave(call: ListenTogetherLeaveMembershipCall): ListenTogetherLeaveRoomResponse =
        api.leaveRoom(baseUrl = call.baseUrl, roomId = call.roomId, token = call.token)
}
