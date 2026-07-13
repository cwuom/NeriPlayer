package moe.ouom.neriplayer.listentogether.network.reconnect

internal const val LISTEN_TOGETHER_MAX_RECONNECT_ATTEMPTS = 15

internal fun isTerminalListenTogetherReconnectError(errorMessage: String?): Boolean {
    val normalized = errorMessage?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) return false
    return isUnauthorizedReconnectError(normalized) ||
        isClosedRoomReconnectError(normalized) ||
        isMissingRoomReconnectError(normalized)
}

internal fun listenTogetherReconnectDelayMs(attempt: Int): Long {
    val baseMs = when (attempt) {
        1 -> 1_500L
        2 -> 3_000L
        3 -> 5_000L
        4 -> 8_000L
        else -> 12_000L
    }
    val jitter = (baseMs * 0.2 * (Math.random() * 2.0 - 1.0)).toLong()
    return baseMs + jitter
}

private fun isUnauthorizedReconnectError(normalized: String): Boolean {
    return "unauthorized" in normalized
}

private fun isClosedRoomReconnectError(normalized: String): Boolean {
    return "room closed" in normalized || "http=410" in normalized || "(410)" in normalized
}

private fun isMissingRoomReconnectError(normalized: String): Boolean {
    return "room not initialized" in normalized ||
        "\"error\":\"room not initialized\"" in normalized ||
        "not found in do" in normalized ||
        "\"error\":\"not found in do\"" in normalized
}
