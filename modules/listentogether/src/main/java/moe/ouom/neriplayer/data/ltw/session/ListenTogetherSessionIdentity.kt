package moe.ouom.neriplayer.data.ltw.session

internal fun String?.normalizedListenTogetherIdentity(): String? {
    val value = this?.trim() ?: return null
    return value.takeIf(String::isNotEmpty)
}
