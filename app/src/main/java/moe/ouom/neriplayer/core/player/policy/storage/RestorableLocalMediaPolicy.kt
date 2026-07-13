package moe.ouom.neriplayer.core.player.policy.storage

internal enum class RestorableLocalMediaState {
    UNKNOWN,
    READABLE,
    REVOKED,
}

internal fun resolveRestorableLocalMediaState(
    scheme: String?,
    localFileReadable: Boolean = false,
    hasPersistedReadPermission: Boolean = false,
    hasCurrentReadPermission: Boolean = false,
): RestorableLocalMediaState {
    return when (scheme?.lowercase()) {
        null, "", "file" -> if (localFileReadable) {
            RestorableLocalMediaState.READABLE
        } else {
            RestorableLocalMediaState.REVOKED
        }
        "content" -> if (hasPersistedReadPermission || hasCurrentReadPermission) {
            RestorableLocalMediaState.READABLE
        } else {
            RestorableLocalMediaState.UNKNOWN
        }
        "android.resource" -> RestorableLocalMediaState.UNKNOWN
        else -> RestorableLocalMediaState.REVOKED
    }
}
