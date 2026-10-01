package moe.ouom.neriplayer.core.download.policy.settings

const val MAX_DOWNLOAD_PARALLELISM = 8

fun normalizeDownloadParallelism(value: Int): Int {
    return value.coerceIn(1, MAX_DOWNLOAD_PARALLELISM)
}

fun normalizeDownloadFileNameTemplate(template: String?): String? {
    return template?.trim()?.takeIf { it.isNotEmpty() }
}
