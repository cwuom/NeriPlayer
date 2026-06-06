package moe.ouom.neriplayer.util

import java.util.Locale

private fun normalizeHostValidationValue(host: String?): String {
    return host
        ?.trim()
        ?.trim('.')
        ?.lowercase(Locale.US)
        .orEmpty()
}

fun String.matchesRootDomain(rootDomain: String): Boolean {
    val normalizedHost = normalizeHostValidationValue(this)
    val normalizedRoot = normalizeHostValidationValue(rootDomain)
    if (normalizedHost.isBlank() || normalizedRoot.isBlank()) {
        return false
    }
    return normalizedHost == normalizedRoot || normalizedHost.endsWith(".$normalizedRoot")
}
