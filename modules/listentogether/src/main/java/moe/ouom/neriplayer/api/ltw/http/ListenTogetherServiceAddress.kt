package moe.ouom.neriplayer.api.ltw.http

const val DEFAULT_LISTEN_TOGETHER_BASE_URL = "https://neriplayer.hancat.work/"

fun configuredListenTogetherBaseUrlOrNull(value: String?): String? =
    value?.trim()?.takeIf(String::isNotBlank)?.normalizedHttpBaseUrlOrNull()

fun isDefaultListenTogetherBaseUrl(value: String?): Boolean =
    configuredListenTogetherBaseUrlOrNull(value) == DEFAULT_LISTEN_TOGETHER_BASE_URL.normalizeBaseUrl()
