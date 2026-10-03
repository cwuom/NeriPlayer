package moe.ouom.neriplayer.data.ltw.invite

import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherInvite

import android.net.Uri
import moe.ouom.neriplayer.data.ltw.validation.normalizeListenTogetherRoomId
import moe.ouom.neriplayer.data.ltw.validation.sanitizeListenTogetherJoinSecretOrNull
import moe.ouom.neriplayer.data.ltw.validation.validateListenTogetherNickname
import moe.ouom.neriplayer.data.ltw.validation.validateListenTogetherRoomId
import java.net.URI
import java.net.URLDecoder

private const val UTF_8_CHARSET_NAME = "UTF-8"
private const val LISTEN_TOGETHER_INVITE_SCHEME = "neriplayer"
private const val LISTEN_TOGETHER_DEBUG_INVITE_SCHEME = "neriplayer-debug"
private const val LISTEN_TOGETHER_INVITE_HOST = "listen-together"
private val LISTEN_TOGETHER_INVITE_REGEX = Regex(
    pattern = """(?<![a-z0-9+.-])neriplayer(?:-debug)?://listen-together/join\?[^\s]+""",
    option = RegexOption.IGNORE_CASE
)

fun parseListenTogetherInvite(uri: Uri?): ListenTogetherInvite? {
    return uri?.toString()?.let(::parseListenTogetherInviteInternal)
}

fun parseListenTogetherInvite(rawText: String?): ListenTogetherInvite? {
    val text = rawText?.trim().orEmpty()
    if (text.isBlank()) return null
    parseListenTogetherInviteInternal(text)?.let { return it }
    val match = LISTEN_TOGETHER_INVITE_REGEX.find(text)?.value ?: return null
    return parseListenTogetherInviteInternal(match)
}

private fun parseListenTogetherInviteInternal(rawText: String): ListenTogetherInvite? {
    val uri = runCatching { URI(rawText) }.getOrNull() ?: return null
    if (!hasInviteRoute(uri)) return null
    return inviteFromQuery(decodeInviteQuery(uri.rawQuery))
}

private fun hasInviteRoute(uri: URI): Boolean {
    if (
        !uri.scheme.equals(LISTEN_TOGETHER_INVITE_SCHEME, ignoreCase = true) &&
        !uri.scheme.equals(LISTEN_TOGETHER_DEBUG_INVITE_SCHEME, ignoreCase = true)
    ) return false
    if (!uri.host.equals(LISTEN_TOGETHER_INVITE_HOST, ignoreCase = true)) return false
    return hasJoinPath(uri.path ?: return false)
}

private fun hasJoinPath(path: String): Boolean {
    val pathSegments = path
        .split('/')
        .filter { it.isNotBlank() }
    return pathSegments.firstOrNull() == LISTEN_TOGETHER_INVITE_JOIN_PATH
}

private fun inviteFromQuery(query: Map<String, String>): ListenTogetherInvite? {
    val roomId = normalizeListenTogetherRoomId(query["roomId"].orEmpty())
    if (validateListenTogetherRoomId(roomId) != null) return null
    val inviterNickname = validInviterNickname(query["inviter"])
    val rawBaseUrl = trimmedInviteValue(query["baseUrl"])
    val normalizedBaseUrl = configuredListenTogetherInviteBaseUrlOrNull(rawBaseUrl)
    val joinSecret = sanitizeListenTogetherJoinSecretOrNull(query["secret"])
        ?: return null
    return ListenTogetherInvite(
        roomId = roomId,
        inviterNickname = inviterNickname,
        baseUrl = normalizedBaseUrl,
        joinSecret = joinSecret,
        hasInvalidBaseUrl = rawBaseUrl.isNotBlank() && normalizedBaseUrl == null
    )
}

private fun trimmedInviteValue(value: String?): String = value?.trim().orEmpty()

private fun decodeInviteQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return rawQuery.split('&').mapNotNull(::decodeInviteQueryPair).toMap()
}

private fun decodeInviteQueryPair(pair: String): Pair<String, String>? {
    val rawKey = pair.substringBefore('=')
    val rawValue = pair.substringAfter('=', "")
    val key = decodeInviteQueryKey(rawKey) ?: return null
    val value = decodeInviteQueryComponent(rawValue) ?: return null
    return key to value
}

private fun decodeInviteQueryKey(value: String): String? {
    val key = decodeInviteQueryComponent(value)?.trim() ?: return null
    return key.takeIf { it.isNotBlank() }
}

private fun validInviterNickname(value: String?): String? {
    val nickname = value?.trim() ?: return null
    return nickname.takeIf { it.isNotBlank() && validateListenTogetherNickname(it) == null }
}

private fun decodeInviteQueryComponent(value: String): String? {
    return runCatching {
        URLDecoder.decode(value, UTF_8_CHARSET_NAME)
    }.getOrNull()
}
