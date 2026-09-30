package moe.ouom.neriplayer.data.ltw.invite

import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherInvite

import moe.ouom.neriplayer.api.ltw.http.normalizeBaseUrl

const val DEFAULT_LISTEN_TOGETHER_BASE_URL =
    moe.ouom.neriplayer.api.ltw.http.DEFAULT_LISTEN_TOGETHER_BASE_URL

fun configuredListenTogetherBaseUrlOrNull(value: String?): String? =
    moe.ouom.neriplayer.api.ltw.http.configuredListenTogetherBaseUrlOrNull(value)

/**
 * 邀请链接来自外部不可信来源, 强制 https, 避免被诱导连接明文 http 端点遭中间人劫持
 * 手动配置的自建服务器仍可用明文(走 configuredListenTogetherBaseUrlOrNull)
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

fun isDefaultListenTogetherBaseUrl(value: String?): Boolean =
    moe.ouom.neriplayer.api.ltw.http.isDefaultListenTogetherBaseUrl(value)
