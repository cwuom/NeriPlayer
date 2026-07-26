package moe.ouom.neriplayer.listentogether.invite

import moe.ouom.neriplayer.listentogether.network.http.normalizeBaseUrl
import moe.ouom.neriplayer.listentogether.network.http.normalizedHttpBaseUrlOrNull

const val DEFAULT_LISTEN_TOGETHER_BASE_URL =
    "https://neriplayer.hancat.work/"

fun configuredListenTogetherBaseUrlOrNull(value: String?): String? {
    return value
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.normalizedHttpBaseUrlOrNull()
}

/**
 * 邀请链接来自外部不可信来源，强制 https，避免被诱导连接明文 http 端点遭中间人劫持。
 * 手动配置的自建服务器仍可用明文(走 configuredListenTogetherBaseUrlOrNull)。
 */
fun configuredListenTogetherInviteBaseUrlOrNull(value: String?): String? {
    return configuredListenTogetherBaseUrlOrNull(value)
        ?.takeIf { it.startsWith("https://", ignoreCase = true) }
}

fun resolveListenTogetherBaseUrl(value: String?): String {
    return configuredListenTogetherBaseUrlOrNull(value)
        ?: DEFAULT_LISTEN_TOGETHER_BASE_URL.normalizeBaseUrl()
}

fun resolveListenTogetherInviteJoinBaseUrl(
    invite: ListenTogetherInvite,
    savedBaseUrlInput: String?,
    savedBaseUrl: String?
): String {
    invite.baseUrl
        ?.let(::configuredListenTogetherBaseUrlOrNull)
        ?.let { return it }
    configuredListenTogetherBaseUrlOrNull(savedBaseUrlInput)
        ?.let { return it }
    configuredListenTogetherBaseUrlOrNull(savedBaseUrl)
        ?.let { return it }
    return resolveListenTogetherBaseUrl(null)
}

fun isDefaultListenTogetherBaseUrl(value: String?): Boolean {
    return configuredListenTogetherBaseUrlOrNull(value) ==
        DEFAULT_LISTEN_TOGETHER_BASE_URL.normalizeBaseUrl()
}
