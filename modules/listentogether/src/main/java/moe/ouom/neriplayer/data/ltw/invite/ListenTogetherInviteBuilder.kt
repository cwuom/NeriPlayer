package moe.ouom.neriplayer.data.ltw.invite

import android.net.Uri
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherValidationError
import moe.ouom.neriplayer.api.ltw.http.normalizeBaseUrl
import moe.ouom.neriplayer.data.ltw.validation.requireValidListenTogetherJoinSecret
import moe.ouom.neriplayer.data.ltw.validation.requireValidListenTogetherNickname
import moe.ouom.neriplayer.data.ltw.validation.requireValidListenTogetherRoomId

private const val LISTEN_TOGETHER_INVITE_SCHEME = "neriplayer"
private const val LISTEN_TOGETHER_INVITE_HOST = "listen-together"
internal const val LISTEN_TOGETHER_INVITE_JOIN_PATH = "join"

fun buildListenTogetherInviteUri(
    roomId: String,
    inviterNickname: String? = null,
    baseUrl: String? = null,
    joinSecret: String,
    inviteScheme: String = LISTEN_TOGETHER_INVITE_SCHEME,
    formatValidationError: (ListenTogetherValidationError) -> String
): String {
    val normalizedRoomId = requireValidListenTogetherRoomId(roomId, formatValidationError)
    val normalizedJoinSecret = requireValidListenTogetherJoinSecret(joinSecret, formatValidationError)
    val normalizedBaseUrl = sharedBaseUrl(baseUrl)
    return Uri.Builder()
        .scheme(inviteScheme)
        .authority(LISTEN_TOGETHER_INVITE_HOST)
        .appendPath(LISTEN_TOGETHER_INVITE_JOIN_PATH)
        .appendQueryParameter("roomId", normalizedRoomId)
        .apply {
            appendInviter(inviterNickname, formatValidationError)
            if (normalizedBaseUrl != null) appendQueryParameter("baseUrl", normalizedBaseUrl)
            appendQueryParameter("secret", normalizedJoinSecret)
        }
        .build()
        .toString()
}

private fun sharedBaseUrl(baseUrl: String?): String? {
    if (baseUrl.isNullOrBlank()) return null
    val normalized = baseUrl.normalizeBaseUrl()
    return normalized.takeUnless(::isDefaultListenTogetherBaseUrl)
}

private fun Uri.Builder.appendInviter(nickname: String?, formatter: (ListenTogetherValidationError) -> String) {
    if (nickname.isNullOrBlank()) return
    appendQueryParameter("inviter", requireValidListenTogetherNickname(nickname, formatter))
}
