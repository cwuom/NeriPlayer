package moe.ouom.neriplayer.data.ltw.session.membership

import moe.ouom.neriplayer.data.ltw.mapping.ListenTogetherSongMapper
import moe.ouom.neriplayer.data.ltw.session.membership.ListenTogetherCreateMembershipCall
import moe.ouom.neriplayer.data.ltw.session.membership.ListenTogetherJoinMembershipCall
import moe.ouom.neriplayer.data.ltw.session.membership.ListenTogetherLeaveMembershipCall
import moe.ouom.neriplayer.data.ltw.session.membership.ListenTogetherMembershipTransport
import moe.ouom.neriplayer.data.ltw.session.membership.resolveReusableListenTogetherMembershipCredential
import moe.ouom.neriplayer.data.ltw.session.membership.shouldAutoPauseListenTogetherForMemberChange
import moe.ouom.neriplayer.data.ltw.session.membership.toMembershipCredentialOrNull

import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherMembershipCredential

import moe.ouom.neriplayer.data.ltw.session.state.normalized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.api.ltw.ws.redactListenTogetherWsUrlForLog
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherInitialSnapshot
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherRoomResponse
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.ltw.validation.requireValidListenTogetherJoinSecret
import moe.ouom.neriplayer.data.ltw.validation.requireValidListenTogetherNickname
import moe.ouom.neriplayer.data.ltw.validation.requireValidListenTogetherRoomCreation
import moe.ouom.neriplayer.data.ltw.validation.requireValidListenTogetherRoomId
import moe.ouom.neriplayer.data.ltw.validation.requireValidListenTogetherUserUuid

internal interface ListenTogetherRoomMembershipPort {
    fun currentSession(): ListenTogetherSessionState
    fun repeatMode(): Int
    fun shuffleEnabled(): Boolean
    fun shuffleRestoreSongs(): List<SongItem>?
    fun applyRoomResponse(baseUrl: String, response: ListenTogetherRoomResponse)
    fun pauseForDeparture()
}

