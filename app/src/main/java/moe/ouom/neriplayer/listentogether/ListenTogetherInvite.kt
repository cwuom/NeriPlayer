package moe.ouom.neriplayer.listentogether

import android.net.Uri
import java.util.UUID
import androidx.core.net.toUri

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
    val baseUrl: String? = null
) {
    val signature: String
        get() = listOf(roomId, inviterNickname.orEmpty(), baseUrl.orEmpty()).joinToString("|")
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

fun resolveListenTogetherBaseUrl(value: String?): String {
    val normalized = value?.trim()?.takeIf { it.isNotBlank() }?.normalizedHttpBaseUrlOrNull()
    return normalized ?: DEFAULT_LISTEN_TOGETHER_BASE_URL.normalizeBaseUrl()
}

fun isDefaultListenTogetherBaseUrl(value: String?): Boolean {
    val normalized = value?.trim()?.takeIf { it.isNotBlank() }?.normalizedHttpBaseUrlOrNull()
    return normalized == DEFAULT_LISTEN_TOGETHER_BASE_URL.normalizeBaseUrl()
}

fun parseListenTogetherInvite(uri: Uri?): ListenTogetherInvite? {
    uri ?: return null
    if (!uri.scheme.equals(LISTEN_TOGETHER_INVITE_SCHEME, ignoreCase = true)) return null
    if (!uri.host.equals(LISTEN_TOGETHER_INVITE_HOST, ignoreCase = true)) return null
    val pathSegments = uri.pathSegments
    if (pathSegments.firstOrNull() != LISTEN_TOGETHER_INVITE_JOIN_PATH) return null
    val roomId = normalizeListenTogetherRoomId(uri.getQueryParameter("roomId").orEmpty())
    if (validateListenTogetherRoomId(roomId) != null) return null
    val inviterNickname = uri.getQueryParameter("inviter")
        ?.trim()
        ?.takeIf { it.isNotBlank() && validateListenTogetherNickname(it) == null }
    return ListenTogetherInvite(
        roomId = roomId,
        inviterNickname = inviterNickname,
        baseUrl = uri.getQueryParameter("baseUrl")?.trim()?.takeIf { it.isNotBlank() }
    )
}

fun parseListenTogetherInvite(rawText: String?): ListenTogetherInvite? {
    val text = rawText?.trim().orEmpty()
    if (text.isBlank()) return null
    parseListenTogetherInvite(text.toUri())?.let { return it }
    val match = LISTEN_TOGETHER_INVITE_REGEX.find(text)?.value ?: return null
    return parseListenTogetherInvite(match.toUri())
}
