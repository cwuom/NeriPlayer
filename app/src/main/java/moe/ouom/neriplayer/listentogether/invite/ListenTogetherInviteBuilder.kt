package moe.ouom.neriplayer.listentogether.invite

import android.net.Uri
import moe.ouom.neriplayer.listentogether.network.http.normalizeBaseUrl
import moe.ouom.neriplayer.listentogether.validation.requireValidListenTogetherNickname
import moe.ouom.neriplayer.listentogether.validation.requireValidListenTogetherRoomId
import java.util.UUID

private const val LISTEN_TOGETHER_INVITE_SCHEME = "neriplayer"
private const val LISTEN_TOGETHER_INVITE_HOST = "listen-together"
internal const val LISTEN_TOGETHER_INVITE_JOIN_PATH = "join"

fun buildListenTogetherUserUuid(): String {
    return UUID.randomUUID().toString()
}

fun buildDefaultListenTogetherNickname(): String {
    return "Neri${UUID.randomUUID().toString().replace("-", "").take(6).uppercase()}"
}

fun buildListenTogetherInviteUri(
    roomId: String,
    inviterNickname: String? = null,
    baseUrl: String? = null
): String {
    val normalizedRoomId = requireValidListenTogetherRoomId(roomId)
    val normalizedBaseUrl = baseUrl
        ?.takeIf { it.isNotBlank() }
        ?.normalizeBaseUrl()
        ?.takeUnless { isDefaultListenTogetherBaseUrl(it) }
    return Uri.Builder()
        .scheme(LISTEN_TOGETHER_INVITE_SCHEME)
        .authority(LISTEN_TOGETHER_INVITE_HOST)
        .appendPath(LISTEN_TOGETHER_INVITE_JOIN_PATH)
        .appendQueryParameter("roomId", normalizedRoomId)
        .apply {
            inviterNickname?.takeIf { it.isNotBlank() }?.let {
                appendQueryParameter("inviter", requireValidListenTogetherNickname(it))
            }
            normalizedBaseUrl?.let {
                appendQueryParameter("baseUrl", it)
            }
        }
        .build()
        .toString()
}