internal class ListenTogetherRoomMembershipOwner(
    private val scope: CoroutineScope,
    private val transport: ListenTogetherMembershipTransport,
    private val port: ListenTogetherRoomMembershipPort,
    private val songMapper: ListenTogetherSongMapper,
    private val formatValidationError: (moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherValidationError) -> String
) : ListenTogetherSongMapper by songMapper {
    @Volatile
    private var retainedCredential: ListenTogetherMembershipCredential? = null

    suspend fun createRoom(
        baseUrl: String,
        userUuid: String,
        nickname: String,
        queue: List<SongItem>,
        currentIndex: Int,
        positionMs: Long,
        isPlaying: Boolean,
        roomSettings: ListenTogetherRoomSettings,
        currentSong: SongItem?
    ): ListenTogetherRoomResponse {
        val validatedUserUuid = requireValidListenTogetherUserUuid(userUuid, formatValidationError)
        val validatedNickname = requireValidListenTogetherNickname(nickname, formatValidationError)
        requireValidListenTogetherRoomCreation(queue, currentIndex, currentSong, songMapper, formatValidationError)
        val snapshot = initialSnapshot(queue, currentIndex, positionMs, isPlaying, roomSettings)
        NPLogger.d(
            TAG,
            "createRoom(): baseUrl=$baseUrl, userUuid=$validatedUserUuid, nickname=$validatedNickname, queueSize=${queue.size}, shareableQueueSize=${snapshot.queue.size}, shuffleRestoreQueueSize=${snapshot.shuffleRestoreQueue?.size ?: 0}, currentIndex=$currentIndex, resolvedCurrentIndex=${snapshot.currentIndex}, isPlaying=$isPlaying, positionMs=$positionMs"
        )
        val response = transport.create(
            ListenTogetherCreateMembershipCall(baseUrl, validatedUserUuid, validatedNickname, snapshot)
        )
        port.applyRoomResponse(baseUrl, response)
        logResponse("createRoom", response)
        return response
    }

    private fun initialSnapshot(
        queue: List<SongItem>,
        currentIndex: Int,
        positionMs: Long,
        isPlaying: Boolean,
        roomSettings: ListenTogetherRoomSettings
    ): ListenTogetherInitialSnapshot {
        val (tracks, resolvedIndex) = queue.toShareableQueueSnapshot(
            currentIndex = currentIndex,
            roomSettings = roomSettings,
            includeResolvedStreamUrl = false
        )
        val shuffle = port.shuffleEnabled()
        return ListenTogetherInitialSnapshot(
            queue = tracks,
            currentIndex = resolvedIndex,
            track = tracks.getOrNull(resolvedIndex),
            settings = roomSettings.normalized(),
            isPlaying = isPlaying,
            positionMs = positionMs.coerceAtLeast(0L),
            repeatMode = port.repeatMode(),
            shuffleEnabled = shuffle,
            shuffleRestoreQueue = if (shuffle) shareableShuffleRestoreQueue(tracks) else null
        )
    }

    private fun shareableShuffleRestoreQueue(tracks: List<ListenTogetherTrack>): List<ListenTogetherTrack>? =
        port.shuffleRestoreSongs()
            ?.toShareableShuffleRestoreQueueSnapshot(tracks)
            ?.takeIf { it.isNotEmpty() }

    suspend fun joinRoom(
        baseUrl: String,
        roomId: String,
        userUuid: String,
        nickname: String,
        memberSecret: String?,
        joinSecret: String?
    ): ListenTogetherRoomResponse {
        val validatedRoomId = requireValidListenTogetherRoomId(roomId, formatValidationError)
        val validatedUserUuid = requireValidListenTogetherUserUuid(userUuid, formatValidationError)
        val validatedNickname = requireValidListenTogetherNickname(nickname, formatValidationError)
        NPLogger.d(TAG, "joinRoom(): baseUrl=$baseUrl, roomId=$validatedRoomId, userUuid=$validatedUserUuid, nickname=$validatedNickname")
        val credential = resolveReusableListenTogetherMembershipCredential(
            port.currentSession(), retainedCredential, baseUrl, validatedRoomId, validatedUserUuid
        )
        val access = resolveJoinAccess(memberSecret, joinSecret, credential)
        val response = transport.join(
            ListenTogetherJoinMembershipCall(
                baseUrl, validatedRoomId, validatedUserUuid, validatedNickname,
                access.memberSecret, access.joinSecret, access.bearerToken
            )
        )
        port.applyRoomResponse(baseUrl, response)
        logResponse("joinRoom", response)
        return response
    }

    private fun resolveJoinAccess(
        memberSecret: String?,
        joinSecret: String?,
        credential: ListenTogetherMembershipCredential?
    ): JoinAccess {
        val resolvedMemberSecret = memberSecret ?: credential?.memberSecret
        val resolvedJoinSecret = explicitJoinSecret(joinSecret) ?: credential?.joinSecret
        return JoinAccess(
            memberSecret = resolvedMemberSecret,
            joinSecret = joinSecretForAccess(resolvedJoinSecret, resolvedMemberSecret, credential?.token),
            bearerToken = credential?.token
        )
    }

    private fun explicitJoinSecret(value: String?): String? =
        value?.takeIf(String::isNotBlank)?.let { requireValidListenTogetherJoinSecret(it, formatValidationError) }

    private fun joinSecretForAccess(joinSecret: String?, memberSecret: String?, token: String?): String? {
        val hasMembership = !memberSecret.isNullOrBlank() || !token.isNullOrBlank()
        return if (hasMembership) joinSecret else requireValidListenTogetherJoinSecret(joinSecret, formatValidationError)
    }

    fun retainCurrentCredential() {
        port.currentSession().toMembershipCredentialOrNull()?.let { retainedCredential = it }
    }

    fun pauseBeforeLeave(snapshot: ListenTogetherSessionState, room: ListenTogetherRoomState?) {
        val autoPause = !snapshot.roomId.isNullOrBlank() && (room?.settings?.autoPauseOnMemberChange ?: true)
        if (shouldAutoPauseListenTogetherForMemberChange(autoPause, "MEMBER_LEFT")) {
            port.pauseForDeparture()
        }
    }

    fun notifyAndClearCredential(snapshot: ListenTogetherSessionState) {
        retainedCredential = null
        val call = leaveCall(snapshot) ?: return
        scope.launch { notifyServer(call) }
    }

    private fun leaveCall(snapshot: ListenTogetherSessionState): ListenTogetherLeaveMembershipCall? {
        val baseUrl = nonBlank(snapshot.baseUrl) ?: return null
        val roomId = nonBlank(snapshot.roomId) ?: return null
        val token = nonBlank(snapshot.token) ?: return null
        return ListenTogetherLeaveMembershipCall(baseUrl, roomId, token)
    }

    private fun nonBlank(value: String?): String? = value?.takeIf(String::isNotBlank)

    private suspend fun notifyServer(call: ListenTogetherLeaveMembershipCall) {
        runCatching { transport.leave(call) }
            .onSuccess { response ->
                if (!response.ok) {
                    NPLogger.w(TAG, "leaveRoom(): server rejected leave, roomId=${call.roomId}, error=${response.error}")
                }
            }
            .onFailure { error ->
                NPLogger.w(TAG, "leaveRoom(): server notification failed, roomId=${call.roomId}, error=${error.message}", error)
            }
    }

    private fun logResponse(operation: String, response: ListenTogetherRoomResponse) {
        NPLogger.d(
            TAG,
            "$operation(): ok=${response.ok}, roomId=${response.roomId}, role=${response.role}, wsUrl=${response.wsUrl.redactListenTogetherWsUrlForLog()}"
        )
    }

    private data class JoinAccess(
        val memberSecret: String?,
        val joinSecret: String?,
        val bearerToken: String?
    )

    private companion object {
        const val TAG = "NERI-ListenTogether"
    }
}
