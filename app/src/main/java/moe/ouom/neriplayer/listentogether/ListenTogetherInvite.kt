package moe.ouom.neriplayer.listentogether

import android.net.Uri
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

const val DEFAULT_LISTEN_TOGETHER_BASE_URL =
    "https://neriplayer.hancat.work/"

private const val LISTEN_TOGETHER_INVITE_SCHEME = "neriplayer"
private const val LISTEN_TOGETHER_INVITE_HOST = "listen-together"
private const val LISTEN_TOGETHER_INVITE_JOIN_PATH = "join"
private val LISTEN_TOGETHER_INVITE_REGEX = Regex(
    pattern = """neriplayer://listen-together/join\?[^\s]+""",
    option = RegexOption.IGNORE_CASE
)

data class ListenTogetherInvite(
    val roomId: String,
    val inviterNickname: String? = null,
    val baseUrl: String? = null,
    val hasInvalidBaseUrl: Boolean = false
) {
    val signature: String
        get() = listOf(
            roomId,
            inviterNickname.orEmpty(),
            baseUrl.orEmpty(),
            hasInvalidBaseUrl.toString()
        ).joinToString("|")
}

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

fun configuredListenTogetherBaseUrlOrNull(value: String?): String? {
    return value
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.normalizedHttpBaseUrlOrNull()
}

fun resolveListenTogetherBaseUrl(value: String?): String {
    return configuredListenTogetherBaseUrlOrNull(value)
        ?: DEFAULT_LISTEN_TOGETHER_BASE_URL.normalizeBaseUrl()
}

fun isDefaultListenTogetherBaseUrl(value: String?): Boolean {
    return configuredListenTogetherBaseUrlOrNull(value) ==
        DEFAULT_LISTEN_TOGETHER_BASE_URL.normalizeBaseUrl()
}

fun parseListenTogetherInvite(uri: Uri?): ListenTogetherInvite? {
    return uri?.toString()?.let(::parseListenTogetherInviteInternal)
}

private fun parseListenTogetherInviteInternal(rawText: String): ListenTogetherInvite? {
    val uri = runCatching { URI(rawText) }.getOrNull() ?: return null
    if (!uri.scheme.equals(LISTEN_TOGETHER_INVITE_SCHEME, ignoreCase = true)) return null
    if (!uri.host.equals(LISTEN_TOGETHER_INVITE_HOST, ignoreCase = true)) return null
    val pathSegments = uri.path
        ?.split('/')
        ?.filter { it.isNotBlank() }
        .orEmpty()
    if (pathSegments.firstOrNull() != LISTEN_TOGETHER_INVITE_JOIN_PATH) return null
    val query = decodeInviteQuery(uri.rawQuery)
    val roomId = normalizeListenTogetherRoomId(query["roomId"].orEmpty())
    if (validateListenTogetherRoomId(roomId) != null) return null
    val inviterNickname = query["inviter"]
        ?.trim()
        ?.takeIf { it.isNotBlank() && validateListenTogetherNickname(it) == null }
    val rawBaseUrl = query["baseUrl"]?.trim().orEmpty()
    val normalizedBaseUrl = configuredListenTogetherBaseUrlOrNull(rawBaseUrl)
    return ListenTogetherInvite(
        roomId = roomId,
        inviterNickname = inviterNickname,
        baseUrl = normalizedBaseUrl,
        hasInvalidBaseUrl = rawBaseUrl.isNotBlank() && normalizedBaseUrl == null
    )
}

fun parseListenTogetherInvite(rawText: String?): ListenTogetherInvite? {
    val text = rawText?.trim().orEmpty()
    if (text.isBlank()) return null
    parseListenTogetherInviteInternal(text)?.let { return it }
    val match = LISTEN_TOGETHER_INVITE_REGEX.find(text)?.value ?: return null
    return parseListenTogetherInviteInternal(match)
}

private fun decodeInviteQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return rawQuery.split('&')
        .mapNotNull { pair ->
            val separatorIndex = pair.indexOf('=')
            val rawKey = if (separatorIndex >= 0) pair.substring(0, separatorIndex) else pair
            val rawValue = if (separatorIndex >= 0) pair.substring(separatorIndex + 1) else ""
            val key = decodeInviteQueryComponent(rawKey).trim()
            if (key.isBlank()) {
                null
            } else {
                key to decodeInviteQueryComponent(rawValue)
            }
        }
        .toMap()
}

private fun decodeInviteQueryComponent(value: String): String {
    return URLDecoder.decode(value, StandardCharsets.UTF_8)
}
