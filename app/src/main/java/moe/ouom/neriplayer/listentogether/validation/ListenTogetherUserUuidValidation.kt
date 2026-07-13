package moe.ouom.neriplayer.listentogether.validation

import moe.ouom.neriplayer.R

private val USER_UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

fun validateListenTogetherUserUuid(userUuid: String): ListenTogetherValidationError? {
    val normalized = userUuid.trim()
    return when {
        normalized.isBlank() -> {
            ListenTogetherValidationError(R.string.listen_together_error_user_uuid_required)
        }

        !USER_UUID_REGEX.matches(normalized) -> {
            ListenTogetherValidationError(R.string.listen_together_error_user_uuid_invalid)
        }

        else -> null
    }
}

fun requireValidListenTogetherUserUuid(userUuid: String): String {
    val normalized = userUuid.trim()
    validateListenTogetherUserUuid(normalized)?.let { error(it.formatForApp()) }
    return normalized.lowercase()
}
